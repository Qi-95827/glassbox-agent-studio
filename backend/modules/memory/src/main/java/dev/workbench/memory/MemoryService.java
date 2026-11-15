package dev.workbench.memory;

import dev.workbench.task.RunRepository;
import dev.workbench.workspace.WorkspaceService;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 单机本地工作区的用户确认记忆；不自动把模型输出写入长期记忆。 */
@Service
public class MemoryService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final WorkspaceService workspaces;

  public MemoryService(JdbcTemplate db, TransactionTemplate tx, WorkspaceService workspaces) {
    this.db = db;
    this.tx = tx;
    this.workspaces = workspaces;
  }

  public List<MemoryRecord> list(String workspaceId) {
    workspaces.get(workspaceId);
    return db.query(
        "SELECT * FROM memory_records WHERE workspace_id=? AND status=? ORDER BY updated_at"
            + " DESC,id",
        (r, i) ->
            new MemoryRecord(
                UUID.fromString(r.getString("id")),
                UUID.fromString(r.getString("workspace_id")),
                MemoryCategory.valueOf(r.getString("category")),
                r.getString("content"),
                MemorySource.valueOf(r.getString("source")),
                MemoryStatus.valueOf(r.getString("status")),
                r.getInt("version"),
                Instant.parse(r.getString("created_at")),
                Instant.parse(r.getString("updated_at"))),
        workspaceId,
        MemoryStatus.ACTIVE.name());
  }

  /** 新任务显式开启记忆时才调用；最多取五条且重新查权威库。 */
  public List<MemoryRecord> context(String workspaceId) {
    return list(workspaceId).stream().limit(5).toList();
  }

  /** 先持久化装配引用，再返回同一批内容；不能据此断言模型已接收。 */
  public List<MemoryRecord> loadContextAndRecord(String runId, String workspaceId) {
    return tx.execute(
        status -> {
          var records = context(workspaceId);
          db.update(
              "INSERT INTO run_memory_loads(run_id,workspace_id,item_count,loaded_at)"
                  + " VALUES(?,?,?,?)",
              runId,
              workspaceId,
              records.size(),
              RunRepository.now());
          for (int index = 0; index < records.size(); index++) {
            var record = records.get(index);
            db.update(
                "INSERT INTO"
                    + " run_memory_references(run_id,sequence_no,memory_id,memory_version,category)"
                    + " VALUES(?,?,?,?,?)",
                runId,
                index + 1,
                record.id().toString(),
                record.version(),
                record.category().name());
          }
          return records;
        });
  }

  /** 没有装配标记时返回未记录，不能把历史数据缺失解释为已装配零条。 */
  public MemoryLoad loadForRun(String runId) {
    var loads =
        db.query(
            "SELECT workspace_id,loaded_at,item_count FROM run_memory_loads WHERE run_id=?",
            (r, i) ->
                new MemoryLoad(
                    true,
                    UUID.fromString(r.getString("workspace_id")),
                    Instant.parse(r.getString("loaded_at")),
                    r.getInt("item_count"),
                    MemoryLoadIntegrity.INCONSISTENT,
                    List.of()),
            runId);
    if (loads.isEmpty())
      return new MemoryLoad(false, null, null, null, MemoryLoadIntegrity.NOT_RECORDED, List.of());
    var references =
        db.query(
            "SELECT sequence_no,memory_id,memory_version,category FROM run_memory_references"
                + " WHERE run_id=? ORDER BY sequence_no",
            (r, i) ->
                new MemoryReference(
                    r.getInt("sequence_no"),
                    UUID.fromString(r.getString("memory_id")),
                    r.getInt("memory_version"),
                    MemoryCategory.valueOf(r.getString("category"))),
            runId);
    var load = loads.getFirst();
    boolean ordered = true;
    for (int i = 0; i < references.size(); i++) {
      if (references.get(i).sequence() != i + 1) ordered = false;
    }
    boolean consistent =
        load.declaredCount() >= 0 && load.declaredCount() == references.size() && ordered;
    return new MemoryLoad(
        true,
        load.workspaceId(),
        load.loadedAt(),
        load.declaredCount(),
        consistent ? MemoryLoadIntegrity.CONSISTENT : MemoryLoadIntegrity.INCONSISTENT,
        references);
  }

  /** 只读历史严格按工作区过滤；游标只取更早版本，避免并发新增导致重复。 */
  public MemoryHistoryPage history(
      String workspaceId, String memoryId, Integer beforeVersion, int limit) {
    if (limit < 1 || limit > 50 || (beforeVersion != null && beforeVersion < 1))
      throw new IllegalArgumentException("历史页大小应为 1 至 50，版本游标必须为正数。");
    workspaces.get(workspaceId);
    Integer exists =
        db.queryForObject(
            "SELECT COUNT(*) FROM memory_records WHERE id=? AND workspace_id=?",
            Integer.class,
            memoryId,
            workspaceId);
    if (exists == null || exists != 1)
      throw new org.springframework.dao.EmptyResultDataAccessException(1);
    String cursor = beforeVersion == null ? "" : " AND h.version<?";
    var args = new java.util.ArrayList<Object>();
    args.add(memoryId);
    args.add(workspaceId);
    if (beforeVersion != null) args.add(beforeVersion);
    args.add(limit + 1);
    var rows =
        db.query(
            "SELECT h.* FROM memory_history h JOIN memory_records m ON m.id=h.memory_id"
                + " WHERE m.id=? AND m.workspace_id=?"
                + cursor
                + " ORDER BY h.version DESC LIMIT ?",
            (r, i) ->
                new MemoryHistoryVersion(
                    UUID.fromString(r.getString("memory_id")),
                    r.getInt("version"),
                    MemoryCategory.valueOf(r.getString("category")),
                    r.getString("content"),
                    MemoryStatus.valueOf(r.getString("status")),
                    Instant.parse(r.getString("changed_at"))),
            args.toArray());
    boolean hasMore = rows.size() > limit;
    var items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
    return new MemoryHistoryPage(items, hasMore ? items.getLast().version() : null);
  }

  /** 在指定工作区内按字面文本和类别筛选有效记忆，不参与 Agent 上下文排序。 */
  public List<MemoryRecord> search(
      String workspaceId, String query, MemoryCategory category, int limit) {
    if (limit < 1 || limit > 20) throw new IllegalArgumentException("查询条数应为 1 至 20。");
    String text = query == null ? "" : query.strip();
    if (text.length() > 100) throw new IllegalArgumentException("查询关键词最多 100 字。");
    String normalized = normalize(text);
    return list(workspaceId).stream()
        .filter(record -> category == null || record.category() == category)
        .filter(record -> normalize(record.content()).contains(normalized))
        .limit(limit)
        .toList();
  }

  private static String normalize(String text) {
    return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }

  /** 同一进程内串行限制条数；共享部署需重新设计并发与租户隔离。 */
  public synchronized MemoryRecord create(
      String workspaceId, MemoryCategory category, String content) {
    workspaces.get(workspaceId);
    String text = validate(category, content);
    return tx.execute(
        s -> {
          if (list(workspaceId).size() >= 20)
            throw new IllegalStateException("每个工作区最多保存 20 条有效记忆。");
          String id = RunRepository.id(), time = RunRepository.now();
          db.update(
              "INSERT INTO memory_records VALUES(?,?,?,?,?,?,?,?,?)",
              id,
              workspaceId,
              category.name(),
              text,
              MemorySource.USER_CONFIRMED.name(),
              MemoryStatus.ACTIVE.name(),
              1,
              time,
              time);
          saveHistory(id);
          return getActive(workspaceId, id);
        });
  }

  public MemoryRecord update(
      String workspaceId, String id, int version, MemoryCategory category, String content) {
    String text = validate(category, content);
    return tx.execute(
        s -> {
          int changed =
              db.update(
                  "UPDATE memory_records SET category=?,content=?,version=version+1,updated_at=?"
                      + " WHERE id=? AND workspace_id=? AND status=? AND version=?",
                  category.name(),
                  text,
                  RunRepository.now(),
                  id,
                  workspaceId,
                  MemoryStatus.ACTIVE.name(),
                  version);
          if (changed != 1) throw new IllegalStateException("记忆不存在、已删除或版本已变化。");
          saveHistory(id);
          return getActive(workspaceId, id);
        });
  }

  public void delete(String workspaceId, String id, int version) {
    tx.executeWithoutResult(
        s -> {
          int changed =
              db.update(
                  "UPDATE memory_records SET status=?,version=version+1,updated_at=? WHERE id=? AND"
                      + " workspace_id=? AND status=? AND version=?",
                  MemoryStatus.DELETED.name(),
                  RunRepository.now(),
                  id,
                  workspaceId,
                  MemoryStatus.ACTIVE.name(),
                  version);
          if (changed != 1) throw new IllegalStateException("记忆不存在、已删除或版本已变化。");
          saveHistory(id);
        });
  }

  private MemoryRecord getActive(String workspaceId, String id) {
    return list(workspaceId).stream()
        .filter(r -> r.id().toString().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private void saveHistory(String id) {
    db.update(
        "INSERT INTO memory_history(memory_id,version,category,content,status,changed_at) SELECT"
            + " id,version,category,content,status,updated_at FROM memory_records WHERE id=?",
        id);
  }

  private static String validate(MemoryCategory category, String content) {
    if (category == null || content == null || content.isBlank() || content.strip().length() > 300)
      throw new IllegalArgumentException("请选择记忆类别并输入 1 至 300 字内容。");
    return content.strip();
  }
}
