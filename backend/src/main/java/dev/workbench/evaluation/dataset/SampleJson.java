package dev.workbench.evaluation.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalSample;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSONL 行到 {@link EvalSample} 的宽松解析；仅规范化字段，不推测执行结果。 */
public final class SampleJson {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_PROMPT_CHARS = 20_000;

  private SampleJson() {}

  @SuppressWarnings("unchecked")
  public static EvalSample parseLine(
      String line, EvalCategory fallbackCategory, String sourcePack, String fallbackId) {
    if (line == null || line.isBlank())
      throw new IllegalArgumentException("评测样本行不能为空。");
    Map<String, Object> row;
    try {
      row = JSON.readValue(line, Map.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("评测样本不是有效 JSON：" + safe(line), e);
    }
    return fromMap(row, fallbackCategory, sourcePack, fallbackId);
  }

  @SuppressWarnings("unchecked")
  public static EvalSample fromMap(
      Map<String, Object> row,
      EvalCategory fallbackCategory,
      String sourcePack,
      String fallbackId) {
    if (row == null) throw new IllegalArgumentException("评测样本不能为空。");
    Object categoryValue = row.get("category");
    EvalCategory category =
        categoryValue == null || String.valueOf(categoryValue).isBlank()
            ? fallbackCategory
            : EvalCategory.parse(String.valueOf(categoryValue));
    String id = firstText(row, "id", "sampleId", "instance_id", "task_id", "question_id");
    if (id == null || id.isBlank()) id = fallbackId;
    if (id == null || id.isBlank())
      id = "sample-" + Integer.toHexString(java.util.Objects.hashCode(row));
    String prompt =
        firstText(
            row,
            "prompt",
            "question",
            "problem_statement",
            "instruction",
            "task",
            "text",
            "user_task");
    if (prompt == null || prompt.isBlank())
      throw new IllegalArgumentException("评测样本缺少 prompt/question 字段。");
    String titleZh = firstText(row, "titleZh", "title", "name");
    String titleEn = firstText(row, "titleEn", "title", "name");
    Map<String, Object> setup = map(row.get("setup"));
    if (setup.isEmpty()) setup = inferredSetup(row);
    List<Map<String, Object>> assertions = maps(row.get("assertions"));
    List<String> rubric = strings(row.get("judgeRubric"));
    return new EvalSample(
        id.strip(),
        category,
        sourcePack,
        titleZh == null ? id : titleZh,
        titleEn == null ? (titleZh == null ? id : titleZh) : titleEn,
        truncate(prompt.strip()),
        setup,
        assertions,
        rubric);
  }

  public static List<String> splitJsonl(String jsonl) {
    if (jsonl == null) return List.of();
    var lines = new ArrayList<String>();
    for (String line : jsonl.split("\\R")) {
      String text = line.strip();
      if (text.isEmpty() || text.startsWith("#") || text.startsWith("//")) continue;
      lines.add(text);
    }
    return List.copyOf(lines);
  }

  private static Map<String, Object> inferredSetup(Map<String, Object> row) {
    var setup = new LinkedHashMap<String, Object>();
    Object expected = row.get("expected");
    if (expected instanceof Map<?, ?>) setup.put("expected", expected);
    Object answer = row.get("answer");
    if (answer != null) setup.put("expectedAnswer", String.valueOf(answer));
    Object context = row.get("context");
    if (context != null) setup.put("hubContext", truncate(String.valueOf(context)));
    Object tools = row.get("function");
    if (tools == null) tools = row.get("functions");
    if (tools instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> fn) {
      Object name = fn.get("name");
      if (name == null) {
        Object inner = fn.get("function");
        if (inner instanceof Map<?, ?> innerFn) name = innerFn.get("name");
      }
      if (name != null) setup.put("expectedTool", String.valueOf(name));
    }
    // 保留少量原始字段，便于玻璃盒解释来源；大字段不复制，避免把整份外部数据集写入数据库。
    var raw = new LinkedHashMap<String, Object>();
    for (String key : List.of("instance_id", "task_id", "level", "category", "source", "license")) {
      if (row.get(key) != null) raw.put(key, row.get(key));
    }
    if (!raw.isEmpty()) setup.put("hubRecord", Map.copyOf(raw));
    return setup;
  }

  private static String firstText(Map<String, Object> row, String... keys) {
    for (String key : keys) {
      Object value = row.get(key);
      if (value instanceof String text && !text.isBlank()) return text;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?> source)) return Map.of();
    var result = new LinkedHashMap<String, Object>();
    for (var entry : source.entrySet())
      if (entry.getKey() != null && entry.getValue() != null)
        result.put(String.valueOf(entry.getKey()), entry.getValue());
    return Map.copyOf(result);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    var result = new ArrayList<Map<String, Object>>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> source) {
        var row = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet())
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

  private static String truncate(String value) {
    if (value == null) return "";
    return value.length() <= MAX_PROMPT_CHARS
        ? value
        : value.substring(0, MAX_PROMPT_CHARS - 1) + "…";
  }

  private static String safe(String value) {
    return value.length() <= 120 ? value : value.substring(0, 119) + "…";
  }
}
