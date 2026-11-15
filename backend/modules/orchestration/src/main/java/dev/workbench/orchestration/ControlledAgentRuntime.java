package dev.workbench.orchestration;

import dev.workbench.agent.AgentRuntime;
import dev.workbench.control.RunControl;
import dev.workbench.domain.EventType;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.skill.SkillRef;
import dev.workbench.task.RunRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 将单个 Agent 的调用接入项目的运行控制与持久化记录。 */
final class ControlledAgentRuntime implements AgentRuntime {
  private final String runId;
  private final String agentId;
  private final RunControl control;
  private final RunRepository repository;
  private Map<ToolKind, Integer> registeredTools;

  ControlledAgentRuntime(
      String runId, String agentId, RunControl control, RunRepository repository) {
    this.runId = Objects.requireNonNull(runId);
    this.agentId = Objects.requireNonNull(agentId);
    this.control = Objects.requireNonNull(control);
    this.repository = Objects.requireNonNull(repository);
  }

  @Override
  public void beforeModel(ModelKind model) {
    control.beginModel(agentId, model);
  }

  @Override
  public void modelResult(Integer input, Integer output) {
    control.finishModel(InvocationStatus.COMPLETED, input, output, null);
    control.checkTokenBudget();
  }

  @Override
  public void skillsLoaded(List<SkillRef> skills) {
    repository.recordAgentSkills(agentId, skills);
  }

  @Override
  public void toolsLoaded(Map<ToolKind, Integer> tools) {
    repository.recordAgentVersionedToolGrants(agentId, tools);
    registeredTools = Map.copyOf(tools);
  }

  @Override
  public UUID beforeTool(ToolKind tool, int version, String fingerprint) {
    if (registeredTools == null || !Objects.equals(registeredTools.get(tool), version))
      throw new IllegalStateException("工具版本与本次 Agent 装配记录不一致。");
    return control.beginTool(agentId, tool, version, fingerprint);
  }

  @Override
  public void check() {
    control.check();
  }

  @Override
  public void event(EventType type, String message) {
    repository.event(runId, type, message);
  }

  @Override
  public void toolResult(String result) {
    control.finishTool(InvocationStatus.COMPLETED, result, null);
    // 工具结束时框架会话未必形成一致快照，不能视为系统检查点。
  }

  @Override
  public void toolRejected(String safeMessage) {
    control.finishTool(InvocationStatus.FAILED, null, safeMessage);
  }
}
