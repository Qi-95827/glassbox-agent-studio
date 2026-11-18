package dev.workbench.framework.workflow.plan;

import dev.workbench.framework.workflow.check.SystemCheckRef;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一次系统检查尝试的只读视图。
 *
 * <p>检查不属于 Agent 实例，也不产生模型或工具调用；{@code inputs} 记录本次判定实际读取的前置结果版本， 而不是复制结果正文。
 */
public record WorkflowCheck(
    UUID id,
    UUID nodeId,
    String nodeKey,
    int attemptNo,
    SystemCheckRef check,
    SystemCheckStatus status,
    String summary,
    String evidence,
    String externalEvidenceId,
    /** 外部证据是否仍对应当前工作区；无外部证据时为空。 */
    Boolean evidenceCurrent,
    String inputsSha256,
    Instant startedAt,
    Instant finishedAt,
    List<Input> inputs) {
  public WorkflowCheck {
    inputs = List.copyOf(inputs);
  }

  /** 检查输入来源：Agent 尝试或另一系统检查，二者恰有一个。 */
  public record Input(
      UUID dependencyNodeId,
      PlanNodeKind sourceKind,
      UUID sourceAttemptId,
      UUID sourceCheckId,
      UUID sourceRunId,
      String resultSha256) {
    public Input {
      if ((sourceAttemptId == null) == (sourceCheckId == null))
        throw new IllegalArgumentException("检查输入必须指向一次尝试或一次检查。");
    }
  }
}
