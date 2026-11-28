package dev.workbench.task;

import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.WorkflowHandoff.ToolEvidence;
import dev.workbench.persistence.repository.WorkflowExecutionRepository;
import java.util.List;
import java.util.UUID;

/**
 * 交接输入读取：把已结算的直接依赖结果组装为调用方可见的 {@code WorkflowInput}。
 *
 * <p>Agent 结果仍从原始实例调用读取；系统检查结果从检查记录读取，不复制正文。恢复复用的 Agent 节点继续
 * 指向父运行的原始尝试。调用方必须已持有运行锁并在同一事务内使用返回值。
 */
final class WorkflowInputProvider {
  private final WorkflowExecutionRepository records;

  WorkflowInputProvider(WorkflowExecutionRepository records) {
    this.records = records;
  }

  /** 读取指定接收节点（为空表示整轮）的已结算依赖结果，按计划顺序返回。 */
  List<WorkflowExecutionService.WorkflowInput> results(String runId, UUID recipient) {
    String recipientId = text(recipient);
    var inputs = new java.util.ArrayList<SequencedInput>();
    for (var row : records.findAgentResults(runId, recipientId)) {
      var tools =
          row.workspaceRead()
              ? records.findToolEvidence(row.sourceRun(), row.agentId(), row.workspaceWrite())
              : List.<ToolEvidence>of();
      inputs.add(
          new SequencedInput(
              row.sequence(),
              new WorkflowExecutionService.WorkflowInput(
                  PlanNodeKind.AGENT,
                  row.key(),
                  UUID.fromString(row.sourceNode()),
                  UUID.fromString(row.dependencyId()),
                  UUID.fromString(row.sourceRun()),
                  row.label(),
                  UUID.fromString(row.attemptId()),
                  UUID.fromString(row.agentId()),
                  null,
                  null,
                  row.result(),
                  null,
                  tools)));
    }
    for (var row : records.findCheckResults(runId, recipientId)) {
      var dependencyId = UUID.fromString(row.dependencyId());
      inputs.add(
          new SequencedInput(
              row.sequence(),
              new WorkflowExecutionService.WorkflowInput(
                  PlanNodeKind.SYSTEM_CHECK,
                  row.key(),
                  dependencyId,
                  dependencyId,
                  UUID.fromString(row.runId()),
                  row.label(),
                  null,
                  null,
                  UUID.fromString(row.checkId()),
                  row.status(),
                  row.summary(),
                  row.evidence(),
                  List.of())));
    }
    inputs.sort(java.util.Comparator.comparingInt(SequencedInput::sequence));
    return inputs.stream().map(SequencedInput::input).toList();
  }

  /** 可空标识转文本，供查询参数使用。 */
  static String text(UUID value) {
    return value == null ? null : value.toString();
  }

  /** 排序用的中间记录：保留原始计划顺序。 */
  private record SequencedInput(int sequence, WorkflowExecutionService.WorkflowInput input) {}
}
