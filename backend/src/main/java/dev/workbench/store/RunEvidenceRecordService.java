package dev.workbench.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.*;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.PlanOperations;
import dev.workbench.persistence.entity.EventEntity;
import dev.workbench.persistence.repository.EventRepository;
import dev.workbench.persistence.repository.HandoffRepository;
import dev.workbench.persistence.repository.InvocationRepository;
import dev.workbench.persistence.repository.RunCheckpointRepository;
import dev.workbench.persistence.repository.RunRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 运行事件、交接、检查点读取和调用证据。 */
@Service
public class RunEvidenceRecordService {
  private final HandoffRepository handoffRows;
  private final RunCheckpointRepository checkpointRows;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final HandoffValidator handoffValidator;
  private final RunRepository runRows;
  private final EventRepository eventRows;
  private final InvocationRepository invocationRows;
  private final PlanOperations plans;
  private final TaskRecordService tasks;
  private final RunCountStore counts;

  public RunEvidenceRecordService(
      HandoffRepository handoffRows,
      RunCheckpointRepository checkpointRows,
      TransactionTemplate tx,
      ObjectMapper json,
      HandoffValidator handoffValidator,
      RunRepository runRows,
      EventRepository eventRows,
      InvocationRepository invocationRows,
      PlanOperations plans,
      TaskRecordService tasks,
      RunCountStore counts) {
    this.handoffRows = handoffRows;
    this.checkpointRows = checkpointRows;
    this.tx = tx;
    this.json = json;
    this.handoffValidator = handoffValidator;
    this.runRows = runRows;
    this.eventRows = eventRows;
    this.invocationRows = invocationRows;
    this.plans = plans;
    this.tasks = tasks;
    this.counts = counts;
  }

  /** 先验证摘要和事件引用，再转换检查点；拒绝不兼容或损坏的数据。 */
  public CheckpointSnapshot checkedCheckpoint(String id) {
    Map<String, Object> row = checkpoint(id);
    if (!validCheckpoint(row)) throw new IllegalArgumentException("检查点损坏、缺少关联事件或版本不兼容。");
    return new CheckpointSnapshot(
        UUID.fromString(row.get("ID").toString()),
        UUID.fromString(row.get("RUN_ID").toString()),
        CheckpointStage.valueOf(row.get("STAGE").toString()),
        ((Number) row.get("ROUNDS")).intValue(),
        ((Number) row.get("TOOLS")).intValue(),
        ((Number) row.get("ELAPSED_MS")).longValue(),
        (String) row.get("TOOL_RESULT"),
        ((Number) row.get("VERSION")).intValue());
  }

  public List<Map<String, Object>> events(String id) {
    return eventRows.findByRunId(id).stream().map(RunEvidenceRecordService::eventMap).toList();
  }

  private static Map<String, Object> eventMap(EventEntity event) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("ID", event.getId());
    row.put("RUN_SEQ", event.getRunSeq());
    row.put("RUN_ID", event.getRunId());
    row.put("EVENT_TYPE", event.getEventType());
    row.put("MESSAGE", event.getMessage());
    row.put("CREATED_AT", event.getCreatedAt());
    row.put("REASON_CODE", event.getReasonCode());
    row.put("PLAN_VERSION_ID", event.getPlanVersionId());
    row.put("NODE_ID", event.getNodeId());
    row.put("ATTEMPT_ID", event.getAttemptId());
    row.put("AGENT_ID", event.getAgentId());
    row.put("CHECK_ID", event.getCheckId());
    row.put("MODEL_CALL_ID", event.getModelCallId());
    row.put("TOOL_CALL_ID", event.getToolCallId());
    return row;
  }

  public void handoff(HandoffDirection direction, Handoff payload) {
    tx.executeWithoutResult(
        status -> {
          handoffValidator.validate(direction, payload);
          if (!runRows.lockForUpdate(payload.runId()))
            throw new IllegalArgumentException("交接不属于指定任务运行。");
          try {
            String serialized = json.writeValueAsString(payload);
            String id = payload.handoffId().toString();
            var existing = handoffRows.findById(id).orElse(null);
            if (existing == null) {
              handoffRows.insert(
                  id, payload.runId(), payload.agentId(), direction, serialized, RecordSupport.now());
            } else if (!direction.name().equals(existing.direction())
                || !serialized.equals(existing.json())) {
              throw new IllegalStateException("交接 ID 已存在且内容不一致。");
            }
          } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存结构化交接记录。", e);
          }
        });
  }

  public List<Map<String, Object>> handoffs(String runId) {
    return handoffRows.findByRunId(runId).stream()
        .<Map<String, Object>>map(
            row -> {
              try {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("ID", row.id());
                result.put("RUN_ID", row.runId());
                result.put("AGENT_ID", row.agentId());
                result.put("DIRECTION", row.direction());
                result.put("PAYLOAD", json.readValue(row.payload(), Handoff.class));
                result.put("CREATED_AT", row.createdAt());
                result.put("RECEIVER_AGENT_ID", row.receiverAgentId());
                result.put("RECEIVED_AT", row.receivedAt());
                return result;
              } catch (JsonProcessingException e) {
                throw new IllegalStateException("结构化交接记录无法读取。", e);
              }
            })
        .toList();
  }

  public List<Long> toolEvidence(String runId) {
    return eventRows.findIdsByType(runId, EventType.TOOL_FINISHED);
  }

  public List<Map<String, Object>> checkpoints(String id) {
    return checkpointRows.findByRunId(id).stream()
        .map(RunCheckpointRepository.CheckpointData::apiFields)
        .map(
            cp -> {
              var result = new LinkedHashMap<>(cp);
              result.put("VALID", validCheckpoint(cp));
              result.remove("TOOL_RESULT");
              result.remove("CHECKSUM");
              return (Map<String, Object>) result;
            })
        .toList();
  }

  public Map<String, Object> checkpoint(String id) {
    return checkpointRows
        .findById(id)
        .map(RunCheckpointRepository.CheckpointData::apiFields)
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  /** 检查点原因使用受控编码，便于稳定聚合且不暴露用户数据。 */
  public void checkpointSkipped(String runId, CheckpointSkipReason reason) {
    insertEvent(
        runId, EventType.CHECKPOINT_SKIPPED, reason.message(), EventRef.empty(), reason.name());
  }

  public void event(String runId, EventType type, String message) {
    event(runId, type, message, EventRef.empty());
  }

  /** 写入带结构化归属的事件；空关联与旧接口行为一致。 */
  public void event(String runId, EventType type, String message, EventRef ref) {
    insertEvent(runId, type, message, ref == null ? EventRef.empty() : ref, null);
  }

  /**
   * 事件写入与每运行序号分配在同一事务：先用 JPA 悲观锁锁定运行行，再取该运行当前最大序号。
   *
   * <p>同一运行的事件写入因此按提交顺序编号——持锁事务提交前，其他线程无法插入更晚序号。全局自增 ID 在跨运行并发时可能让晚提交事件落在旧游标之前；读取方一律使用 {@code
   * run_seq} 游标。
   */
  private void insertEvent(
      String runId, EventType type, String message, EventRef checked, String reasonCode) {
    tx.executeWithoutResult(
        status -> {
          if (!runRows.lockForUpdate(runId)) throw new IllegalArgumentException("运行不存在。");
          long next = eventRows.maxRunSeq(runId) + 1;
          eventRows.saveAndFlush(
              new EventEntity(
                  next,
                  runId,
                  type.name(),
                  message,
                  RecordSupport.now(),
                  reasonCode,
                  text(checked.planVersionId()),
                  text(checked.nodeId()),
                  text(checked.attemptId()),
                  text(checked.agentId()),
                  text(checked.checkId()),
                  text(checked.modelCallId()),
                  text(checked.toolCallId())));
        });
  }

  /**
   * 按 Agent 实例查找已持久化的节点和尝试。
   *
   * <p>只返回实际存在的归属；兼容流程或尚未认领的实例保持空值，不从角色或计划顺序推断。
   */
  public EventRef eventRefForAgent(String runId, String agentId) {
    var origin = invocationRows.findEventOrigin(runId, agentId);
    var event = EventRef.agent(UUID.fromString(agentId));
    return origin == null
        ? event
        : event
            .withPlan(nullableUuid(origin.planId()))
            .withNode(nullableUuid(origin.nodeId()))
            .withAttempt(nullableUuid(origin.attemptId()));
  }

  private static String text(UUID value) {
    return value == null ? null : value.toString();
  }

  private static UUID nullableUuid(String value) {
    return value == null || value.isBlank() ? null : UUID.fromString(value);
  }

  public String analysisReference(String runId, String agentId) {
    String result = invocationRows.findCompletedAnalysis(runId, agentId);
    if (result == null) throw new IllegalArgumentException("分析来源不存在或尚未完成。");
    return UUID.fromString(agentId) + ":" + RecordSupport.checksum(result);
  }

  /** 拒绝跨运行引用、损坏摘要和已经改变的分析结果。 */
  public AnalysisSourceData analysisSource(String runId, String reference) {
    if (reference == null || !reference.matches("[0-9a-fA-F-]{36}:[0-9a-f]{64}"))
      throw new IllegalArgumentException("分析检查点内容无效。");
    String agentId = reference.substring(0, 36);
    if (!MessageDigest.isEqual(
        analysisReference(runId, agentId).getBytes(StandardCharsets.US_ASCII),
        reference.getBytes(StandardCharsets.US_ASCII)))
      throw new IllegalArgumentException("分析来源摘要不匹配。");
    String result = invocationRows.readAgentResult(runId, agentId);
    return new AnalysisSourceData(UUID.fromString(agentId), result);
  }

  /** 解析旧版本运行引用或新版祖先引用，并核验运行链与复用节点。 */
  public CheckpointAnalysisSourceData checkpointAnalysisSource(
      String checkpointRunId, String reference) {
    if (reference == null) throw new IllegalArgumentException("分析检查点内容无效。");
    int separator = reference.indexOf('|');
    if (separator < 0) {
      var source = analysisSource(checkpointRunId, reference);
      return new CheckpointAnalysisSourceData(
          UUID.fromString(checkpointRunId), source.agentId(), source.result());
    }
    UUID sourceRun = UUID.fromString(reference.substring(0, separator));
    String originalReference = reference.substring(separator + 1);
    var current = runSnapshot(checkpointRunId);
    var currentTask = current.taskId();
    var ancestor = current.parentId();
    var visited = new HashSet<UUID>();
    visited.add(current.id());
    boolean found = false;
    while (ancestor != null) {
      if (!visited.add(ancestor) || visited.size() > 1000)
        throw new IllegalArgumentException("恢复运行链无效或过长。");
      var previous = runSnapshot(ancestor.toString());
      if (!previous.taskId().equals(currentTask))
        throw new IllegalArgumentException("恢复运行链跨越了任务边界。");
      if (previous.id().equals(sourceRun)) {
        found = true;
        break;
      }
      ancestor = previous.parentId();
    }
    if (!found) throw new IllegalArgumentException("分析来源不属于运行祖先。");
    var source = analysisSource(sourceRun.toString(), originalReference);
    var nodes = plans.find(checkpointRunId);
    boolean reused =
        nodes.stream()
            .anyMatch(
                n ->
                    n.role() == AgentRole.ANALYST
                        && n.status() == PlanNodeStatus.REUSED
                        && source.agentId().equals(n.sourceAgentId()));
    if (!reused) throw new IllegalArgumentException("运行没有记录对该分析来源的复用。");
    return new CheckpointAnalysisSourceData(sourceRun, source.agentId(), source.result());
  }

  /** 保存引用前复验祖先关系，避免凭模型输出构造来源。 */
  public String ancestorAnalysisReference(
      String checkpointRunId, String sourceRunId, UUID sourceAgentId) {
    String reference = sourceRunId + "|" + analysisReference(sourceRunId, sourceAgentId.toString());
    checkpointAnalysisSource(checkpointRunId, reference);
    return reference;
  }

  public void reuseAnalysis(String runId, UUID sourceAgentId) {
    plans.reuseAnalysis(runId, sourceAgentId.toString(), RecordSupport.now());
  }

  /** 运行计数与 token 证据读取；具体累计规则由计数存储保持。 */
  public int countAgentModelCalls(String agentId) {
    return counts.countAgentModelCalls(agentId);
  }

  public int countAgentToolCalls(String agentId) {
    return counts.countAgentToolCalls(agentId);
  }

  public int countNodeModelCalls(String runId, UUID nodeId) {
    return counts.countNodeModelCalls(runId, nodeId);
  }

  public int countNodeToolCalls(String runId, UUID nodeId) {
    return counts.countNodeToolCalls(runId, nodeId);
  }

  public int consecutiveToolAttempts(String runId, ToolKind kind, String fingerprint, int limit) {
    return counts.consecutiveToolAttempts(runId, kind, fingerprint, limit);
  }

  public dev.workbench.framework.control.RunControlStore.ReportedTokenUsage reportedTokens(
      String runId) {
    return counts.reportedTokens(runId);
  }

  public int countToolCalls(String runId, ToolKind kind) {
    return counts.countToolCalls(runId, kind);
  }

  /** 检查点 JSON 不含文件正文；格式不合法时拒绝恢复。 */
  public WorkspaceReviewCheckpoint workspaceReviewPayload(String value) {
    try {
      return json.readValue(value, WorkspaceReviewCheckpoint.class);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new IllegalArgumentException("工作区检查点内容无效。", e);
    }
  }

  public boolean validCheckpoint(Map<String, Object> cp) {
    try {
      if (!hasHeader(cp)) return false;
      int version = ((Number) cp.get("VERSION")).intValue();
      CheckpointStage stage = CheckpointStage.valueOf(cp.get("STAGE").toString());
      if (!validStage(cp, version, stage)) return false;
      if (!validCounters(cp)) return false;
      if (!validChecksum(cp, version, stage.name())) return false;
      return eventRows.exists(
          Long.parseLong(cp.get("EVENT_ID").toString()),
          cp.get("RUN_ID").toString(),
          EventType.CHECKPOINT_SAVED);
    } catch (NullPointerException
        | IllegalArgumentException
        | ClassCastException
        | org.springframework.dao.DataAccessException e) {
      // 字段缺失或编码非法属于不可恢复的快照，不应让整个列表查询失败。
      return false;
    }
  }

  private static boolean hasHeader(Map<String, Object> cp) {
    // 旧手写流程的快照没有框架上下文，不能当作 AgentScope 状态恢复。
    return "AGENTSCOPE".equals(cp.get("ENGINE")) && cp.get("CHECKSUM") != null;
  }

  private boolean validStage(Map<String, Object> cp, int version, CheckpointStage stage) {
    if (version == 3)
      return stage == CheckpointStage.BEFORE_TOOL && cp.get("TOOL_RESULT") == null;
    if (version == 4 || version == 5) {
      if (stage != CheckpointStage.BEFORE_REVIEW) return false;
      String runId = cp.get("RUN_ID").toString();
      var task = tasks.taskDefinition(runSnapshot(runId).taskId().toString());
      if (task.workspaceId() != null || task.bugId() != null) return false;
      String reference = (String) cp.get("TOOL_RESULT");
      if (reference == null || (version == 5) != reference.contains("|")) return false;
      checkpointAnalysisSource(runId, reference);
      return true;
    }
    if (version != 6) return false;
    if (stage != CheckpointStage.BEFORE_REVIEW) return false;
    String runId = cp.get("RUN_ID").toString();
    var task = tasks.taskDefinition(runSnapshot(runId).taskId().toString());
    var payload = workspaceReviewPayload((String) cp.get("TOOL_RESULT"));
    if (task.workspaceId() == null
        || task.writeEnabled()
        || task.bugId() != null
        || task.memoryEnabled()
        || !task.workspaceId().equals(payload.workspaceId().toString())) return false;
    checkpointAnalysisSource(runId, payload.analysisReference());
    return true;
  }

  private static boolean validCounters(Map<String, Object> cp) {
    return ((Number) cp.get("ROUNDS")).intValue() >= 0
        && ((Number) cp.get("TOOLS")).intValue() >= 0
        && ((Number) cp.get("ELAPSED_MS")).longValue() >= 0;
  }

  private static boolean validChecksum(Map<String, Object> cp, int version, String stage) {
    String expected =
        RecordSupport.checksum(
            cp.get("ID").toString(),
            cp.get("RUN_ID").toString(),
            String.valueOf(version),
            "AGENTSCOPE",
            stage,
            cp.get("ROUNDS").toString(),
            cp.get("TOOLS").toString(),
            cp.get("ELAPSED_MS").toString(),
            (String) cp.get("TOOL_RESULT"),
            cp.get("EVENT_ID").toString(),
            cp.get("CREATED_AT").toString());
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        cp.get("CHECKSUM").toString().getBytes(StandardCharsets.US_ASCII));
  }

  private RunSnapshot runSnapshot(String id) {
    return runRows
        .findRunById(id)
        .orElseThrow(() -> new EmptyResultDataAccessException(1))
        .snapshot();
  }

  /** 供检查点写入读取运行所属任务。 */
  String runTaskId(String runId) {
    return runSnapshot(runId).taskId().toString();
  }

  /** 已完成分析的引用与摘要；结果正文保留在原 Agent 调用中。 */
  public record AnalysisSourceData(UUID agentId, String result) {}

  /** 检查点复用的真实分析来源，不把祖先 Agent 当成本次调用。 */
  public record CheckpointAnalysisSourceData(UUID runId, UUID agentId, String result) {}
}
