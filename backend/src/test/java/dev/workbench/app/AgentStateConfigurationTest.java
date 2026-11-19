package dev.workbench.app;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.persistence.agentscope.JpaAgentStateStore;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** 使用隔离数据库验证 JPA 会话存储的重建读取和版本冲突。 */
class AgentStateConfigurationTest {
  @Test
  void frameworkStateSurvivesStoreRecreationAndRejectsStaleVersion() throws Exception {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:session_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
    }

    var factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(dataSource);
    factory.setPackagesToScan("dev.workbench.persistence.entity");
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
    factory.afterPropertiesSet();
    var entityManagerFactory = factory.getObject();
    try {
      var store = store(entityManagerFactory);
      var initial = AgentState.builder().sessionId("session-one").summary("第一版").build();
      assertEquals(1L, store.saveIfVersion(null, "session-one", "agent_state", initial, 0));
      var reopened = store(entityManagerFactory);
      var loaded = reopened.getVersioned(null, "session-one", "agent_state", AgentState.class);
      assertEquals(1L, loaded.version());
      assertEquals("第一版", loaded.value().getSummary());
      var updated = AgentState.builder().sessionId("session-one").summary("第二版").build();
      assertEquals(2L, reopened.saveIfVersion(null, "session-one", "agent_state", updated, 1));
      assertEquals(
          AgentStateStore.UNVERSIONED,
          store.saveIfVersion(null, "session-one", "agent_state", initial, 1));
      assertEquals(
          "第二版",
          store.get(null, "session-one", "agent_state", AgentState.class).orElseThrow().getSummary());
      assertTrue(store.get(null, "session-two", "agent_state", AgentState.class).isEmpty());
      assertTrue(store.get("other-user", "session-one", "agent_state", AgentState.class).isEmpty());
    } finally {
      entityManagerFactory.close();
    }
  }

  @Test
  void listAppendFromLegacyJdbcStoreWritesOnlyNewTailAndReadsAfterReopen() throws Exception {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:session_list_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
            "sa",
            "");
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
    }
    var factory = entityManagerFactory(dataSource);
    try {
      var a = AgentState.builder().sessionId("list-session").summary("A").build();
      var b = AgentState.builder().sessionId("list-session").summary("B").build();
      var c = AgentState.builder().sessionId("list-session").summary("C").build();
      var legacy = new JdbcAgentStateStore(dataSource, new H2Dialect(), true);
      legacy.save(null, "list-session", "messages", List.of(a, b));

      var store = store(factory);
      store.save(null, "list-session", "messages", List.of(a, b, c));

      var reopened = store(factory);
      var loaded =
          reopened.getList(null, "list-session", "messages", AgentState.class).stream()
              .map(AgentState::getSummary)
              .toList();
      assertEquals(List.of("A", "B", "C"), loaded);
      assertEquals(3, loaded.size());
    } finally {
      factory.close();
    }
  }

  @Test
  void concurrentUnconditionalSavesDoNotExposePrimaryKeyConflict() throws Exception {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:session_concurrent_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
            "sa",
            "");
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
    }
    var factory = entityManagerFactory(dataSource);
    try {
      var store = gatedStore(factory, 2);
      var gate = new CountDownLatch(1);
      Callable<Void> save =
          () -> {
            gate.await();
            store.save(
                null,
                "concurrent-session",
                "agent_state",
                AgentState.builder().sessionId("concurrent-session").summary("并发").build());
            return null;
          };
      try (var pool = Executors.newFixedThreadPool(2)) {
        var first = pool.submit(save);
        var second = pool.submit(save);
        gate.countDown();
        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);
      }
      var loaded =
          store
              .get(null, "concurrent-session", "agent_state", AgentState.class)
              .orElseThrow();
      assertEquals("并发", loaded.getSummary());
      assertEquals(
          2L,
          store
              .getVersioned(null, "concurrent-session", "agent_state", AgentState.class)
              .version());
    } finally {
      factory.close();
    }
  }

  @Test
  void concurrentUnversionedSaveIfVersionWritesBothAndEndsAtVersionTwo() throws Exception {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:session_unversioned_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
            "sa",
            "");
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
    }
    var factory = entityManagerFactory(dataSource);
    try {
      var store = gatedStore(factory, 2);
      var gate = new CountDownLatch(1);
      Callable<Long> save =
          () -> {
            gate.await();
            return store.saveIfVersion(
                null,
                "unversioned-session",
                "agent_state",
                AgentState.builder().sessionId("unversioned-session").summary("并发").build(),
                AgentStateStore.UNVERSIONED);
          };
      long firstVersion;
      long secondVersion;
      try (var pool = Executors.newFixedThreadPool(2)) {
        var first = pool.submit(save);
        var second = pool.submit(save);
        gate.countDown();
        firstVersion = first.get(20, TimeUnit.SECONDS);
        secondVersion = second.get(20, TimeUnit.SECONDS);
      }
      assertEquals(new java.util.TreeSet<>(List.of(1L, 2L)),
          new java.util.TreeSet<>(List.of(firstVersion, secondVersion)));
      var loaded =
          store.getVersioned(null, "unversioned-session", "agent_state", AgentState.class);
      assertEquals(2L, loaded.version());
      assertEquals("并发", loaded.value().getSummary());
    } finally {
      factory.close();
    }
  }

  private static jakarta.persistence.EntityManagerFactory entityManagerFactory(
      javax.sql.DataSource dataSource) {
    var factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(dataSource);
    factory.setPackagesToScan("dev.workbench.persistence.entity");
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
    factory.afterPropertiesSet();
    return factory.getObject();
  }

  private static AgentStateStore store(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
    return new AgentStateConfiguration()
        .agentStateStore(entityManagerFactory, new JpaTransactionManager(entityManagerFactory));
  }

  /** 用插入前同步点强制两个请求同时走到新主键插入，而不是靠线程调度碰运气。 */
  private static AgentStateStore gatedStore(
      jakarta.persistence.EntityManagerFactory entityManagerFactory, int parties) {
    return new GatedStore(
        entityManagerFactory, new JpaTransactionManager(entityManagerFactory), parties);
  }

  private static final class GatedStore extends JpaAgentStateStore {
    private final java.util.concurrent.CyclicBarrier barrier;

    GatedStore(
        jakarta.persistence.EntityManagerFactory entityManagerFactory,
        org.springframework.transaction.PlatformTransactionManager transactionManager,
        int parties) {
      super(entityManagerFactory, transactionManager);
      this.barrier = new java.util.concurrent.CyclicBarrier(parties);
    }

    @Override
    protected void beforeInsert() {
      try {
        barrier.await(10, TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new IllegalStateException("插入同步点等待失败", e);
      }
    }
  }
}
