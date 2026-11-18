package dev.workbench.framework.workflow.plan;

import dev.workbench.domain.ToolKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 交接引用同时指向节点、尝试和实际实例；已接收表示已纳入执行输入。 */
public record WorkflowHandoff(
    int version,
    UUID senderAttemptId,
    UUID senderNodeId,
    UUID senderAgentId,
    UUID dependencyNodeId,
    UUID receiverAttemptId,
    UUID receiverNodeId,
    UUID receiverAgentId,
    Instant acceptedAt,
    List<ToolEvidence> toolEvidence) {
  public WorkflowHandoff {
    toolEvidence = List.copyOf(toolEvidence);
  }

  /** 已结算的只读工具引用，仅证明这次调用的执行事实。 */
  public record ToolEvidence(UUID toolCallId, ToolKind tool, int version, String resourceVersion) {
    public ToolEvidence(UUID toolCallId, ToolKind tool, int version) {
      this(toolCallId, tool, version, null);
    }
  }
}
