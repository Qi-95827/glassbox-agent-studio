package dev.workbench.evaluation.grader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.evaluation.EvalCaseExecution;
import dev.workbench.framework.evaluation.EvalSample;
import dev.workbench.framework.prompt.PromptResourceLoader;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.modelconnection.ModelProviderType;
import dev.workbench.modelconnection.RuntimeModelConnection;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 可选 LLM-as-a-Judge。
 *
 * <p>严格读取外置双语 Prompt，要求裁判只输出 JSON；裁判自身的 Token 与参考费用独立记录，不混入被测模型统计。 未配置裁判连接时返回 {@code
 * DISABLED}，不会用规则分数冒充大模型裁判。
 */
@Component
public class LlmJudgeGrader {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_RESPONSE_CHARS = 80_000;

  public record JudgeResult(
      String mode,
      Integer score,
      Boolean passed,
      List<Map<String, Object>> rubricChecks,
      String summary,
      String raw,
      int inputTokens,
      int outputTokens,
      double costUsd,
      String error) {
    public static JudgeResult disabled() {
      return new JudgeResult("DISABLED", null, null, List.of(), "", "", 0, 0, 0, null);
    }

    public static JudgeResult failed(String error, int inputTokens, int outputTokens, double cost) {
      return new JudgeResult(
          "BYOK", null, null, List.of(), "", "", inputTokens, outputTokens, cost, error);
    }
  }

  private final ModelConnectionService connections;

  public LlmJudgeGrader(ModelConnectionService connections) {
    this.connections = connections;
  }

  public JudgeResult judge(
      EvalSample sample,
      EvalCaseExecution execution,
      String judgeConnectionId,
      String locale,
      String referenceAnswer) {
    if (judgeConnectionId == null || judgeConnectionId.isBlank()) return JudgeResult.disabled();
    RuntimeModelConnection connection;
    try {
      connection = connections.resolveForExecution(judgeConnectionId);
    } catch (RuntimeException e) {
      return JudgeResult.failed("JUDGE_CONNECTION_UNAVAILABLE", 0, 0, 0);
    }
    String prompt = PromptResourceLoader.load(locale, "eval-judge.txt");
    String user =
        prompt
            + "\n\n## Case prompt\n"
            + sample.prompt()
            + (referenceAnswer == null ? "" : "\n\n## Reference answer\n" + referenceAnswer)
            + "\n\n## Declared rubric\n"
            + String.join("\n", sample.judgeRubric())
            + "\n\n## Model output\n"
            + truncate(execution == null ? "" : execution.rawOutput())
            + "\n\n## Deterministic assertion evidence\n"
            + safeJson(execution == null ? Map.of() : execution.metrics());
    try {
      var endpoint = URI.create(connection.baseUrl() + "/chat/completions");
      var payload = new LinkedHashMap<String, Object>();
      payload.put("model", connection.modelName());
      payload.put(
          "messages",
          List.of(
              Map.of("role", "system", "content", prompt),
              Map.of("role", "user", "content", user)));
      payload.put("temperature", 0);
      payload.put("max_tokens", referenceAnswer == null ? 900 : 2_048);
      payload.put("stream", false);
      if (referenceAnswer != null
          && connection.providerType() == ModelProviderType.DEEPSEEK
          && "api.deepseek.com".equalsIgnoreCase(endpoint.getHost())) {
        // 公开问答裁判只需短 JSON 结论，避免默认思考用量挤占最终判词。
        payload.put("thinking", Map.of("type", "disabled"));
      }
      var body = JSON.writeValueAsBytes(payload);
      var client = dev.workbench.framework.outbound.OutboundHttpClients.shared();
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(90))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + connection.apiKey())
              .POST(HttpRequest.BodyPublishers.ofByteArray(body))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2)
        return JudgeResult.failed("裁判服务返回 HTTP " + response.statusCode(), 0, 0, 0);
      var root = JSON.readTree(response.body());
      var choice = root.path("choices").path(0);
      String content = choice.path("message").path("content").asText("");
      int input = root.path("usage").path("prompt_tokens").asInt(0);
      int output = root.path("usage").path("completion_tokens").asInt(0);
      double cost = cost(connection, input, output);
      if ("length".equals(choice.path("finish_reason").asText()))
        return JudgeResult.failed("裁判输出达到 Token 上限，未获得完整判词。", input, output, cost);
      return parseVerdict(content, input, output, cost);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return JudgeResult.failed("裁判调用被中断。", 0, 0, 0);
    } catch (Exception e) {
      return JudgeResult.failed("JUDGE_MODEL_ERROR", 0, 0, 0);
    }
  }

  /** 容忍代码围栏，但仍强制校验 score / passed / rubricChecks 结构。 */
  static JudgeResult parseVerdict(String content, int inputTokens, int outputTokens, double cost) {
    String text = content == null ? "" : content.strip();
    if (text.startsWith("```")) {
      int firstNewline = text.indexOf('\n');
      int lastFence = text.lastIndexOf("```");
      if (firstNewline > 0 && lastFence > firstNewline)
        text = text.substring(firstNewline + 1, lastFence).strip();
    }
    try {
      JsonNode node = JSON.readTree(text);
      int score = node.path("score").asInt(-1);
      if (score < 1 || score > 5)
        return JudgeResult.failed("裁判返回的 score 不在 1..5。", inputTokens, outputTokens, cost);
      boolean passed = node.path("passed").asBoolean(false);
      var checks = new ArrayList<Map<String, Object>>();
      for (JsonNode item : node.path("rubricChecks")) {
        var row = new LinkedHashMap<String, Object>();
        row.put("criterion", item.path("criterion").asText(""));
        row.put("passed", item.path("passed").asBoolean(false));
        row.put("reason", item.path("reason").asText(""));
        checks.add(Map.copyOf(row));
      }
      return new JudgeResult(
          "BYOK",
          score,
          passed,
          List.copyOf(checks),
          node.path("summary").asText(""),
          truncate(text),
          inputTokens,
          outputTokens,
          cost,
          null);
    } catch (Exception e) {
      return JudgeResult.failed("裁判输出不是严格 JSON：" + truncate(text), inputTokens, outputTokens, cost);
    }
  }

  private static double cost(RuntimeModelConnection connection, int input, int output) {
    double inputPrice = connection.inputPricePer1m() == null ? 0 : connection.inputPricePer1m();
    double outputPrice = connection.outputPricePer1m() == null ? 0 : connection.outputPricePer1m();
    return (input * inputPrice + output * outputPrice) / 1_000_000.0;
  }

  private static String safeJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      return "{}";
    }
  }

  private static String truncate(String value) {
    if (value == null) return "";
    return value.length() <= MAX_RESPONSE_CHARS
        ? value
        : value.substring(0, MAX_RESPONSE_CHARS - 1) + "…";
  }

  /** 命中语言前缀时切换 Prompt，避免裁判用错语言解释扣分理由。 */
  public static String normalizeLocale(String locale) {
    return locale == null
        ? PromptResourceLoader.ZH_CN
        : locale.strip().toLowerCase(Locale.ROOT).startsWith("en")
            ? PromptResourceLoader.EN_US
            : PromptResourceLoader.ZH_CN;
  }
}
