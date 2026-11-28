package dev.workbench.framework.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * JSON 叶子级脱敏与结构保真限长器。
 *
 * <p>模型工具 Schema、工具调用参数与工具网关入参本身都是 JSON 字符串：若在生产端或落库前直接对整串跑正则或硬切字符，
 * 一个未闭合 PEM 会吞掉字段闭合引号与相邻字段，超长内容也会把 JSON 截成非法半截文本。
 *
 * <p>本工具提供两条硬保证：
 *
 * <ol>
 *   <li><b>敏感父键穿透保护</b>：一旦命中敏感字段名，无论其值是字符串、数字、数组还是嵌套对象，整个子树内的标量值一律脱敏，
 *       杜绝结构化凭据原样进入轨迹。
 *   <li><b>结构保真限长</b>：对任意合法 JSON 对象或数组，优先全体收敛长文本叶子，仍超限时在容器尾部裁剪并写入明确省略标记，
 *       极端单键超限时退化为合法 JSON 摘要信封——返回值永远是可由 Jackson 正常解析的合法 JSON 且长度严格不超过 {@code maxChars}。
 * </ol>
 */
public final class JsonLeafMasker {
  private static final String TRUNCATED_SUFFIX = "...[已截断]";
  private static final String MASKED = "***MASKED***";
  private static final String ARRAY_OMIT_PREFIX = "...[已截断 ";
  private static final String ARRAY_OMIT_SUFFIX = " 项]";
  private static final String OBJECT_TRUNCATED_KEY = "_truncated";

  private static final List<String> SENSITIVE_KEY_TOKENS =
      List.of(
          "apikey",
          "secret",
          "password",
          "passwd",
          "token",
          "authorization",
          "cookie",
          "credential",
          "privatekey");

  /** 仅豁免明确已知的模型 Token 用量/限额计数字段；复数凭据集合（如 access_tokens/refresh_tokens）一律按敏感处理。 */
  private static final java.util.Set<String> NON_SENSITIVE_TOKEN_METRIC_KEYS =
      java.util.Set.of(
          "maxtokens",
          "maxcompletiontokens",
          "maxoutputtokens",
          "maxinputtokens",
          "maxprompttokens",
          "maxreportedtokens",
          "inputtokens",
          "outputtokens",
          "prompttokens",
          "completiontokens",
          "totaltokens",
          "cachedtokens",
          "reasoningtokens",
          "estimatedtokens",
          "beforetokens",
          "savedtokens",
          "remainingtokens",
          "knowntokens",
          "tokencount",
          "tokenlimit",
          "tokenbudget",
          "segmenttokens");

  private static final Pattern FULL_MASK_PLACEHOLDER =
      Pattern.compile("^\\*\\*\\*MASKED(?:-(?:PRIVATE-KEY|TOKEN|KEY))?\\*\\*\\*$");

  private JsonLeafMasker() {}

  /**
   * 对可能为 JSON 的字符串执行敏感子树脱敏，并保证总长度严格不超过 {@code maxChars}（保底 256）。
   *
   * <p>只要输入是合法 JSON 对象或数组，输出必定仍是合法 JSON 且 {@code text.length() <= Math.max(256, maxChars)}；
   * 非 JSON 或解析失败时退回整段文本脱敏限长。
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
    } catch (com.fasterxml.jackson.core.exc.StreamConstraintsException constraintError) {
      // 超长字段名（>50,000）或超深嵌套等合法 JSON 触发 Jackson 读限制时，直接返回预算内合法摘要信封，
      // 既不退回会破坏 JSON 的普通文本硬切，也不回显任何未脱敏原始片段。
      return envelope(json, value.length(), limit);
    } catch (JsonProcessingException error) {
      return SensitiveDataMasker.maskAndTruncate(value, limit);
    }
    if (root == null || !(root.isObject() || root.isArray())) {
      return SensitiveDataMasker.maskAndTruncate(value, limit);
    }
    maskJsonLeaves(root, false);
    String serialized = serialize(json, root);
    if (serialized == null) {
      return SensitiveDataMasker.maskAndTruncate(value, limit);
    }
    if (serialized.length() <= limit) {
      return new SensitiveDataMasker.TruncatedText(serialized, false);
    }

    // 阶段 1：按统一阈值全体收敛超长文本叶子（几轮即可收敛任意宽度的文本叶子树）。
    String fitted = shrinkAllTextLeaves(json, root, limit);
    if (fitted != null && fitted.length() <= limit) {
      return new SensitiveDataMasker.TruncatedText(fitted, true);
    }

    // 阶段 2：宽对象/宽数组或大量数字等非文本节点导致骨架超限时，按容器尾部减半裁剪并保留明确省略标记。
    fitted = pruneContainersToFit(json, root, limit);
    if (fitted != null && fitted.length() <= limit) {
      return new SensitiveDataMasker.TruncatedText(fitted, true);
    }

    // 阶段 3：极端单键名本身超过 limit 时，返回固定合法 JSON 摘要信封，保证调用方永不因非法 JSON 崩毁。
    return envelope(json, serialized.length(), limit);
  }

  private static SensitiveDataMasker.TruncatedText envelope(
      ObjectMapper json, int originalChars, int limit) {
    ObjectNode envelope = json.createObjectNode();
    envelope.put("_truncated", true);
    envelope.put("_originalChars", originalChars);
    envelope.put("_summary", TRUNCATED_SUFFIX);
    String envelopeJson = serialize(json, envelope);
    if (envelopeJson != null && envelopeJson.length() <= limit) {
      return new SensitiveDataMasker.TruncatedText(envelopeJson, true);
    }
    return new SensitiveDataMasker.TruncatedText("{\"_truncated\":true}", true);
  }

  /** 对单个 JSON 对象/数组树做原地敏感子树与叶子脱敏；{@code underSensitiveKey} 向下穿透整个子树。 */
  private static void maskJsonLeaves(JsonNode node, boolean underSensitiveKey) {
    if (node instanceof ObjectNode object) {
      var names = new ArrayList<String>();
      object.fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        var child = object.get(name);
        if (child == null || child.isNull()) continue;
        boolean childSensitive = underSensitiveKey || sensitiveKey(name);
        if (child.isObject() || child.isArray()) {
          maskJsonLeaves(child, childSensitive);
          continue;
        }
        if (child.isTextual()) {
          object.put(name, childSensitive ? maskSensitiveLeaf(child.asText()) : SensitiveDataMasker.mask(child.asText()));
        } else if (childSensitive && (child.isNumber() || child.isBinary())) {
          object.put(name, MASKED);
        }
      }
      return;
    }
    if (node instanceof ArrayNode array) {
      for (int index = 0; index < array.size(); index++) {
        var child = array.get(index);
        if (child == null || child.isNull()) continue;
        if (child.isObject() || child.isArray()) {
          maskJsonLeaves(child, underSensitiveKey);
          continue;
        }
        if (child.isTextual()) {
          String safe =
              underSensitiveKey
                  ? maskSensitiveLeaf(child.asText())
                  : SensitiveDataMasker.mask(child.asText());
          array.set(index, TextNode.valueOf(safe));
        } else if (underSensitiveKey && (child.isNumber() || child.isBinary())) {
          array.set(index, TextNode.valueOf(MASKED));
        }
      }
    }
  }

  /**
   * 敏感字段或敏感子树下的文本值：先跑既有专项规则，只有整值已彻底化为完整掩码占位符（或空白）时保留专项占位符，
   * 其余（含短口令、无前缀密钥、以及「占位符前缀 + 明文后缀」混合值）一律整体替换为统一占位符。
   */
  private static String maskSensitiveLeaf(String raw) {
    if (raw == null || raw.isBlank()) return raw;
    String masked = SensitiveDataMasker.mask(raw);
    if (fullyMaskedValue(masked)) return masked;
    return MASKED;
  }

  private static boolean fullyMaskedValue(String value) {
    if (value == null) return false;
    String trimmed = value.trim();
    if ("sk-***MASKED***".equals(trimmed) || FULL_MASK_PLACEHOLDER.matcher(trimmed).matches())
      return true;
    String lower = trimmed.toLowerCase(Locale.ROOT);
    return lower.equals("bearer ***masked***") || lower.equals("basic ***masked***");
  }

  /**
   * 敏感字段名判定：仅豁免白名单内明确已知的 Token 用量计数字段，
   * 复数凭据集合（如 {@code access_tokens}/{@code refresh_tokens}）及各类密钥/口令/认证/Cookie 字段一律判定敏感。
   */
  private static boolean sensitiveKey(String fieldName) {
    if (fieldName == null || fieldName.isBlank()) return false;
    String normalized = fieldName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    if (NON_SENSITIVE_TOKEN_METRIC_KEYS.contains(normalized)) {
      return false;
    }
    for (String token : SENSITIVE_KEY_TOKENS) {
      if (normalized.contains(token)) return true;
    }
    return false;
  }

  /**
   * 阶段 1：按递减上限全体裁剪所有超长文本叶子，最多 12 轮对数收敛。
   * 相比每轮只裁单个叶子，80 个或 1,000 个长标签都能在几轮内整体压至安全水位。
   */
  private static String shrinkAllTextLeaves(ObjectMapper json, JsonNode root, int limit) {
    var leaves = new ArrayList<LeafSlot>();
    collectTextLeaves(root, leaves);
    if (leaves.isEmpty()) return null;
    int minKeep = TRUNCATED_SUFFIX.length();
    int maxLen = leaves.stream().mapToInt(slot -> slot.text().length()).max().orElse(0);
    if (maxLen <= minKeep) return null;

    // 初始上限兼顾平均配额与最长叶子减半，后续按折半快速收敛到 minKeep。
    int cap = Math.max(minKeep, Math.min(maxLen / 2, Math.max(minKeep, (limit - 64) / leaves.size())));
    for (int round = 0; round < 16; round++) {
      boolean changed = false;
      int keepPrefix = Math.max(0, cap - TRUNCATED_SUFFIX.length());
      for (LeafSlot slot : leaves) {
        String current = slot.text();
        if (current.length() <= cap) continue;
        String base = stripSuffix(current);
        int cut = Math.min(keepPrefix, base.length());
        slot.set(base.substring(0, cut) + TRUNCATED_SUFFIX);
        changed = true;
      }
      if (changed) {
        String serialized = serialize(json, root);
        if (serialized != null && serialized.length() <= limit) return serialized;
      }
      if (cap <= minKeep) break;
      cap = Math.max(minKeep, cap / 2);
    }
    String finalText = serialize(json, root);
    return finalText != null && finalText.length() <= limit ? finalText : null;
  }

  /**
   * 阶段 2：当大量字段/数组元素（含非文本数值）导致结构骨架仍超过 {@code limit} 时，
   * 反复选取当前元素数最多的容器从尾部减半裁剪，并在容器内写入明确省略标记。
   */
  private static String pruneContainersToFit(ObjectMapper json, JsonNode root, int limit) {
    Map<JsonNode, Integer> omittedCounts = new IdentityHashMap<>();
    for (int round = 0; round < 64; round++) {
      String current = serialize(json, root);
      if (current != null && current.length() <= limit) return current;
      var containers = new ArrayList<JsonNode>();
      collectContainers(root, containers);
      JsonNode target =
          containers.stream()
              .filter(container -> prunableElementCount(container, omittedCounts) > 0)
              .max(Comparator.comparingInt(container -> prunableElementCount(container, omittedCounts)))
              .orElse(null);
      if (target == null) return null;
      if (target instanceof ArrayNode array) {
        pruneArrayTail(array, omittedCounts);
      } else if (target instanceof ObjectNode object) {
        pruneObjectTail(object, omittedCounts);
      }
    }
    String finalText = serialize(json, root);
    return finalText != null && finalText.length() <= limit ? finalText : null;
  }

  private static int prunableElementCount(JsonNode container, Map<JsonNode, Integer> omittedCounts) {
    boolean hasSentinel = omittedCounts.containsKey(container);
    int total = container.size();
    return hasSentinel ? Math.max(0, total - 1) : total;
  }

  private static void pruneArrayTail(ArrayNode array, Map<JsonNode, Integer> omittedCounts) {
    int previousOmitted = omittedCounts.getOrDefault(array, 0);
    if (previousOmitted > 0 && !array.isEmpty()) {
      array.remove(array.size() - 1);
    }
    int realCount = array.size();
    if (realCount <= 0) {
      array.add(ARRAY_OMIT_PREFIX + previousOmitted + ARRAY_OMIT_SUFFIX);
      return;
    }
    int removeCount = Math.max(1, (realCount + 1) / 2);
    for (int i = 0; i < removeCount && !array.isEmpty(); i++) {
      array.remove(array.size() - 1);
    }
    int totalOmitted = previousOmitted + removeCount;
    omittedCounts.put(array, totalOmitted);
    array.add(ARRAY_OMIT_PREFIX + totalOmitted + ARRAY_OMIT_SUFFIX);
  }

  private static void pruneObjectTail(ObjectNode object, Map<JsonNode, Integer> omittedCounts) {
    int previousOmitted = omittedCounts.getOrDefault(object, 0);
    var keys = new ArrayList<String>();
    object
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (!(previousOmitted > 0 && OBJECT_TRUNCATED_KEY.equals(name))) keys.add(name);
            });
    if (keys.isEmpty()) return;
    int removeCount = Math.max(1, (keys.size() + 1) / 2);
    for (int i = keys.size() - 1; i >= keys.size() - removeCount; i--) {
      object.remove(keys.get(i));
    }
    int totalOmitted = previousOmitted + removeCount;
    omittedCounts.put(object, totalOmitted);
    object.put(OBJECT_TRUNCATED_KEY, "已省略 " + totalOmitted + " 个字段");
  }

  private static void collectContainers(JsonNode node, List<JsonNode> out) {
    if (node instanceof ObjectNode object) {
      out.add(object);
      object.elements().forEachRemaining(child -> collectContainers(child, out));
    } else if (node instanceof ArrayNode array) {
      out.add(array);
      array.elements().forEachRemaining(child -> collectContainers(child, out));
    }
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
