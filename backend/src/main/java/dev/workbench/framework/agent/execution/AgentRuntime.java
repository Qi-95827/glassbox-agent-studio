package dev.workbench.framework.agent.execution;

import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.context.ContextBreakdown;
import dev.workbench.framework.trace.GlassboxModels;
import java.util.UUID;

/** 框架适配层向项目运行控制器报告调用，不能绕过限制。 */
public interface AgentRuntime {
  void beforeModel(ModelKind modelName);

  void modelResult(Integer inputTokens, Integer outputTokens);

  /** 记录单次模型调用的完整请求与响应透视快照（脱敏后存储）。 */
  default void modelTrace(GlassboxModels.ModelCallPayload payload) {}

  /** 四级上下文压缩已执行；记录分桶、等级与节省比例，不写消息正文。 */
  default void contextCompacted(ContextBreakdown breakdown) {}

  /** 价格版本允许的单次输出上限；没有硬费用预算时为空。 */
  default Integer maxOutputTokens() {
    return null;
  }

  /** 派发前核对输入规模是否超过价格版本的可计价上界。 */
  default void verifyModelInput(String input) {}

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

  /** 记录单次工具调用的入参、ToolGateway 安检明细与完整结果。 */
  default void toolTrace(UUID callId, GlassboxModels.ToolCallTrace trace) {}
}
