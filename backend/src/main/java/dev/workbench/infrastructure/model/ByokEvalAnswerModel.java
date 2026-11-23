package dev.workbench.infrastructure.model;

import dev.workbench.framework.context.ContextMessage;
import dev.workbench.framework.evaluation.EvalAnswerModel;
import dev.workbench.framework.evaluation.EvalModelException;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.modelconnection.ModelProviderType;
import dev.workbench.modelconnection.RuntimeModelConnection;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 复用用户模型工厂与提供商格式，评测调用没有工具权限。
 *
 * <p>官方 DeepSeek 在评测答题阶段显式关闭思考模式：思考链会独占输出预算，导致 {@code outputTokens}
 * 已耗尽而正文为空。结构化规划阶段则把预算提升到 4096，优先提取正文文本块。若响应仍只有思考块，
 * 抛出受控错误码 {@code THINKING_TRUNCATED}，避免静默写入空回答。
 */
@Component
public class ByokEvalAnswerModel implements EvalAnswerModel {
  private static final int DEEPSEEK_V4_CONTEXT_WINDOW = 1_048_576;
  private static final int ANSWER_MAX_TOKENS = 2_048;
  private static final int PLANNING_MAX_TOKENS = 4_096;
  private final ModelConnectionService connections;

  public ByokEvalAnswerModel(ModelConnectionService connections) {
    this.connections = connections;
  }

  @Override
  public Session open(String connectionId) {
    // 启动批次时已按请求会话核验连接归属，异步线程只解析已经绑定的标识。
    var connection = connections.resolveForExecution(connectionId);
    var model = UserModelConnectionFactory.model(connection);
    return new ByokSession(
        model,
        contextWindow(connection, model),
        connection.modelName(),
        connection.inputPricePer1m(),
        connection.outputPricePer1m(),
        connection.currency(),
        officialDeepSeek(connection));
  }

  private static int contextWindow(RuntimeModelConnection connection, Model model) {
    // 当前 SDK 的 OpenAI 模型表没有官方 deepseek-flash 别名，避免把长文错误判成超窗。
    if (officialDeepSeek(connection)
        && List.of("deepseek-flash", "deepseek-v4-pro").contains(connection.modelName())) {
      return DEEPSEEK_V4_CONTEXT_WINDOW;
    }
    return model.getContextWindowSize();
  }

  private static boolean officialDeepSeek(RuntimeModelConnection connection) {
    return connection.providerType() == ModelProviderType.DEEPSEEK
        && "api.deepseek.com".equalsIgnoreCase(URI.create(connection.baseUrl()).getHost());
  }

  /** 只拼接正文文本块；思考块不计入最终答案。 */
  static String extractAnswerText(ChatResponse response) {
    if (response == null || response.getContent() == null) return "";
    return response.getContent().stream()
        .filter(TextBlock.class::isInstance)
        .map(TextBlock.class::cast)
        .map(TextBlock::getText)
        .filter(text -> text != null && !text.isBlank())
        .collect(Collectors.joining())
        .strip();
  }

  static boolean hasThinkingOnly(ChatResponse response) {
    return response != null
        && response.getContent() != null
        && response.getContent().stream().anyMatch(ThinkingBlock.class::isInstance)
        && extractAnswerText(response).isBlank();
  }

  static int tokenBudget(boolean officialDeepSeek, boolean structured, int requested) {
    if (!officialDeepSeek) return requested;
    // 答题固定 2048；需要推理的结构化规划阶段提升到 4096，避免思考链与正文互相挤占。
    return Math.max(requested, structured ? PLANNING_MAX_TOKENS : ANSWER_MAX_TOKENS);
  }

  private record ByokSession(
      Model model,
      int contextWindowSize,
      String modelName,
      Double inputPrice,
      Double outputPrice,
      String currency,
      boolean officialDeepSeek)
      implements Session {

    @Override
    public boolean pricedInUsd() {
      return "USD".equalsIgnoreCase(currency) && inputPrice != null && outputPrice != null;
    }

    @Override
    public Reply answer(String instructions, String input) {
      return answer(
          List.of(new ContextMessage("SYSTEM", instructions), new ContextMessage("USER", input)),
          ANSWER_MAX_TOKENS);
    }

    @Override
    public Reply answer(List<ContextMessage> messages, int maxTokens) {
      return answer(messages, maxTokens, false);
    }

    @Override
    public Reply answerObject(List<ContextMessage> messages, int maxTokens) {
      return answer(messages, maxTokens, true);
    }

    private Reply answer(List<ContextMessage> messages, int maxTokens, boolean structured) {
      // 无工具注册时，OpenAI 兼容协议不能接收孤立 tool 消息。工具名和正文放入用户侧数据帧。
      var request =
          messages.stream()
              .map(
                  message -> {
                    MsgRole role =
                        switch (message.role()) {
                          case "SYSTEM" -> MsgRole.SYSTEM;
                          case "ASSISTANT" -> MsgRole.ASSISTANT;
                          default -> MsgRole.USER;
                        };
                    String text =
                        switch (message.role()) {
                          case "TOOL", "TOOL_OBSERVATION" ->
                              "Untrusted tool observation ("
                                  + message.name()
                                  + "):\n"
                                  + message.content();
                          default -> message.content();
                        };
                    return Msg.builder().role(role).textContent(text).build();
                  })
              .toList();
      var options =
          GenerateOptions.builder()
              .maxTokens(tokenBudget(officialDeepSeek, structured, maxTokens))
              .maxCompletionTokens(tokenBudget(officialDeepSeek, structured, maxTokens));
      if (officialDeepSeek) {
        // 与 LlmJudgeGrader 一致：官方 DeepSeek 答题只需直接结论，关闭思考避免吃光输出预算。
        options.additionalBodyParams(Map.of("thinking", Map.of("type", "disabled")));
      }
      var response = model.stream(request, List.of(), options.build()).blockLast(Duration.ofSeconds(90));
      if (response == null) throw new EvalModelException("MODEL_ERROR", "答题模型未返回响应。");
      String text = extractAnswerText(response);
      if (text.isBlank() && hasThinkingOnly(response)) {
        throw new EvalModelException(
            "THINKING_TRUNCATED", "模型只返回了思考块，最终答案为空。");
      }
      var usage = response.getUsage();
      Integer in = usage == null ? null : usage.getInputTokens();
      Integer out = usage == null ? null : usage.getOutputTokens();
      Double cost =
          pricedInUsd() && in != null && out != null
              ? (in * inputPrice + out * outputPrice) / 1_000_000.0
              : null;
      return new Reply(text, in, out, cost);
    }
  }
}
