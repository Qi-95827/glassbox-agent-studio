package dev.workbench.framework.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * JSON 叶子级脱敏与结构保真限长器。
 *
 * <p>模型工具 Schema、工具调用参数与工具网关入参本身都是 JSON 字符串：若在生产端或落库前直接对整串跑正则或硬切字符，
 * 一个未闭合 PEM 会吞掉字段闭合引号与相邻字段，超长内容也会把 JSON 截成非法半截文本。本工具统一先解析成 JSON 树、
 * 逐叶子脱敏、必要时按叶子收缩体积，再序列化回合法 JSON；非 JSON 或解析失败时退回整段脱敏限长。
 */
public final class JsonLeafMasker {
  private static final String TRUNCATED_SUFFIX = "...[已截断]";
  private static final Pattern SENSITIVE_KEY =
      Pattern.compile("(?i).*(?:api[_-]?key|secret|password|passwd|token|authorization|cookie).*");
  private static final Pattern FULL_MASK_PLACEHOLDER =
      Pattern.compile("^\\*\\*\\*MASKED(?:-(?:PRIVATE-KEY|TOKEN|KEY))?\\*\\*\\*$");

  private JsonLeafMasker() {}

  /**
   * 对可能为 JSON 的字符串执行叶子级脱敏并保证总长度不超过 {@code maxChars}（保底 256）。
   *
   * <p>只要输入是合法 JSON 对象或数组，脱敏与必要时的叶子裁剪均在树内完成，返回结果保持可由 Jackson 正常解析的合法 JSON
   * （仅当纯键名/结构骨架本身超出上限时，退回整段安全截断）。
   */
  public static SensitiveDataMasker.TruncatedText maskAndTruncate(
      ObjectMapper json, String value, int maxChars) {
    if (value == null || value.isBlank()) {
      return new SensitiveDataMasker.TruncatedText(value, false);
    }
    int limit = Math.max(256, maxChars);
    String trimmed = value.trim();
    if (json == null || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
      return SensitiveDataMasker.maskAndTruncate(value, limit);
    }
    JsonNode root;
    try {
      root = json.readTree(value);
    } catch (JsonProcessingException error) {
      return SensitiveDataMasker.maskAndTruncate(value, limit);
    }
    if (root == null || !(root.isObject() || root.isArray())) {
      return SensitiveDataMasker.maskAndTruncate(value, limit);
    }
    maskJsonLeaves(root);
    String serialized = serialize(json, root);
    if (serialized == null) {
      return SensitiveDataMasker.maskAndTruncate(value, limit);
    }
    if (serialized.length() <= limit) {
      return new SensitiveDataMasker.TruncatedText(serialized, false);
    }
    boolean shrunk = shrinkLeavesToFit(json, root, limit);
    if (shrunk) {
      String fitted = serialize(json, root);
      if (fitted != null && fitted.length() <= limit) {
        return new SensitiveDataMasker.TruncatedText(fitted, true);
      }
    }
    // 仅当键名骨架本身超过 limit、即使所有叶子裁到最小也放不下时，退回已脱敏文本截断。
    String fallback = serialized.substring(0, Math.min(limit, serialized.length())) + "\n...[已截断]";
    return new SensitiveDataMasker.TruncatedText(fallback, true);
  }

  /** 对单个 JSON 对象/数组树做原地叶子脱敏。 */
  private static void maskJsonLeaves(JsonNode node) {
    if (node instanceof ObjectNode object) {
      var names = new ArrayList<String>();
      object.fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        var child = object.get(name);
        if (child == null) continue;
        if (child.isObject() || child.isArray()) {
          maskJsonLeaves(child);
          continue;
        }
        if (!child.isTextual()) continue;
        object.put(name, maskLeafText(name, child.asText()));
      }
      return;
    }
    if (node instanceof ArrayNode array) {
      for (int index = 0; index < array.size(); index++) {
        var child = array.get(index);
        if (child == null) continue;
        if (child.isObject() || child.isArray()) {
          maskJsonLeaves(child);
          continue;
        }
        if (child.isTextual()) {
          array.set(index, TextNode.valueOf(SensitiveDataMasker.mask(child.asText())));
        }
      }
    }
  }

  /**
   * 敏感键名下的叶子值：先按既有规则脱敏；只有整值已彻底化为完整掩码占位符（或短于 6 字符）时才保留专项占位符，
   * 否则（含无前缀口令、以及「专项掩码/占位符前缀 + 明文后缀」的混合值）一律整体替换为统一占位符，防止旁路泄露。
   */
  static String maskLeafText(String fieldName, String raw) {
    String masked = SensitiveDataMasker.mask(raw);
    if (!sensitiveKey(fieldName)) return masked;
    if (raw.length() < 6 || fullyMaskedValue(masked)) return masked;
    return "***MASKED***";
  }

  private static boolean fullyMaskedValue(String value) {
    if (value == null) return false;
    String trimmed = value.trim();
    if ("sk-***MASKED***".equals(trimmed) || FULL_MASK_PLACEHOLDER.matcher(trimmed).matches())
      return true;
    String lower = trimmed.toLowerCase(Locale.ROOT);
    return lower.equals("bearer ***masked***") || lower.equals("basic ***masked***");
  }

  private static boolean sensitiveKey(String fieldName) {
    return fieldName != null && SENSITIVE_KEY.matcher(fieldName).matches();
  }

  /**
   * 优先裁剪最长的文本叶子，使整棵 JSON 树序列化后严格落在 {@code limit} 内且维持合法 JSON 结构。
   */
  private static boolean shrinkLeavesToFit(ObjectMapper json, JsonNode root, int limit) {
    var leaves = new ArrayList<LeafSlot>();
    collectTextLeaves(root, leaves);
    if (leaves.isEmpty()) return false;
    leaves.sort(Comparator.comparingInt((LeafSlot slot) -> slot.text().length()).reversed());
    boolean anyTruncated = false;
    // 最多做若干轮收敛，每轮压减当前最长的若干文本叶子，同时保留短字段不被误伤。
    for (int round = 0; round < 24; round++) {
      String current = serialize(json, root);
      if (current == null) return false;
      int excess = current.length() - limit;
      if (excess <= 0) return anyTruncated;
      leaves.sort(Comparator.comparingInt((LeafSlot slot) -> slot.text().length()).reversed());
      LeafSlot longest = leaves.getFirst();
      int len = longest.text().length();
      int minKeep = TRUNCATED_SUFFIX.length();
      if (len <= minKeep) return false;
      // 考虑 JSON 转义开销，按超额量或折半激进压减最长叶子。
      int nextLen = Math.max(minKeep, Math.min(len - excess - 16, len / 2));
      int keepPrefix = Math.max(0, nextLen - TRUNCATED_SUFFIX.length());
      String base = stripSuffix(longest.text());
      int cut = Math.min(keepPrefix, base.length());
      longest.set(base.substring(0, cut) + TRUNCATED_SUFFIX);
      anyTruncated = true;
    }
    String finalText = serialize(json, root);
    return anyTruncated && finalText != null && finalText.length() <= limit;
  }

  private static String stripSuffix(String text) {
    return text.endsWith(TRUNCATED_SUFFIX)
        ? text.substring(0, text.length() - TRUNCATED_SUFFIX.length())
        : text;
  }

  private static void collectTextLeaves(JsonNode node, List<LeafSlot> out) {
    if (node instanceof ObjectNode object) {
      var names = new ArrayList<String>();
      object.fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        var child = object.get(name);
        if (child == null) continue;
        if (child.isObject() || child.isArray()) {
          collectTextLeaves(child, out);
        } else if (child.isTextual()) {
          out.add(new ObjectLeaf(object, name));
        }
      }
      return;
    }
    if (node instanceof ArrayNode array) {
      for (int index = 0; index < array.size(); index++) {
        var child = array.get(index);
        if (child == null) continue;
        if (child.isObject() || child.isArray()) {
          collectTextLeaves(child, out);
        } else if (child.isTextual()) {
          out.add(new ArrayLeaf(array, index));
        }
      }
    }
  }

  private static String serialize(ObjectMapper json, JsonNode root) {
    try {
      return json.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private sealed interface LeafSlot permits ObjectLeaf, ArrayLeaf {
    String text();

    void set(String value);
  }

  private record ObjectLeaf(ObjectNode owner, String field) implements LeafSlot {
    @Override
    public String text() {
      var node = owner.get(field);
      return node == null ? "" : node.asText("");
    }

    @Override
    public void set(String value) {
      owner.put(field, value);
    }
  }

  private record ArrayLeaf(ArrayNode owner, int index) implements LeafSlot {
    @Override
    public String text() {
      var node = owner.get(index);
      return node == null ? "" : node.asText("");
    }

    @Override
    public void set(String value) {
      owner.set(index, TextNode.valueOf(value));
    }
  }
}
