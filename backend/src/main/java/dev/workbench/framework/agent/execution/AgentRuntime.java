package dev.workbench.framework.agent.execution;

import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.skill.SkillRef;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 框架适配层向项目运行控制器报告调用，不能绕过限制。 */
public interface AgentRuntime {
  void beforeModel(ModelKind modelName);

  void modelResult(Integer inputTokens, Integer outputTokens);

  /** 记录本次解析的定义版本；不能据此宣称工具或模型已经执行。 */
  void definitionResolved(AgentDefinitionRef ref, String sha256);

  /** 记录角色实际装配的内置技能，不表示模型已遵循或任务完成。 */
  void skillsLoaded(List<SkillRef> skills);

  /** 记录成功注册的工具集合；不能借记录扩大实际授权。 */
  void toolsLoaded(Map<ToolKind, Integer> tools);

  UUID beforeTool(ToolKind tool, int version, String fingerprint);

  void check();

  void event(EventType type, String message);

  void toolResult(String result);

  /** 已启动的调用被输入校验拒绝，保留失败状态与安全错误摘要。 */
  void toolRejected(String safeMessage);
}
