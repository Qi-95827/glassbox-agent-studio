package dev.workbench.framework.workflow.check;

import dev.workbench.domain.ToolKind;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import java.util.List;
import java.util.UUID;

/**
 * 检查执行器可读取的一条前置输入。
 *
 * <p>正文不复制到检查表；{@code result} 是认领事务中重新读取的已结算结果，只在此次执行内使用。来源尝试 标识对应 Agent 前置，来源检查标识对应检查前置，二者恰有一个非空。
 */
public record SystemCheckInput(
    UUID nodeId,
    String key,
    String label,
    PlanNodeKind kind,
    UUID sourceAttemptId,
    UUID sourceCheckId,
    boolean settled,
    SystemCheckStatus checkStatus,
    String result,
    List<WorkflowHandoff.ToolEvidence> toolEvidence) {
  public SystemCheckInput {
    if (nodeId == null || label == null || kind == null || result == null)
      throw new IllegalArgumentException("检查输入缺少节点、名称、类型或结果。");
    if ((sourceAttemptId == null) == (sourceCheckId == null))
      throw new IllegalArgumentException("检查输入必须且只能指向一次 Agent 尝试或一次检查。");
    if (checkStatus != null && checkStatus == SystemCheckStatus.RUNNING)
      throw new IllegalArgumentException("未结算的检查不能作为前置输入。");
    toolEvidence =
        toolEvidence == null
            ? List.of()
            : toolEvidence.stream()
                .filter(evidence -> evidence.tool() != ToolKind.WORKSPACE_WRITE)
                .toList();
  }

  /** 是否提供了至少一条授权文件读取或搜索的实际调用证据。 */
  public boolean hasReadEvidence() {
    return !toolEvidence.isEmpty();
  }
}
