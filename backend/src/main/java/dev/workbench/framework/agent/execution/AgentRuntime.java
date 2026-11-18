package dev.workbench.framework.agent.execution;

import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import java.util.UUID;

/** 框架适配层向项目运行控制器报告调用，不能绕过限制。 */
public interface AgentRuntime {
  void beforeModel(ModelKind modelName);

  void modelResult(Integer inputTokens, Integer outputTokens);

  /** 在第一次模型调用之前提交已创建实例的实际装配。 */
  void assemblyResolved(AgentAssemblySnapshot.Components components);

  UUID beforeTool(ToolKind tool, int version, String fingerprint);

  void check();

  void event(EventType type, String message);

  void toolResult(String result);

  /** 额外保存本次只读调用观察到的资源版本；旧实现可忽略。 */
  default void toolResult(String result, String resourceVersion) {
    toolResult(result);
  }

  /** 已启动的调用被输入校验拒绝，保留失败状态与安全错误摘要。 */
  void toolRejected(String safeMessage);
}
