package dev.workbench.framework.agent.execution;

import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.context.ContextBreakdown;
import dev.workbench.framework.tool.ToolApprovalDecision;
import dev.workbench.framework.tool.ToolRiskAssessment;
import dev.workbench.framework.trace.GlassboxModels;
import java.util.UUID;

/** 框架适配层向项目运行控制器报告调用，不能绕过限制。 */
public interface AgentRuntime {
  void beforeModel(ModelKind modelName);

  void modelResult(Integer inputTokens, Integer outputTokens);

  /**
   * 通知本次模型流式输出的结束原因（如 {@code stop}、{@code length}、{@code max_tokens}）。
   *
   * <p>必须在 {@link #modelResult(Integer, Integer)} 之前调用，使运行时在输出限长等非自然结束时
   * 启用截断凭据兜底脱敏，而不是误按自然结束跳过截断收尾。
   */
  default void modelFinishReason(String finishReason) {}

  /** 记录单次模型调用的完整请求与响应透视快照（脱敏后存储）。 */
  default void modelTrace(GlassboxModels.ModelCallPayload payload) {}

  /** 四级上下文压缩已执行；记录分桶、等级与节省比例，不写消息正文。 */
  default void contextCompacted(ContextBreakdown breakdown) {}

  /** 本轮模型调用失败或被取消：必须释放已预留的费用额度（重复调用需幂等）。 */
  default void modelFailed() {}

  /** 模型流式片段；仅用于瞬态 SSE 推送，不写入高膨胀事件表。 */
  default void modelDelta(String text) {}

  /**
   * 推理模型思考链的流式片段。
   *
   * <p>与 {@link #modelDelta(String)} 分开上报：思考链不是最终答案，混在正文里会污染
   * 打字机效果，也让「先想后答」的结构无法还原。同样的理由，它同样只走瞬态通道。
   */
  default void modelReasoningDelta(String text) {}

  /**
   * Gate 5.5：高危工具调用在 Gate 5 通过后请求用户决策。
   *
   * <p>默认拒绝自动执行高危调用；正式实现由运行控制接入持久化审批单。
   */
  default ToolApprovalDecision approvalDecision(
      String toolName, int version, String argsJson, ToolRiskAssessment risk) {
    return ToolApprovalDecision.reject("当前运行未配置动态审批通道，高危工具不会自动执行。");
  }

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

  /**
   * 单道安检门刚刚产生判定：用于运行中的横向流水线实时点亮。
   *
   * <p>同一次调用的完整判定仍由 {@link #toolTrace(UUID, GlassboxModels.ToolCallTrace)} 持久化；
   * 这里只是把同一条事实提前推给正在观看的用户，不引入第二份真相。
   */
  default void toolGateProgress(String toolName, GlassboxModels.GatewayCheck check) {}

  /**
   * 后台辅助模型调用（如 L2 上下文摘要）产生的用量。
   *
   * <p>这类调用不经过主模型调用的开始/结算配对，必须单独入口记账，否则会绕过运行费用与 Token 限制。
   */
  default void auxiliaryModelUsage(Integer inputTokens, Integer outputTokens) {}
}
