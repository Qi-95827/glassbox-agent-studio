package dev.workbench.orchestration;

import dev.workbench.domain.EventType;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.control.RunControl;
import dev.workbench.task.CostService;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.Objects;
import java.util.UUID;

/** 将单个 Agent 的调用接入项目的运行控制与持久化记录。 */
final class ControlledAgentRuntime implements AgentRuntime {
  private final String runId;
  private final String agentId;
  private final RunControl control;
  private final AgentAssemblyRecordService assemblyRecords;
  private final RunStateStore runs;
  private final RunEvidenceRecordService evidence;
  private final RunControl.NodePolicy nodePolicy;
  private final CostService costs;
  private final WorkspaceAuthorizationService authorization;
  private AgentAssemblySnapshot.Components assembly;
  private CostService.Reservation costReservation = CostService.Reservation.NONE;

  ControlledAgentRuntime(
      String runId,
      String agentId,
      RunControl control,
      AgentAssemblyRecordService assemblyRecords,
      RunStateStore runs,
      RunEvidenceRecordService evidence) {
    this(runId, agentId, control, assemblyRecords, runs, evidence, null, null, null);
  }

  ControlledAgentRuntime(
      String runId,
      String agentId,
      RunControl control,
      AgentAssemblyRecordService assemblyRecords,
      RunStateStore runs,
      RunEvidenceRecordService evidence,
      RunControl.NodePolicy nodePolicy) {
    this(runId, agentId, control, assemblyRecords, runs, evidence, nodePolicy, null, null);
  }

  ControlledAgentRuntime(
      String runId,
      String agentId,
      RunControl control,
      AgentAssemblyRecordService assemblyRecords,
      RunStateStore runs,
      RunEvidenceRecordService evidence,
      RunControl.NodePolicy nodePolicy,
      CostService costs) {
    this(runId, agentId, control, assemblyRecords, runs, evidence, nodePolicy, costs, null);
  }

  ControlledAgentRuntime(
      String runId,
      String agentId,
      RunControl control,
      AgentAssemblyRecordService assemblyRecords,
      RunStateStore runs,
      RunEvidenceRecordService evidence,
      RunControl.NodePolicy nodePolicy,
      CostService costs,
      WorkspaceAuthorizationService authorization) {
    this.runId = Objects.requireNonNull(runId);
    this.agentId = Objects.requireNonNull(agentId);
    this.control = Objects.requireNonNull(control);
    this.assemblyRecords = Objects.requireNonNull(assemblyRecords);
    this.runs = Objects.requireNonNull(runs);
    this.evidence = Objects.requireNonNull(evidence);
    this.nodePolicy = nodePolicy;
    this.costs = costs;
    this.authorization = authorization;
  }

  @Override
  public void beforeModel(ModelKind model) {
    if (assembly == null || ModelKind.forMode(assembly.model().mode()) != model)
      throw new IllegalStateException("模型来源与本次 Agent 装配记录不一致。");
    if (costs != null) costReservation = costs.reserve(runId, model);
    try {
      control.beginModel(agentId, model, nodePolicy);
    } catch (RuntimeException rejected) {
      if (costs != null) {
        costs.cancel(costReservation);
        costReservation = CostService.Reservation.NONE;
      }
      throw rejected;
    }
  }

  @Override
  public void modelResult(Integer input, Integer output) {
    control.finishModel(InvocationStatus.COMPLETED, input, output, null);
    CostService.Settlement settlement = CostService.Settlement.NONE;
    if (costs != null) {
      settlement = costs.settle(costReservation, input, output);
      costReservation = CostService.Reservation.NONE;
    }
    if (settlement.overBudgetMicros() > 0)
      throw new dev.workbench.framework.control.StopExecution(
          dev.workbench.domain.StopCause.COST_LIMIT,
          "实际费用已超过硬预算：结算 "
              + settlement.settledMicros()
              + "，上限 "
              + settlement.maxCostMicros()
              + " 微单位；超支 "
              + settlement.overBudgetMicros()
              + " 已记账。");
    if (settlement.unknown())
      throw new dev.workbench.framework.control.StopExecution(
          dev.workbench.domain.StopCause.TOKEN_USAGE_UNKNOWN,
          "供应方未报告实际 Token 用量；费用硬预算保留原预留并停止后续调用。");
    control.checkTokenBudget();
  }

  @Override
  public Integer maxOutputTokens() {
    return costReservation.outputTokenUpperBound();
  }

  @Override
  public void verifyModelInput(String input) {
    Integer bound = costReservation.inputTokenUpperBound();
    if (bound == null) return;
    int bytes = input == null ? 0 : input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    if (bytes > bound) {
      if (costs != null) costs.cancel(costReservation);
      costReservation = CostService.Reservation.NONE;
      throw new dev.workbench.framework.control.StopExecution(
          dev.workbench.domain.StopCause.COST_LIMIT,
          "模型输入可能超过价格版本的 Token 上界："
              + bytes
              + " 字节 > "
              + bound
              + "；本次调用未派发。");
    }
  }

  @Override
  public void assemblyResolved(AgentAssemblySnapshot.Components components) {
    var snapshot =
        new AgentAssemblySnapshot(
            AgentAssemblySnapshot.VERSION,
            components,
            control.budgetPolicy(components.authorization().profile(), nodePolicy));
    assemblyRecords.recordAgentAssembly(runId, agentId, snapshot);
    assembly = components;
  }

  @Override
  public UUID beforeTool(ToolKind tool, int version, String fingerprint) {
    if (assembly == null || !Objects.equals(assembly.tools().get(tool), version))
      throw new IllegalStateException("工具版本与本次 Agent 装配记录不一致。");
    if (authorization != null)
      authorization.requireCurrent(
          runs.runSnapshot(runId).taskId().toString());
    return control.beginTool(agentId, tool, version, fingerprint, nodePolicy);
  }

  @Override
  public void check() {
    control.check();
  }

  @Override
  public void event(EventType type, String message) {
    var ref = evidence.eventRefForAgent(runId, agentId);
    // 调用标识只在服务端已创建对应记录后写入；旧调用或拒绝前事件保持空值。
    if (type == EventType.MODEL_STARTED) ref = ref.withModelCall(control.currentModelCallId());
    else if (type == EventType.MODEL_FINISHED) ref = ref.withModelCall(control.lastModelCallId());
    else if (type == EventType.TOOL_STARTED
        || type == EventType.TOOL_FINISHED
        || type == EventType.TOOL_REJECTED)
      ref = ref.withToolCall(control.currentToolCallId());
    evidence.event(runId, type, message, ref);
  }

  @Override
  public void toolResult(String result) {
    toolResult(result, null);
  }

  @Override
  public void toolResult(String result, String resourceVersion) {
    control.finishTool(InvocationStatus.COMPLETED, result, null, resourceVersion);
    // 工具结束时框架会话未必形成一致快照，不能视为系统检查点。
  }

  @Override
  public void toolRejected(String safeMessage) {
    control.finishTool(InvocationStatus.FAILED, null, safeMessage);
  }
}
