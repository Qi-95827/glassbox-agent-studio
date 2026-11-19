package dev.workbench.orchestration;

import dev.workbench.domain.EventType;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.control.RunControl;
import dev.workbench.task.RunRecordService;
import java.util.Objects;
import java.util.UUID;

/** 将单个 Agent 的调用接入项目的运行控制与持久化记录。 */
final class ControlledAgentRuntime implements AgentRuntime {
  private final String runId;
  private final String agentId;
  private final RunControl control;
  private final RunRecordService repository;
  private final RunControl.NodePolicy nodePolicy;
  private AgentAssemblySnapshot.Components assembly;

  ControlledAgentRuntime(
      String runId, String agentId, RunControl control, RunRecordService repository) {
    this(runId, agentId, control, repository, null);
  }

  ControlledAgentRuntime(
      String runId,
      String agentId,
      RunControl control,
      RunRecordService repository,
      RunControl.NodePolicy nodePolicy) {
    this.runId = Objects.requireNonNull(runId);
    this.agentId = Objects.requireNonNull(agentId);
    this.control = Objects.requireNonNull(control);
    this.repository = Objects.requireNonNull(repository);
    this.nodePolicy = nodePolicy;
  }

  @Override
  public void beforeModel(ModelKind model) {
    if (assembly == null || ModelKind.forMode(assembly.model().mode()) != model)
      throw new IllegalStateException("模型来源与本次 Agent 装配记录不一致。");
    control.beginModel(agentId, model, nodePolicy);
  }

  @Override
  public void modelResult(Integer input, Integer output) {
    control.finishModel(InvocationStatus.COMPLETED, input, output, null);
    control.checkTokenBudget();
  }

  @Override
  public void assemblyResolved(AgentAssemblySnapshot.Components components) {
    var snapshot =
        new AgentAssemblySnapshot(
            AgentAssemblySnapshot.VERSION,
            components,
            control.budgetPolicy(components.authorization().profile(), nodePolicy));
    repository.recordAgentAssembly(runId, agentId, snapshot);
    assembly = components;
  }

  @Override
  public UUID beforeTool(ToolKind tool, int version, String fingerprint) {
    if (assembly == null || !Objects.equals(assembly.tools().get(tool), version))
      throw new IllegalStateException("工具版本与本次 Agent 装配记录不一致。");
    return control.beginTool(agentId, tool, version, fingerprint, nodePolicy);
  }

  @Override
  public void check() {
    control.check();
  }

  @Override
  public void event(EventType type, String message) {
    var ref = repository.eventRefForAgent(runId, agentId);
    // 调用标识只在服务端已创建对应记录后写入；旧调用或拒绝前事件保持空值。
    if (type == EventType.MODEL_STARTED) ref = ref.withModelCall(control.currentModelCallId());
    else if (type == EventType.MODEL_FINISHED) ref = ref.withModelCall(control.lastModelCallId());
    else if (type == EventType.TOOL_STARTED
        || type == EventType.TOOL_FINISHED
        || type == EventType.TOOL_REJECTED)
      ref = ref.withToolCall(control.currentToolCallId());
    repository.event(runId, type, message, ref);
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
