package dev.workbench.delivery;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 任务 1 的只读执行视图投影入口。
 *
 * <p>数据全部来自已持久化的计划、节点、尝试、检查、调用和事件。这里只做归属关联与状态汇总， 不根据模板、角色、节点顺序或消息文本补造事实；旧运行缺失的字段保持空值。
 *
 * <p>图投影、操作判定和节点证据分别由包内投影器承担，本类保留公开契约并负责编排。
 */
@Service
public class ExecutionViewService {
  public static final int SCHEMA_VERSION = 1;

  private final ExecutionGraphProjector graph;
  private final ExecutionNodeDetailProjector details;

  public ExecutionViewService(
      ExecutionGraphProjector graph, ExecutionNodeDetailProjector details) {
    this.graph = graph;
    this.details = details;
  }

  public ExecutionView view(String runId) {
    return graph.view(runId);
  }

  public NodeDetail detail(String runId, String nodeIdText) {
    return details.detail(runId, nodeIdText);
  }

  public record ExecutionView(
      int schemaVersion,
      String generatedAt,
      String taskId,
      String runId,
      String parentRunId,
      String runStatus,
      String stopCause,
      String reason,
      String result,
      String createdAt,
      String updatedAt,
      long elapsedMs,
      int rounds,
      int tools,
      int taskVersion,
      PlanView plan,
      List<NodeView> nodes,
      List<EdgeView> edges,
      FocusView focus,
      List<BlockingView> blocking,
      List<ActionView> availableActions,
      RecoveryView recovery,
      SupplementView supplement,
      HandoverView handover,
      CountsView counts,
      BudgetView budget,
      RecoverySupportView recoverySupport,
      long lastEventSeq,
      String executionMode,
      List<String> breakpoints,
      List<RevisionEdgeView> revisionEdges) {
    public ExecutionView(
        int schemaVersion,
        String generatedAt,
        String taskId,
        String runId,
        String parentRunId,
        String runStatus,
        String stopCause,
        String reason,
        String result,
        String createdAt,
        String updatedAt,
        long elapsedMs,
        int rounds,
        int tools,
        int taskVersion,
        PlanView plan,
        List<NodeView> nodes,
        List<EdgeView> edges,
        FocusView focus,
        List<BlockingView> blocking,
        List<ActionView> availableActions,
        RecoveryView recovery,
        SupplementView supplement,
        HandoverView handover,
        CountsView counts,
        BudgetView budget,
        RecoverySupportView recoverySupport,
        long lastEventSeq) {
      this(
          schemaVersion,
          generatedAt,
          taskId,
          runId,
          parentRunId,
          runStatus,
          stopCause,
          reason,
          result,
          createdAt,
          updatedAt,
          elapsedMs,
          rounds,
          tools,
          taskVersion,
          plan,
          nodes,
          edges,
          focus,
          blocking,
          availableActions,
          recovery,
          supplement,
          handover,
          counts,
          budget,
          recoverySupport,
          lastEventSeq,
          "STEP_TEACHING",
          List.of(),
          List.of());
    }
  }

  /** 有界反思回边的只读视图；revisionCount 表示已触发次数。 */
  public record RevisionEdgeView(
      String reviewerNodeId, String targetNodeId, int maxRevisions, int revisionCount) {}

  public record PlanView(
      String id,
      int version,
      String templateId,
      int templateVersion,
      String scope,
      boolean reviewRequired,
      String approvedAt,
      String sourceCheckpointId,
      String graphSha256,
      int maxAttempts) {}

  public record NodeView(
      String id,
      String planVersionId,
      int sequence,
      String key,
      String label,
      String kind,
      String phase,
      String role,
      String status,
      String agentId,
      String sourceAgentId,
      String startedAt,
      String finishedAt,
      boolean workspaceRead,
      boolean workspaceWrite,
      Integer maxModelCalls,
      Integer maxToolCalls,
      String definitionId,
      Integer definitionVersion,
      String checkId,
      Integer checkVersion,
      List<String> dependencyIds,
      boolean ready,
      List<String> waitingForNodeIds,
      int attemptCount,
      int checkCount,
      String agentKind,
      String agentName,
      String agentSource,
      String agentReason,
      String agentSpecSha256,
      List<BindingComponentView> agentSkills,
      List<BindingComponentView> agentTools) {}

  public record BindingComponentView(
      String id, int version, String name, String kind, String sha256) {}

  public record EdgeView(String fromNodeId, String toNodeId) {}

  public record FocusView(List<FocusItem> active) {}

  public record FocusItem(
      String nodeId,
      String nodeLabel,
      String attemptId,
      String checkId,
      String agentId,
      String activityKind,
      String activityId,
      String activityLabel,
      String startedAt,
      String status) {}

  public record BlockingView(
      String code,
      String message,
      String scope,
      String nodeId,
      String nodeLabel,
      List<String> blockingNodeIds) {}

  public record ActionView(
      String actionCode,
      String label,
      boolean enabled,
      String disabledReason,
      String runId,
      String planVersionId,
      String checkpointId,
      String targetLabel) {}

  public record RecoveryView(
      String parentRunId,
      String sourceCheckpointId,
      List<ReusedNodeView> reusedNodes,
      InheritedView inherited,
      long newModelCalls,
      long newToolCalls) {}

  public record ReusedNodeView(String nodeId, String key, String label, String sourceAgentId) {}

  public record InheritedView(Integer rounds, Integer tools, Long elapsedMs) {}

  /** 待补充或已应用的补充请求；不存在时为空。 */
  public record SupplementView(
      String id,
      String status,
      String kind,
      String reasonCode,
      String question,
      String nodeId,
      String nodeLabel,
      String answerPath,
      String answerSha256,
      int taskVersion,
      String createdAt,
      String decidedAt) {}

  /** 人工接手记录；旧测试或审批是否失效以服务端快照为准。 */
  public record HandoverView(
      String id,
      String status,
      String baselineFingerprint,
      String currentFingerprint,
      boolean evidenceInvalidated,
      boolean revalidationRequired,
      String note,
      String takenAt,
      String returnedAt) {}

  public record BudgetView(
      Integer maxRounds,
      int usedRounds,
      Long remainingRounds,
      Integer maxTools,
      int usedTools,
      Long remainingTools,
      Long maxTokens,
      long knownTokens,
      long unknownTokenCalls,
      Long remainingTokens,
      Integer maxSeconds,
      long elapsedMs,
      Long remainingMs,
      boolean inherited) {}

  public record RecoverySupportView(boolean supported, String reason) {}

  public record CountsView(
      int total,
      long completed,
      long reused,
      long running,
      long pending,
      long failed,
      long skipped,
      int checks,
      int modelCalls,
      int toolCalls,
      int unknownTokenCalls) {}

  public record NodeDetail(
      int schemaVersion,
      String generatedAt,
      String runId,
      NodeView node,
      List<DependencyView> dependencies,
      List<AttemptView> attempts,
      List<CheckView> checks,
      List<HandoffView> inboundHandoffs,
      List<HandoffView> outboundHandoffs,
      List<CheckReceiptView> checkReceipts,
      List<NodeEventView> nodeEvents,
      String result,
      ToolFunnelView toolFunnel) {}

  /**
   * 节点工具权限漏斗：{@code granted} 是装配时真正授予的工具，{@code filtered} 是被平台裁剪掉的真实工具与原因编码。
   *
   * <p>两者都使用真实 {@link dev.workbench.domain.ToolKind} 枚举名，前端只做翻译，不再用硬编码清单反推。
   */
  public record ToolFunnelView(
      String definitionId,
      Integer definitionVersion,
      List<ToolGrantView> granted,
      List<ToolFilterView> filtered,
      Integer attemptNumber) {}

  public record ToolGrantView(String kind, Integer version) {}

  public record ToolFilterView(String kind, String reason) {}

  public record DependencyView(
      String nodeId, String label, String role, String status, String kind) {}

  public record AttemptView(
      String id,
      int number,
      String agentId,
      String status,
      String startedAt,
      String updatedAt,
      String stopCause,
      List<ModelCallView> models,
      List<ToolCallView> tools,
      String result,
      dev.workbench.framework.agent.assembly.AgentAssemblySnapshot assembly) {}

  public record ModelCallView(
      String id,
      String name,
      String status,
      Integer inputTokens,
      Integer outputTokens,
      Long durationMs,
      String createdAt,
      String updatedAt,
      String errorMessage,
      dev.workbench.framework.trace.GlassboxModels.ModelCallPayload payload) {
    public ModelCallView(
        String id,
        String name,
        String status,
        Integer inputTokens,
        Integer outputTokens,
        Long durationMs,
        String createdAt,
        String updatedAt,
        String errorMessage) {
      this(id, name, status, inputTokens, outputTokens, durationMs, createdAt, updatedAt, errorMessage, null);
    }
  }

  public record ToolCallView(
      String id,
      String name,
      Integer version,
      String status,
      Long durationMs,
      String createdAt,
      String updatedAt,
      String result,
      String errorMessage,
      String resourceVersion,
      dev.workbench.framework.trace.GlassboxModels.ToolCallTrace trace) {
    public ToolCallView(
        String id,
        String name,
        Integer version,
        String status,
        Long durationMs,
        String createdAt,
        String updatedAt,
        String result,
        String errorMessage,
        String resourceVersion) {
      this(
          id,
          name,
          version,
          status,
          durationMs,
          createdAt,
          updatedAt,
          result,
          errorMessage,
          resourceVersion,
          null);
    }
  }

  public record CheckView(
      String id,
      int attemptNo,
      String checkId,
      int checkVersion,
      String status,
      String summary,
      String evidence,
      String externalEvidenceId,
      Boolean evidenceCurrent,
      String inputsSha256,
      String startedAt,
      String finishedAt,
      List<CheckInputView> inputs) {}

  public record CheckInputView(
      String dependencyNodeId,
      String sourceKind,
      String sourceAttemptId,
      String sourceCheckId,
      String sourceRunId,
      String resultSha256,
      String sourceResult) {}

  public record CheckReceiptView(
      String checkId,
      String dependencyNodeId,
      String dependencyNodeLabel,
      String receiverNodeId,
      String receiverNodeLabel,
      String receiverAttemptId,
      String acceptedAt,
      String summary,
      String evidence) {}

  public record HandoffView(
      int version,
      String senderNodeId,
      String senderNodeLabel,
      String senderAttemptId,
      String senderAgentId,
      String receiverNodeId,
      String receiverNodeLabel,
      String receiverAttemptId,
      String receiverAgentId,
      String dependencyNodeId,
      String dependencyNodeLabel,
      String acceptedAt,
      String sourceRunId,
      String sourceResult,
      List<ToolEvidenceView> toolEvidence,
      dev.workbench.framework.trace.GlassboxModels.StructuredHandoff structured) {
    public HandoffView(
        int version,
        String senderNodeId,
        String senderNodeLabel,
        String senderAttemptId,
        String senderAgentId,
        String receiverNodeId,
        String receiverNodeLabel,
        String receiverAttemptId,
        String receiverAgentId,
        String dependencyNodeId,
        String dependencyNodeLabel,
        String acceptedAt,
        String sourceRunId,
        String sourceResult,
        List<ToolEvidenceView> toolEvidence) {
      this(
          version,
          senderNodeId,
          senderNodeLabel,
          senderAttemptId,
          senderAgentId,
          receiverNodeId,
          receiverNodeLabel,
          receiverAttemptId,
          receiverAgentId,
          dependencyNodeId,
          dependencyNodeLabel,
          acceptedAt,
          sourceRunId,
          sourceResult,
          toolEvidence,
          null);
    }
  }

  public record ToolEvidenceView(
      String toolCallId, String tool, int version, String resourceVersion) {}

  public record NodeEventView(
      long id,
      String type,
      String message,
      String occurredAt,
      String attemptId,
      String checkId,
      String modelCallId,
      String toolCallId,
      String reasonCode) {}
}
