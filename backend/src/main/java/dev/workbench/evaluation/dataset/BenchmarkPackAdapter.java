package dev.workbench.evaluation.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalSample;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 将各公开基准的正式格式标准化为对应原生 Runner 的输入。 */
@Component
public class BenchmarkPackAdapter {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final int MAX_SAMPLES = 5_000;
  private static final int LOCOMO_MAX_QA = 50;
  public static final String AGENT_PACK_MODE = "agent_eval_v2";

  public List<EvalSample> adapt(
      String packId, EvalCategory category, String payload, String license) {
    if (payload == null || payload.isBlank()) throw new IllegalArgumentException("外部评测集文件为空。");
    var input = rows(payload);
    if (input.isEmpty() || input.size() > MAX_SAMPLES)
      throw new IllegalArgumentException("外部评测集题量超出允许范围。");
    var samples = new ArrayList<EvalSample>();
    var ids = new java.util.HashSet<String>();
    for (var row : input) {
      var node = JSON.valueToTree(row);
      switch (packId) {
        case "bfcl-v3-simple" -> adaptBfcl(packId, category, license, node, samples);
        case "ruler-niah-compact" -> adaptLongBench(packId, category, license, node, samples);
        case "longmemeval-s" -> adaptLoCoMo(packId, category, license, node, input, samples);
        case "gaia-dev-l1" -> adaptHotpot(packId, category, license, node, samples);
        case "agentdojo-security" -> adaptInjecAgent(packId, category, license, node, samples);
        case "swe-bench-lite" -> adaptSweBench(packId, category, license, node, samples);
        default -> throw new IllegalArgumentException("不支持的公开评测集。");
      }
    }
    if (samples.isEmpty()) throw new IllegalArgumentException("公开评测集没有有效题目。");
    for (var sample : samples) {
      if (sample.id().length() > 80 || !ids.add(sample.id()))
        throw new IllegalArgumentException("公开评测题标识超长或重复。");
    }
    return List.copyOf(samples);
  }

  private static void adaptBfcl(
      String pack, EvalCategory category, String license, JsonNode row, List<EvalSample> samples) {
    var turns = new ArrayList<String>();
    for (var messageGroup : array(row, "question")) {
      if (!messageGroup.isArray()) throw new IllegalArgumentException("BFCL 消息必须按轮组织。");
      for (var message : messageGroup)
        if ("user".equals(message.path("role").asText())) turns.add(required(message, "content"));
    }
    if (turns.isEmpty()) throw new IllegalArgumentException("BFCL 缺少用户问题。");
    var functions = array(row, "function");
    var names = new java.util.LinkedHashSet<String>();
    for (var function : functions) {
      if (!names.add(required(function, "name")) || !function.path("parameters").isObject())
        throw new IllegalArgumentException("BFCL 工具名称重复或缺少参数结构。");
    }
    if (names.isEmpty()) throw new IllegalArgumentException("BFCL 缺少函数定义。");
    var expected = new ArrayList<String>();
    var truth = row.path("ground_truth");
    if (truth.isArray()) {
      for (var call : truth) addToolNames(call, names, expected);
    } else if (!truth.isMissingNode() && !truth.isNull()) {
      addToolNames(truth, names, expected);
    }
    if (truth.isValueNode() && !truth.isNull() && expected.isEmpty()
        || truth.isContainerNode() && !truth.isEmpty() && expected.isEmpty())
      throw new IllegalArgumentException("BFCL ground_truth 缺少合法工具名。");
    String question = String.join("\n", turns);
    add(
        samples,
        pack,
        category,
        license,
        required(row, "id"),
        question,
        Map.of(
            "userQuestion",
            question,
            "availableFunctions",
            value(functions),
            "expectedToolNames",
            expected.isEmpty() ? List.copyOf(names) : List.copyOf(expected),
            "hasGroundTruth",
            !expected.isEmpty()));
  }

  private static void addToolNames(
      JsonNode node, java.util.Set<String> declared, List<String> result) {
    if (node.isTextual() || node.has("name") || node.has("tool")) {
      String name =
          node.isTextual() ? node.asText() : node.path("name").asText(node.path("tool").asText());
      if (!declared.contains(name)) throw new IllegalArgumentException("BFCL ground_truth 含未声明工具。");
      if (!result.contains(name)) result.add(name);
    } else if (node.isObject()) {
      node.fieldNames()
          .forEachRemaining(
              name -> {
                if (!declared.contains(name))
                  throw new IllegalArgumentException("BFCL ground_truth 含未声明工具。");
                if (!result.contains(name)) result.add(name);
              });
    }
  }

  private static void adaptLongBench(
      String pack, EvalCategory category, String license, JsonNode row, List<EvalSample> samples) {
    String answer = required(row, "answer");
    if (!answer.matches("[ABCD]")) throw new IllegalArgumentException("LongBench v2 答案必须是 A–D。");
    var setup = new LinkedHashMap<String, Object>();
    setup.put("documentContext", required(row, "context"));
    setup.put(
        "choices",
        List.of(
            required(row, "choice_A"),
            required(row, "choice_B"),
            required(row, "choice_C"),
            required(row, "choice_D")));
    setup.put("referenceAnswer", answer);
    setup.put("domain", required(row, "domain"));
    setup.put("lengthCategory", required(row, "length"));
    if (row.has("targetUtilization")) {
      double target = row.path("targetUtilization").asDouble(Double.NaN);
      if (!Double.isFinite(target) || target <= 0) throw new IllegalArgumentException("压缩水位必须大于零。");
      setup.put("targetUtilization", target);
    }
    add(samples, pack, category, license, required(row, "_id"), required(row, "question"), setup);
  }

  private static void adaptLoCoMo(
      String pack,
      EvalCategory category,
      String license,
      JsonNode row,
      List<Map<String, Object>> input,
      List<EvalSample> samples) {
    if (samples.size() >= LOCOMO_MAX_QA) return;
    String id = required(row, "sample_id");
    var memories = conversationMemories(row.path("conversation"));
    var distractors =
        input.stream()
            .map(JSON::<JsonNode>valueToTree)
            .filter(other -> !id.equals(other.path("sample_id").asText()))
            .findFirst()
            .map(
                other ->
                    conversationMemories(other.path("conversation")).stream().limit(2).toList())
            .orElseGet(List::of);
    int index = 0;
    for (var qa : array(row, "qa")) {
      index++;
      // 类别 5 含无标注答案的对抗问答，不能用于带参考答案的评分。
      if (samples.size() >= LOCOMO_MAX_QA) break;
      if (!qa.path("answer").isValueNode()
          || qa.path("answer").isNull()
          || qa.path("answer").asText().isBlank()
          || qa.path("question").asText().isBlank()) continue;
      add(
          samples,
          pack,
          category,
          license,
          id + "-" + index,
          required(qa, "question"),
          Map.of(
              "sessionMemories",
              memories,
              "distractorMemories",
              distractors,
              "referenceAnswer",
              qa.path("answer").asText(),
              "evidenceIds",
              strings(value(array(qa, "evidence"))),
              "qaCategory",
              qa.path("category").asInt()));
    }
  }

  private static List<Map<String, Object>> conversationMemories(JsonNode conversation) {
    if (!conversation.isObject()) throw new IllegalArgumentException("LoCoMo 缺少会话。");
    var result = new ArrayList<Map<String, Object>>();
    var fields = conversation.fields();
    while (fields.hasNext()) {
      var session = fields.next();
      if (!session.getKey().matches("session_[0-9]+")) continue;
      String timestamp = required(conversation, session.getKey() + "_date_time");
      if (!session.getValue().isArray()) throw new IllegalArgumentException("LoCoMo 会话结构无效。");
      for (var turn : session.getValue()) {
        String id = required(turn, "dia_id");
        String speaker = required(turn, "speaker");
        String text = turn.path("text").asText();
        String image = turn.path("blip_caption").asText();
        if (!image.isBlank()) text = text + " [Image: " + image + "]";
        if (!text.isBlank())
          result.add(Map.of("id", id, "timestamp", timestamp, "speaker", speaker, "content", text));
      }
    }
    if (result.isEmpty()) throw new IllegalArgumentException("LoCoMo 没有可用记忆。");
    return List.copyOf(result);
  }

  private static void adaptHotpot(
      String pack, EvalCategory category, String license, JsonNode row, List<EvalSample> samples) {
    var context = row.path("context");
    var passages = new ArrayList<Map<String, String>>();
    var titles = array(context, "title");
    var sentences = array(context, "sentences");
    if (titles.isEmpty() || titles.size() != sentences.size())
      throw new IllegalArgumentException("HotpotQA 标题和正文必须对应。");
    for (int i = 0; i < titles.size(); i++) {
      var lines = strings(value(sentences.get(i)));
      if (lines.isEmpty() || !titles.get(i).isTextual())
        throw new IllegalArgumentException("HotpotQA 文章缺少标题或句子。");
      passages.add(
          Map.of(
              "title",
              titles.get(i).asText(),
              "text",
              String.join(" ", lines),
              "firstSentence",
              lines.getFirst()));
    }
    var facts = row.path("supporting_facts");
    var support = strings(value(array(facts, "title"))).stream().distinct().toList();
    if (support.isEmpty()
        || !passages.stream().map(p -> p.get("title")).toList().containsAll(support))
      throw new IllegalArgumentException("HotpotQA 支持事实无法匹配文章。");
    add(
        samples,
        pack,
        category,
        license,
        required(row, "id"),
        required(row, "question"),
        Map.of(
            "passages",
            passages,
            "supportingTitles",
            support,
            "supportingFacts",
            value(facts),
            "referenceAnswer",
            required(row, "answer"),
            "questionType",
            required(row, "type"),
            "difficulty",
            required(row, "level")));
  }

  private static void adaptInjecAgent(
      String pack, EvalCategory category, String license, JsonNode row, List<EvalSample> samples) {
    String instruction = required(row, "User Instruction");
    String id = row.path("id").asText(pack + "-" + (samples.size() + 1));
    var attackerTools = strings(value(array(row, "Attacker Tools")));
    if (attackerTools.isEmpty()) throw new IllegalArgumentException("InjecAgent 缺少攻击工具标注。");
    add(
        samples,
        pack,
        category,
        license,
        id,
        instruction,
        Map.of(
            "userInstruction",
            instruction,
            "userTool",
            required(row, "User Tool"),
            "toolResponse",
            required(row, "Tool Response"),
            "attackerInstruction",
            required(row, "Attacker Instruction"),
            "attackerTools",
            attackerTools,
            "toolParameters",
            value(row.path("Tool Parameters"))));
  }

  private static void adaptSweBench(
      String pack, EvalCategory category, String license, JsonNode row, List<EvalSample> samples) {
    add(
        samples,
        pack,
        category,
        license,
        required(row, "instance_id"),
        required(row, "problem_statement"),
        Map.of("repository", required(row, "repo"), "baseCommit", required(row, "base_commit")));
  }

  private static void add(
      List<EvalSample> samples,
      String pack,
      EvalCategory category,
      String license,
      String id,
      String question,
      Map<String, Object> data) {
    var setup = new LinkedHashMap<>(data);
    setup.put("hubPackMode", AGENT_PACK_MODE);
    setup.put("hubSourcePack", pack);
    if (license != null && !license.isBlank()) setup.put("hubLicense", license);
    String title = truncate(question, 90);
    var rubric =
        setup.containsKey("referenceAnswer")
            ? List.of("Is the model answer equivalent to the reference answer?")
            : List.<String>of();
    samples.add(
        new EvalSample(
            id, category, "hub:" + pack, title, title, question, setup, List.of(), rubric));
  }

  private static JsonNode array(JsonNode row, String key) {
    var value = row.path(key);
    if (!value.isArray()) throw new IllegalArgumentException("公开评测题数组字段无效：" + key);
    return value;
  }

  private static String required(JsonNode row, String key) {
    var node = row.path(key);
    if (!node.isTextual() || node.asText().isBlank())
      throw new IllegalArgumentException("公开评测题缺少字段 " + key);
    return node.asText();
  }

  private static Object value(JsonNode node) {
    if (node.isMissingNode() || node.isNull()) return "";
    return JSON.convertValue(node, Object.class);
  }

  public List<Map<String, Object>> rows(String payload) {
    String text = payload.strip();
    try {
      if (text.startsWith("[")) {
        return maps(JSON.readValue(text, List.class));
      }
      if (text.startsWith("{")) {
        JsonNode node;
        try (com.fasterxml.jackson.databind.MappingIterator<JsonNode> records =
            JSON.readerFor(JsonNode.class).readValues(text)) {
          node = records.nextValue();
          if (records.hasNextValue()) {
            var result = new ArrayList<Map<String, Object>>();
            result.add(JSON.convertValue(node, Map.class));
            while (records.hasNextValue()) {
              result.add(JSON.convertValue(records.nextValue(), Map.class));
            }
            return List.copyOf(result);
          }
        }
        if (node.has("rows") && node.get("rows").isArray()) {
          var result = new ArrayList<Map<String, Object>>();
          for (JsonNode item : node.get("rows")) {
            JsonNode row = item.has("row") ? item.get("row") : item;
            result.add(JSON.convertValue(row, Map.class));
          }
          return List.copyOf(result);
        }
        if (node.has("data") && node.get("data").isArray()) {
          return maps(JSON.convertValue(node.get("data"), List.class));
        }
        return List.of(JSON.convertValue(node, Map.class));
      }
      var result = new ArrayList<Map<String, Object>>();
      for (String line : SampleJson.splitJsonl(payload)) {
        result.add(JSON.readValue(line, Map.class));
      }
      return List.copyOf(result);
    } catch (Exception e) {
      throw new IllegalArgumentException("外部评测集不是有效 JSON / JSONL。", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(List<?> list) {
    var result = new ArrayList<Map<String, Object>>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        var row = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet())
          if (entry.getKey() != null && entry.getValue() != null)
            row.put(String.valueOf(entry.getKey()), entry.getValue());
        result.add(Map.copyOf(row));
      }
    }
    return List.copyOf(result);
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream()
        .filter(java.util.Objects::nonNull)
        .map(String::valueOf)
        .filter(text -> !text.isBlank())
        .toList();
  }

  private static String truncate(String value, int limit) {
    if (value == null) return "";
    String text = value.strip();
    return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
  }

  /** 供离线导入日志展示；不参与评分。 */
  public static String describe(EvalSample sample) {
    return sample.id()
        + " ["
        + sample.category().name().toLowerCase(Locale.ROOT)
        + "] "
        + truncate(sample.prompt(), 80);
  }
}
