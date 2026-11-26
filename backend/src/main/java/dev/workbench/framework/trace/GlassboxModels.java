package dev.workbench.framework.trace;

import dev.workbench.framework.context.ContextBreakdown;
import java.util.List;

/** 玻璃盒全链路透视使用的纯不可变值对象，不依赖任何持久化或 SDK 类型。 */
public final class GlassboxModels {
  private GlassboxModels() {}

  /** 发给大模型的单条消息切片。 */
  public record PromptMessage(String role, String name, String content, int charCount) {}

  /** 注入给大模型的工具 Schema 摘要。 */
  public record ToolSchemaView(String name, String description, String parametersJson) {}

  /** 模型响应中触发的工具调用决策。 */
  public record ToolCallDecision(String id, String name, String argumentsJson) {}

  /**
   * 单次模型调用的完整请求与响应透视快照。
   *
   * @param responseContent 正式输出正文（已脱敏）。
   * @param reasoningContent 推理模型的思考链（已脱敏）；模型没有思考通道时为空字符串。
   *     与正文分开保存，既能还原「先想后答」的 ReAct 结构，也避免思考链被误当成答案。
   * @param streamed 本次调用是否以增量流式方式收到。
   */
  public record ModelCallPayload(
      List<PromptMessage> requestMessages,
      List<ToolSchemaView> requestTools,
      String responseContent,
      List<ToolCallDecision> responseToolCalls,
      boolean truncated,
      ContextBreakdown context,
      String reasoningContent,
      boolean streamed) {
    public ModelCallPayload {
      requestMessages = requestMessages == null ? List.of() : List.copyOf(requestMessages);
      requestTools = requestTools == null ? List.of() : List.copyOf(requestTools);
      responseToolCalls = responseToolCalls == null ? List.of() : List.copyOf(responseToolCalls);
      reasoningContent = reasoningContent == null ? "" : reasoningContent;
    }

    /** 兼容没有上下文预算快照的历史调用。 */
    public ModelCallPayload(
        List<PromptMessage> requestMessages,
        List<ToolSchemaView> requestTools,
        String responseContent,
        List<ToolCallDecision> responseToolCalls,
        boolean truncated) {
      this(requestMessages, requestTools, responseContent, responseToolCalls, truncated, null, "", false);
    }

    /** 兼容只补上下文快照、尚未记录思考链的历史调用。 */
    public ModelCallPayload(
        List<PromptMessage> requestMessages,
        List<ToolSchemaView> requestTools,
        String responseContent,
        List<ToolCallDecision> responseToolCalls,
        boolean truncated,
        ContextBreakdown context) {
      this(requestMessages, requestTools, responseContent, responseToolCalls, truncated, context, "", false);
    }
  }

  /** ToolGateway 单道安检门或交接校验项的判定记录。 */
  public record GatewayCheck(String gate, String label, String status, String detail) {}

  /** 单次工具调用的入参、6 道安检门明细与完整返回值。 */
  public record ToolCallTrace(
      String inputArgs,
      List<GatewayCheck> gatewayChecks,
      String fullResult,
      boolean resultTruncated) {
    public ToolCallTrace {
      gatewayChecks = gatewayChecks == null ? List.of() : List.copyOf(gatewayChecks);
    }
  }

  /** 多 Agent 结构化交接信封：将上游输出拆解为结论、决策、产物、风险与平台校验清单。 */
  public record StructuredHandoff(
      String summary,
      List<String> decisions,
      List<String> artifacts,
      List<String> openRisks,
      List<GatewayCheck> validationChecks) {
    public StructuredHandoff {
      decisions = decisions == null ? List.of() : List.copyOf(decisions);
      artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
      openRisks = openRisks == null ? List.of() : List.copyOf(openRisks);
      validationChecks = validationChecks == null ? List.of() : List.copyOf(validationChecks);
    }
  }
}
