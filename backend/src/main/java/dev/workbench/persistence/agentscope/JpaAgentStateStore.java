package dev.workbench.persistence.agentscope;

import dev.workbench.persistence.entity.AgentScopeSessionEntity;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AgentScope {@link AgentStateStore} 的 JPA 适配。
 *
 * <p>沿用框架 JDBC 存储的表 agentscope_sessions、{@code 用户:会话} 命名空间、JSON 文本和
 * {@code key:_hash} 列表哈希格式。单值状态插入版本 1、更新版本加 1；列表项新建版本 0，与旧实现一致。
 * 每次写操作在独立事务中提交，避免会话状态与调用方业务事务互相影响。
 *
 * <p>按旧实现，{@link #delete(String, String, String)} 仍使用接口默认的空操作，不提供按键删除。
 */
public class JpaAgentStateStore implements AgentStateStore {
  private static final String HASH_KEY_SUFFIX = ":_hash";
  private static final int SINGLE_STATE_INDEX = 0;
  private static final String ANON_USER = "__anon__";
  private static final char LIKE_ESCAPE = '!';

  private final EntityManagerFactory entityManagerFactory;
  private final TransactionTemplate writeTransaction;

  public JpaAgentStateStore(
      EntityManagerFactory entityManagerFactory, PlatformTransactionManager transactionManager) {
    this.entityManagerFactory =
        Objects.requireNonNull(entityManagerFactory, "entityManagerFactory");
    this.writeTransaction = new TransactionTemplate(transactionManager);
    this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public void save(String userId, String sessionId, String key, State value) {
    String slotId = slotId(userId, sessionId);
    validateSlotId(slotId);
    validateStateKey(key);
    String json = JsonUtils.getJsonCodec().toJson(value);
    try {
      saveUnconditional(slotId, key, json);
    } catch (RuntimeException e) {
      throw new RuntimeException("Failed to save state: " + key, e);
    }
  }

  /**
   * 无条件保存的并发语义：先尝试更新，不存在则插入；两个请求同时插入时只有一个成功，
   * 另一个因主键冲突回滚后在新事务中改为更新，不把 23505 暴露给调用方。
   *
   * <p>写入事务内读回最终版本，供 {@link #saveIfVersion} 的 {@link #UNVERSIONED} 分支返回，
   * 避免提交后再读时被其他写入干扰。
   */
  private long saveUnconditional(String slotId, String key, String json) {
    RuntimeException last = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return inTransaction(
            em -> {
              upsert(em, slotId, key, json);
              return readVersion(em, slotId, key);
            });
      } catch (RuntimeException failure) {
        if (!isConstraintViolation(failure)) throw failure;
        last = failure;
      }
    }
    throw last;
  }

  @Override
  public void save(String userId, String sessionId, String key, List<? extends State> values) {
    String slotId = slotId(userId, sessionId);
    validateSlotId(slotId);
    validateStateKey(key);
    if (values.isEmpty()) {
      return;
    }
    String hashKey = key + HASH_KEY_SUFFIX;
    try {
      inTransaction(
          em -> {
            String currentHash = ListHashUtil.computeHash(values);
            String storedHash = readData(em, slotId, hashKey);
            int existingCount = listCount(em, slotId, key);
            boolean needsFullRewrite =
                ListHashUtil.needsFullRewrite(values, storedHash, existingCount);

            if (needsFullRewrite) {
              deleteByKey(em, slotId, key);
              insertItems(em, slotId, key, values, 0);
              upsert(em, slotId, hashKey, currentHash);
            } else if (values.size() > existingCount) {
              // 增量追加只写新增尾部；读取游标从已有数量开始，避免重复写入 A、B。
              insertItems(
                  em,
                  slotId,
                  key,
                  values.subList(existingCount, values.size()),
                  existingCount);
              upsert(em, slotId, hashKey, currentHash);
            }
            return null;
          });
    } catch (RuntimeException e) {
      throw new RuntimeException("Failed to save list: " + key, e);
    }
  }

  @Override
  public boolean supportsVersioning() {
    return true;
  }

  @Override
  public <T extends State> VersionedState<T> getVersioned(
      String userId, String sessionId, String key, Class<T> type) {
    String slotId = slotId(userId, sessionId);
    validateSlotId(slotId);
    validateStateKey(key);
    try {
      return read(
          em -> {
            AgentScopeSessionEntity row =
                em.find(
                    AgentScopeSessionEntity.class,
                    new AgentScopeSessionEntity.Id(slotId, key, SINGLE_STATE_INDEX));
            if (row == null) {
              return new VersionedState<>(null, 0L);
            }
            return new VersionedState<>(
                JsonUtils.getJsonCodec().fromJson(row.getStateData(), type), row.getVersion());
          });
    } catch (RuntimeException e) {
      throw new RuntimeException("Failed to get versioned state: " + key, e);
    }
  }

  @Override
  public long saveIfVersion(
      String userId, String sessionId, String key, State value, long expectedVersion) {
    String slotId = slotId(userId, sessionId);
    validateSlotId(slotId);
    validateStateKey(key);
    String json = JsonUtils.getJsonCodec().toJson(value);
    if (expectedVersion == UNVERSIONED) {
      // 与 save(State) 共用同一套插入冲突重试；返回值取自写入事务内。
      try {
        return saveUnconditional(slotId, key, json);
      } catch (RuntimeException e) {
        throw new RuntimeException("Failed to save state if version: " + key, e);
      }
    }
    try {
      return inTransaction(
          em -> {
            if (expectedVersion == 0L) {
              int updated = updateIfVersion(em, slotId, key, json, 0L);
              if (updated == 1) {
                return 1L;
              }
              if (existsEntry(em, slotId, key)) {
                return UNVERSIONED;
              }
              em.persist(
                  new AgentScopeSessionEntity(slotId, key, SINGLE_STATE_INDEX, json, 1L));
              em.flush();
              return 1L;
            }
            int updated = updateIfVersion(em, slotId, key, json, expectedVersion);
            return updated == 1 ? expectedVersion + 1L : UNVERSIONED;
          });
    } catch (RuntimeException e) {
      if (expectedVersion == 0L && isConstraintViolation(e)) {
        return UNVERSIONED;
      }
      throw new RuntimeException("Failed to save state if version: " + key, e);
    }
  }

  @Override
  public <T extends State> Optional<T> get(
      String userId, String sessionId, String key, Class<T> type) {
    String slotId = slotId(userId, sessionId);
    validateSlotId(slotId);
    validateStateKey(key);
    try {
      return read(
          em -> {
            AgentScopeSessionEntity row =
                em.find(
                    AgentScopeSessionEntity.class,
                    new AgentScopeSessionEntity.Id(slotId, key, SINGLE_STATE_INDEX));
            return row == null
                ? Optional.empty()
                : Optional.of(JsonUtils.getJsonCodec().fromJson(row.getStateData(), type));
          });
    } catch (RuntimeException e) {
      throw new RuntimeException("Failed to get state: " + key, e);
    }
  }

  @Override
  public <T extends State> List<T> getList(
      String userId, String sessionId, String key, Class<T> itemType) {
    String slotId = slotId(userId, sessionId);
    validateSlotId(slotId);
    validateStateKey(key);
    try {
      return read(
          em -> {
            List<String> rows =
                em.createQuery(
                        "select e.stateData from AgentScopeSessionEntity e"
                            + " where e.id.sessionId = :sessionId and e.id.stateKey = :stateKey"
                            + " order by e.id.itemIndex asc",
                        String.class)
                    .setParameter("sessionId", slotId)
                    .setParameter("stateKey", key)
                    .getResultList();
            List<T> result = new ArrayList<>(rows.size());
            for (String row : rows) {
              result.add(JsonUtils.getJsonCodec().fromJson(row, itemType));
            }
            return result;
          });
    } catch (RuntimeException e) {
      throw new RuntimeException("Failed to get list: " + key, e);
    }
  }

  @Override
  public boolean exists(String userId, String sessionId) {
    String slotId = slotId(userId, sessionId);
    validateSlotId(slotId);
    try {
      return read(
          em ->
              em.createQuery(
                      "select count(e) from AgentScopeSessionEntity e"
                          + " where e.id.sessionId = :sessionId",
                      Long.class)
                  .setParameter("sessionId", slotId)
                  .getSingleResult()
                  > 0);
    } catch (RuntimeException e) {
      throw new RuntimeException("Failed to check session existence: " + slotId, e);
    }
  }

  @Override
  public void delete(String userId, String sessionId) {
    String slotId = slotId(userId, sessionId);
    validateSlotId(slotId);
    try {
      inTransaction(
          em -> {
            em.createQuery(
                    "delete from AgentScopeSessionEntity e where e.id.sessionId = :sessionId")
                .setParameter("sessionId", slotId)
                .executeUpdate();
            return null;
          });
    } catch (RuntimeException e) {
      throw new RuntimeException("Failed to delete session: " + slotId, e);
    }
  }

  @Override
  public Set<String> listSessionIds(String userId) {
    String prefix = normalizeUser(userId) + ":";
    String pattern = likePrefixPattern(prefix);
    try {
      return read(
          em -> {
            List<String> slots =
                em.createQuery(
                        "select distinct e.id.sessionId from AgentScopeSessionEntity e"
                            + " where e.id.sessionId like :pattern escape '"
                            + LIKE_ESCAPE
                            + "' order by e.id.sessionId",
                        String.class)
                    .setParameter("pattern", pattern)
                    .getResultList();
            Set<String> sessionIds = new LinkedHashSet<>();
            for (String slot : slots) {
              sessionIds.add(slot.substring(prefix.length()));
            }
            return sessionIds;
          });
    } catch (RuntimeException e) {
      throw new RuntimeException("Failed to list sessions", e);
    }
  }

  private <T> T inTransaction(Function<EntityManager, T> work) {
    return writeTransaction.execute(
        status -> {
          EntityManager em =
              EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
          if (em == null) {
            throw new IllegalStateException("JPA EntityManager is not available");
          }
          return work.apply(em);
        });
  }

  private <T> T read(Function<EntityManager, T> work) {
    EntityManager em = entityManagerFactory.createEntityManager();
    try {
      return work.apply(em);
    } finally {
      em.close();
    }
  }

  private void upsert(EntityManager em, String slotId, String key, String data) {
    int updated = update(em, slotId, key, data);
    if (updated == 0) {
      beforeInsert();
      em.persist(
          new AgentScopeSessionEntity(slotId, key, SINGLE_STATE_INDEX, data, 1L));
    }
  }

  /** 插入新主键前的同步点；生产实现为空操作，测试用它强制并发插入冲突。 */
  protected void beforeInsert() {}

  private static int update(EntityManager em, String slotId, String key, String data) {
    return em.createQuery(
            "update AgentScopeSessionEntity e"
                + " set e.stateData = :data, e.version = e.version + 1"
                + " where e.id = :id")
        .setParameter("id", new AgentScopeSessionEntity.Id(slotId, key, SINGLE_STATE_INDEX))
        .setParameter("data", data)
        .executeUpdate();
  }

  private static int updateIfVersion(
      EntityManager em, String slotId, String key, String data, long expectedVersion) {
    return em.createQuery(
            "update AgentScopeSessionEntity e"
                + " set e.stateData = :data, e.version = :nextVersion"
                + " where e.id = :id and e.version = :expectedVersion")
        .setParameter("id", new AgentScopeSessionEntity.Id(slotId, key, SINGLE_STATE_INDEX))
        .setParameter("data", data)
        .setParameter("nextVersion", expectedVersion + 1L)
        .setParameter("expectedVersion", expectedVersion)
        .executeUpdate();
  }

  private static long readVersion(EntityManager em, String slotId, String key) {
    List<Long> versions =
        em.createQuery(
                "select e.version from AgentScopeSessionEntity e where e.id = :id", Long.class)
            .setParameter("id", new AgentScopeSessionEntity.Id(slotId, key, SINGLE_STATE_INDEX))
            .getResultList();
    return versions.isEmpty() ? 0L : versions.get(0);
  }

  private static boolean existsEntry(EntityManager em, String slotId, String key) {
    return !em.createQuery(
            "select e.version from AgentScopeSessionEntity e where e.id = :id", Long.class)
        .setParameter("id", new AgentScopeSessionEntity.Id(slotId, key, SINGLE_STATE_INDEX))
        .getResultList()
        .isEmpty();
  }

  private static String readData(EntityManager em, String slotId, String key) {
    List<String> rows =
        em.createQuery(
                "select e.stateData from AgentScopeSessionEntity e where e.id = :id", String.class)
            .setParameter("id", new AgentScopeSessionEntity.Id(slotId, key, SINGLE_STATE_INDEX))
            .getResultList();
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static int listCount(EntityManager em, String slotId, String key) {
    List<Integer> maxIndexes =
        em.createQuery(
                "select max(e.id.itemIndex) from AgentScopeSessionEntity e"
                    + " where e.id.sessionId = :sessionId and e.id.stateKey = :stateKey",
                Integer.class)
            .setParameter("sessionId", slotId)
            .setParameter("stateKey", key)
            .getResultList();
    Integer maxIndex = maxIndexes.isEmpty() ? null : maxIndexes.get(0);
    return maxIndex == null ? 0 : maxIndex + 1;
  }

  private static void insertItems(
      EntityManager em, String slotId, String key, List<? extends State> items, int startIndex) {
    for (int i = 0; i < items.size(); i++) {
      String json = JsonUtils.getJsonCodec().toJson(items.get(i));
      em.persist(
          new AgentScopeSessionEntity(slotId, key, startIndex + i, json, 0L));
    }
  }

  private static void deleteByKey(EntityManager em, String slotId, String key) {
    em.createQuery(
            "delete from AgentScopeSessionEntity e"
                + " where e.id.sessionId = :sessionId and e.id.stateKey = :stateKey")
        .setParameter("sessionId", slotId)
        .setParameter("stateKey", key)
        .executeUpdate();
  }

  private static boolean isConstraintViolation(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof ConstraintViolationException
          || current instanceof DataIntegrityViolationException) {
        return true;
      }
    }
    return false;
  }

  private static String likePrefixPattern(String prefix) {
    StringBuilder pattern = new StringBuilder();
    for (char ch : prefix.toCharArray()) {
      if (ch == LIKE_ESCAPE || ch == '%' || ch == '_') {
        pattern.append(LIKE_ESCAPE);
      }
      pattern.append(ch);
    }
    pattern.append('%');
    return pattern.toString();
  }

  private static String normalizeUser(String userId) {
    return userId == null || userId.isBlank() ? ANON_USER : userId;
  }

  private static String slotId(String userId, String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    return normalizeUser(userId) + ":" + sessionId;
  }

  private static void validateSlotId(String slotId) {
    if (slotId == null || slotId.trim().isEmpty()) {
      throw new IllegalArgumentException("Session ID cannot be null or empty");
    }
    if (slotId.length() > 255) {
      throw new IllegalArgumentException("Session ID cannot exceed 255 characters");
    }
  }

  private static void validateStateKey(String key) {
    if (key == null || key.trim().isEmpty()) {
      throw new IllegalArgumentException("State key cannot be null or empty");
    }
    if (key.length() > 255) {
      throw new IllegalArgumentException("State key cannot exceed 255 characters");
    }
  }
}
