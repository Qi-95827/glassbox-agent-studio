package dev.workbench.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.MemorySource;
import dev.workbench.domain.MemoryStatus;
import dev.workbench.framework.memory.MemoryMatch;
import dev.workbench.framework.memory.MemoryType;
import dev.workbench.persistence.entity.MemoryHistoryEntity;
import dev.workbench.persistence.entity.MemoryRecordEntity;
import dev.workbench.persistence.entity.RunMemoryLoadEntity;
import dev.workbench.persistence.entity.RunMemoryReferenceEntity;
import dev.workbench.persistence.repository.MemoryHistoryRepository;
import dev.workbench.persistence.repository.MemoryRecordRepository;
import dev.workbench.persistence.repository.RunMemoryLoadRepository;
import dev.workbench.persistence.repository.RunMemoryReferenceRepository;
import dev.workbench.workspace.WorkspaceService;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 单机本地工作区的用户确认记忆；不自动把模型输出写入长期记忆。 */
@Service
public class MemoryService {
  private final MemoryRecordRepository records;
  private final MemoryHistoryRepository versions;
  private final RunMemoryLoadRepository loads;
  private final RunMemoryReferenceRepository references;
  private final TransactionTemplate tx;
  private final WorkspaceService workspaces;
  private final ObjectMapper json;

  public MemoryService(
      MemoryRecordRepository records,
      MemoryHistoryRepository versions,
      RunMemoryLoadRepository loads,
      RunMemoryReferenceRepository references,
      TransactionTemplate tx,
      WorkspaceService workspaces,
      ObjectMapper json) {
    this.records = records;
    this.versions = versions;
    this.loads = loads;
    this.references = references;
    this.tx = tx;
    this.workspaces = workspaces;
    this.json = json;
  }

  public List<MemoryRecord> list(String workspaceId) {
    workspaces.get(workspaceId);
    return records
        .findByWorkspaceIdAndStatusOrderByUpdatedAtDescIdAsc(workspaceId, MemoryStatus.ACTIVE)
        .stream()
        .map(MemoryService::toRecord)
        .toList();
  }

  /** 面板读取：默认排除已删除，可按状态与层类型筛选。 */
  public List<MemoryRecord> list(String workspaceId, MemoryStatus status, MemoryType type) {
    workspaces.get(workspaceId);
    return records
        .findByWorkspaceIdAndStatusNotOrderByUpdatedAtDescIdAsc(workspaceId, MemoryStatus.DELETED)
        .stream()
        .filter(row -> status == null || row.getStatus() == status)
        .filter(row -> type == null || row.getMemoryType() == type)
        .map(MemoryService::toRecord)
        .toList();
  }

  /** 新任务显式开启记忆时才调用；最多取五条且重新查权威库。 */
  public List<MemoryRecord> context(String workspaceId) {
    return list(workspaceId).stream().limit(5).toList();
  }

  /** 先持久化装配引用，再返回同一批内容；不能据此断言模型已接收。 */
  public List<MemoryRecord> loadContextAndRecord(String runId, String workspaceId) {
    return tx.execute(
        status -> {
          var selected = context(workspaceId);
          loads.save(
              new RunMemoryLoadEntity(
                  runId, workspaceId, selected.size(), dev.workbench.domain.WorkbenchTime.now()));
          for (int index = 0; index < selected.size(); index++) {
            var record = selected.get(index);
            references.save(
                new RunMemoryReferenceEntity(
                    runId, index + 1, record.id().toString(), record.version(), record.category()));
          }
          return selected;
        });
  }

  /** 持久化本次混合召回的得分与理由，供装配快照和玻璃盒解释。 */
  public void recordLoad(String runId, String workspaceId, List<MemoryMatch> matches) {
    tx.executeWithoutResult(
        status -> {
          // 同一运行的多节点共享同一份召回：已有记录时直接复用，避免撞主键、虚增 hit_count 与重复事件。
          if (loads.findById(runId).isPresent()) return;
          loads.save(
              new RunMemoryLoadEntity(
                  runId, workspaceId, matches.size(), dev.workbench.domain.WorkbenchTime.now()));
          for (int index = 0; index < matches.size(); index++) {
            var match = matches.get(index);
            var row =
                records
                    .findById(match.memoryId())
                    .orElseThrow(() -> new IllegalStateException("召回记忆已不存在。"));
            references.save(
                new RunMemoryReferenceEntity(
                    runId,
                    index + 1,
                    match.memoryId(),
                    match.memoryVersion(),
                    row.getCategory(),
                    match.memoryType(),
                    match.totalScore(),
                    match.matchedReason()));
            records.recordHit(
                match.memoryId(), dev.workbench.domain.WorkbenchTime.now(), MemoryStatus.ACTIVE);
          }
        });
  }

  /** 没有装配标记时返回未记录，不能把历史数据缺失解释为已装配零条。 */
  /**
   * 读取本运行已冻结的记忆召回（含正文），供同一运行内的后续节点复用。
   *
   * <p>多节点动态工作流每个节点都会请求记忆上下文；若每次都重新召回并落库，会撞 `run_memory_loads` 主键、 虚增 `hit_count`，还会重复发
   * `MEMORY_LOADED` 事件。这里返回首次冻结的结果，找不到时返回空列表由调用方 重新召回。
   */
  public List<MemoryMatch> frozenMatches(String runId) {
    if (runId == null || runId.isBlank() || loads.findById(runId).isEmpty()) return List.of();
    var result = new java.util.ArrayList<MemoryMatch>();
    for (var reference : references.findByRunId(runId)) {
      var row = records.findById(reference.getMemoryId().toString()).orElse(null);
      if (row == null || row.getStatus() != MemoryStatus.ACTIVE) continue;
      result.add(
          new MemoryMatch(
              row.getId(),
              reference.getMemoryType(),
              reference.getScore(),
              reference.getMatchedReason(),
              row.getContent(),
              reference.getMemoryVersion()));
    }
    return List.copyOf(result);
  }

  public MemoryLoad loadForRun(String runId) {
    var load = loads.findById(runId);
    if (load.isEmpty())
      return new MemoryLoad(false, null, null, null, MemoryLoadIntegrity.NOT_RECORDED, List.of());
    var stored = load.get();
    var selected =
        references.findByRunId(runId).stream()
            .map(
                reference ->
                    new MemoryReference(
                        reference.getId().getSequenceNo(),
                        UUID.fromString(reference.getMemoryId()),
                        reference.getMemoryVersion(),
                        reference.getCategory(),
                        reference.getMemoryType(),
                        reference.getScore(),
                        reference.getMatchedReason()))
            .toList();
    boolean ordered = true;
    for (int i = 1; i <= selected.size(); i++) {
      if (selected.get(i - 1).sequence() != i) ordered = false;
    }
    boolean consistent =
        stored.getItemCount() >= 0 && stored.getItemCount() == selected.size() && ordered;
    return new MemoryLoad(
        true,
        UUID.fromString(stored.getWorkspaceId()),
        Instant.parse(stored.getLoadedAt()),
        stored.getItemCount(),
        consistent ? MemoryLoadIntegrity.CONSISTENT : MemoryLoadIntegrity.INCONSISTENT,
        selected);
  }

  /** 只读历史严格按工作区过滤；游标只取更早版本，避免并发新增导致重复。 */
  public MemoryHistoryPage history(
      String workspaceId, String memoryId, Integer beforeVersion, int limit) {
    if (limit < 1 || limit > 50 || (beforeVersion != null && beforeVersion < 1))
      throw new IllegalArgumentException("历史页大小应为 1 至 50，版本游标必须为正数。");
    workspaces.get(workspaceId);
    if (!records.existsByIdAndWorkspaceId(memoryId, workspaceId))
      throw new EmptyResultDataAccessException(1);
    var page = PageRequest.of(0, limit + 1);
    var rows =
        beforeVersion == null
            ? versions.findNewest(memoryId, page)
            : versions.findBefore(memoryId, beforeVersion, page);
    boolean hasMore = rows.size() > limit;
    var items =
        (hasMore ? rows.subList(0, limit) : rows)
            .stream().map(MemoryService::toHistoryVersion).toList();
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
          if (records.countByWorkspaceIdAndStatus(workspaceId, MemoryStatus.ACTIVE) >= 20)
            throw new IllegalStateException("每个工作区最多保存 20 条有效记忆。");
          String id = dev.workbench.domain.WorkbenchIds.newId(),
              time = dev.workbench.domain.WorkbenchTime.now();
          records.saveAndFlush(
              new MemoryRecordEntity(
                  id,
                  workspaceId,
                  category,
                  text,
                  MemorySource.USER_CONFIRMED,
                  MemoryStatus.ACTIVE,
                  1,
                  time,
                  time));
          saveHistory(id);
          return getActive(workspaceId, id);
        });
  }

  /** 运行结束提炼候选卡；同一运行的 1–3 条卡片共享 source_run_id，绝不自动生效。 */
  public MemoryRecord createCandidate(
      String workspaceId,
      MemoryCategory category,
      String content,
      String sourceRunId,
      List<String> pathPatterns,
      double confidence) {
    workspaces.get(workspaceId);
    String text = validate(category, content);
    if (sourceRunId == null || sourceRunId.isBlank())
      throw new IllegalArgumentException("候选记忆缺少来源运行。");
    return tx.execute(
        s -> {
          if (records.countByWorkspaceIdAndStatus(workspaceId, MemoryStatus.CANDIDATE) >= 50)
            throw new IllegalStateException("每个工作区最多保留 50 条候选记忆，请先采纳或忽略。");
          String id = dev.workbench.domain.WorkbenchIds.newId();
          String time = dev.workbench.domain.WorkbenchTime.now();
          records.saveAndFlush(
              new MemoryRecordEntity(
                  id,
                  workspaceId,
                  category,
                  text,
                  MemorySource.RUN_EXTRACTION,
                  MemoryStatus.CANDIDATE,
                  1,
                  MemoryType.EPISODIC,
                  sourceRunId,
                  writePaths(pathPatterns),
                  confidence,
                  0,
                  null,
                  time,
                  time));
          saveHistory(id);
          return records.findById(id).map(MemoryService::toRecord).orElseThrow();
        });
  }

  public boolean hasSourceRun(String runId) {
    return runId != null && records.existsBySourceRunId(runId);
  }

  /** 用户采纳候选；只有 ACTIVE 记忆会进入混合召回。 */
  public MemoryRecord accept(String workspaceId, String id, int version) {
    return moveStatus(workspaceId, id, version, MemoryStatus.CANDIDATE, MemoryStatus.ACTIVE);
  }

  /** 用户忽略候选；保留记录以便审计，但不再召回。 */
  public MemoryRecord ignore(String workspaceId, String id, int version) {
    return moveStatus(workspaceId, id, version, MemoryStatus.CANDIDATE, MemoryStatus.ARCHIVED);
  }

  private MemoryRecord moveStatus(
      String workspaceId, String id, int version, MemoryStatus current, MemoryStatus next) {
    return tx.execute(
        s -> {
          int changed =
              records.moveStatus(
                  id,
                  workspaceId,
                  dev.workbench.domain.WorkbenchTime.now(),
                  next,
                  current,
                  version);
          if (changed != 1) throw new IllegalStateException("记忆不存在、状态或版本已变化。");
          saveHistory(id);
          return records.findById(id).map(MemoryService::toRecord).orElseThrow();
        });
  }

  public MemoryRecord update(
      String workspaceId, String id, int version, MemoryCategory category, String content) {
    String text = validate(category, content);
    return tx.execute(
        s -> {
          int changed =
              records.updateIfActive(
                  id,
                  workspaceId,
                  category,
                  text,
                  dev.workbench.domain.WorkbenchTime.now(),
                  MemoryStatus.ACTIVE,
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
              records.moveStatus(
                  id,
                  workspaceId,
                  dev.workbench.domain.WorkbenchTime.now(),
                  MemoryStatus.DELETED,
                  MemoryStatus.ACTIVE,
                  version);
          if (changed != 1) throw new IllegalStateException("记忆不存在、已删除或版本已变化。");
          saveHistory(id);
        });
  }

  private MemoryRecord getActive(String workspaceId, String id) {
    return records
        .findByIdAndWorkspaceIdAndStatus(id, workspaceId, MemoryStatus.ACTIVE)
        .map(MemoryService::toRecord)
        .orElseThrow();
  }

  private void saveHistory(String id) {
    var row = records.findById(id).orElseThrow();
    versions.save(
        new MemoryHistoryEntity(
            row.getId(),
            row.getVersion(),
            row.getCategory(),
            row.getContent(),
            row.getStatus(),
            row.getUpdatedAt()));
  }

  private static MemoryRecord toRecord(MemoryRecordEntity row) {
    return new MemoryRecord(
        UUID.fromString(row.getId()),
        UUID.fromString(row.getWorkspaceId()),
        row.getCategory(),
        row.getContent(),
        row.getSource(),
        row.getStatus(),
        row.getVersion(),
        Instant.parse(row.getCreatedAt()),
        Instant.parse(row.getUpdatedAt()),
        row.getMemoryType(),
        row.getSourceRunId() == null ? null : UUID.fromString(row.getSourceRunId()),
        readPaths(row.getPathPatternsJson()),
        row.getConfidence(),
        row.getHitCount(),
        row.getLastHitAt() == null ? null : Instant.parse(row.getLastHitAt()));
  }

  private String writePaths(List<String> paths) {
    try {
      return json.writeValueAsString(paths == null ? List.of() : List.copyOf(paths));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("记忆路径范围无法保存。", e);
    }
  }

  private static List<String> readPaths(String value) {
    if (value == null || value.isBlank()) return List.of();
    try {
      return java.util.Arrays.asList(new ObjectMapper().readValue(value, String[].class));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return List.of();
    }
  }

  private static MemoryHistoryVersion toHistoryVersion(MemoryHistoryEntity row) {
    return new MemoryHistoryVersion(
        UUID.fromString(row.getId().getMemoryId()),
        row.getId().getVersion(),
        row.getCategory(),
        row.getContent(),
        row.getStatus(),
        Instant.parse(row.getChangedAt()));
  }

  private static String validate(MemoryCategory category, String content) {
    if (category == null || content == null || content.isBlank() || content.strip().length() > 300)
      throw new IllegalArgumentException("请选择记忆类别并输入 1 至 300 字内容。");
    return content.strip();
  }
}
