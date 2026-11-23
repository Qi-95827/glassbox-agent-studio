package dev.workbench.evaluation.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import dev.workbench.evaluation.dataset.BenchmarkPackAdapter;
import dev.workbench.framework.context.ContextMessage;
import dev.workbench.framework.evaluation.EvalAnswerModel;
import dev.workbench.framework.evaluation.EvalCaseExecution;
import dev.workbench.framework.evaluation.EvalExperimentConfig;
import dev.workbench.framework.evaluation.EvalSample;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** 单题模型会话、阶段计量和受控故障边界。领域流水线留在各自 Runner。 */
final class AgentEvalCase {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final long started = System.nanoTime();
  private final Supplier<EvalAnswerModel.Session> sessions;
  private EvalAnswerModel.Session session;
  private int calls;
  private int inputTokens;
  private int outputTokens;
  private double costUsd;
  private boolean usageKnown = true;
  private boolean costKnown = true;
  private String rawOutput = "";

  final Map<String, Object> metrics = new LinkedHashMap<>();
  final Map<String, Object> trace = new LinkedHashMap<>();

  AgentEvalCase(EvalAnswerModel model, EvalExperimentConfig config, String measurement) {
    sessions =
        config != null && config.usesByok() ? () -> model.open(config.modelConnectionId()) : null;
    trace.put("measurement", sessions == null ? "fixture_" + measurement : "byok_" + measurement);
    trace.put("modelMode", sessions == null ? "mock" : "byok");
  }

  static boolean isAgentPack(EvalSample sample) {
    return BenchmarkPackAdapter.AGENT_PACK_MODE.equals(sample.setup().get("hubPackMode"));
  }

  EvalCaseExecution run(Runnable pipeline) {
    try {
      pipeline.run();
    } catch (ModelFailure e) {
      metrics.put("executionFailed", true);
      metrics.put("errorCode", e.code);
      trace.put("errorCode", e.code);
    }
    metrics.put("modelRequestAttempted", calls > 0);
    metrics.put("llmCallsCount", calls);
    metrics.put("usageKnown", calls > 0 && usageKnown);
    metrics.put("costKnown", calls > 0 && costKnown);
    if (calls > 0 && usageKnown) {
      metrics.put("actualInputTokens", inputTokens);
      metrics.put("actualOutputTokens", outputTokens);
    }
    var evidence = new LinkedHashMap<>(metrics);
    evidence.put("agentGrade", true);
    return new EvalCaseExecution(
        evidence,
        metrics,
        trace,
        null,
        rawOutput,
        (System.nanoTime() - started) / 1_000_000,
        inputTokens,
        outputTokens,
        costUsd);
  }

  int modelWindow() {
    int declared = sessions == null ? 0 : model().contextWindowSize();
    return declared > 0 ? declared : 32_768;
  }

  String answer(String stage, String instructions, String input, String fixture) {
    return answer(
        stage,
        List.of(new ContextMessage("SYSTEM", instructions), new ContextMessage("USER", input)),
        fixture);
  }

  String answerObject(String stage, String instructions, String input, String fixture) {
    return answer(
        stage,
        List.of(new ContextMessage("SYSTEM", instructions), new ContextMessage("USER", input)),
        fixture,
        true);
  }

  String answer(String stage, List<ContextMessage> input, String fixture) {
    return answer(stage, input, fixture, false);
  }

  private String answer(
      String stage, List<ContextMessage> input, String fixture, boolean structured) {
    trace.put("currentStage", stage);
    if (sessions == null) {
      rawOutput = fixture;
      return rawOutput;
    }
    var selected = model();
    calls++;
    try {
      var reply = structured ? selected.answerObject(input, 2_048) : selected.answer(input, 2_048);
      if (reply == null) throw new IllegalStateException();
      rawOutput = reply.text() == null ? "" : reply.text().strip();
      if (reply.inputTokens() != null) inputTokens += reply.inputTokens();
      if (reply.outputTokens() != null) outputTokens += reply.outputTokens();
      usageKnown &= reply.inputTokens() != null && reply.outputTokens() != null;
      if (reply.costUsd() != null) costUsd += reply.costUsd();
      costKnown &= reply.costUsd() != null;
      return rawOutput;
    } catch (RuntimeException e) {
      usageKnown = false;
      costKnown = false;
      throw new ModelFailure(isTimeout(e) ? "MODEL_TIMEOUT" : "MODEL_ERROR");
    }
  }

  private static boolean isTimeout(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.util.concurrent.TimeoutException
          || cause instanceof java.net.http.HttpTimeoutException
          || cause instanceof java.net.SocketTimeoutException) return true;
    }
    return false;
  }

  private EvalAnswerModel.Session model() {
    if (session == null) {
      try {
        session = java.util.Objects.requireNonNull(sessions.get());
        trace.put("modelName", session.modelName());
      } catch (RuntimeException e) {
        throw new ModelFailure("MODEL_CONNECTION_UNAVAILABLE");
      }
    }
    return session;
  }

  void gradeAnswer(String actual, String reference) {
    rawOutput = actual;
    trace.put("modelAnswer", actual);
    trace.put("referenceAnswer", reference);
    String normalized = normalize(actual);
    String expected = normalize(reference);
    metrics.put("answerExactMatch", !actual.isBlank() && normalized.equals(expected) ? 1 : 0);
    metrics.put("answerF1", f1(normalized, expected));
  }

  static String normalize(String answer) {
    return answer
        .toLowerCase(Locale.ROOT)
        .replaceAll("[\\p{Punct}\\p{P}]", "")
        .replaceAll("\\b(a|an|the)\\b", " ")
        .strip()
        .replaceAll("\\s+", " ");
  }

  private static double f1(String actual, String reference) {
    if (actual.isEmpty() || reference.isEmpty()) return 0;
    var tokens = new HashMap<String, Integer>();
    var expected = reference.split(" ");
    var received = actual.split(" ");
    for (String token : expected) tokens.merge(token, 1, Integer::sum);
    int overlap = 0;
    for (String token : received) {
      if (tokens.getOrDefault(token, 0) <= 0) continue;
      tokens.merge(token, -1, Integer::sum);
      overlap++;
    }
    return 2.0 * overlap / (expected.length + received.length);
  }

  static JsonNode decision(String raw) {
    String value = raw.strip();
    if (value.startsWith("```") && value.endsWith("```") && value.indexOf('\n') > 0)
      value = value.substring(value.indexOf('\n') + 1, value.length() - 3);
    try {
      var node =
          JSON.reader()
              .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(value);
      return node == null ? MissingNode.getInstance() : node;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return MissingNode.getInstance();
    }
  }

  static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("评测输入无法序列化。", e);
    }
  }

  static JsonNode field(EvalSample sample, String key) {
    return JSON.valueToTree(sample.setup().get(key));
  }

  static String preview(String value, int limit) {
    if (value.length() <= limit) return value;
    int end = Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
    return value.substring(0, end) + "…";
  }

  static List<String> strings(JsonNode node) {
    if (!node.isArray()) return List.of();
    return node.valueStream().filter(JsonNode::isTextual).map(JsonNode::asText).toList();
  }

  private static final class ModelFailure extends RuntimeException {
    private final String code;

    ModelFailure(String code) {
      super(code);
      this.code = code;
    }
  }
}
