package dev.workbench.privateagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.privateagent.PrivateComponentModels.ComponentRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** 私有组件服务共享的校验、序列化、扩展名与摘要逻辑。 */
final class PrivateComponentSupport {
  private PrivateComponentSupport() {}

  static List<ComponentRef> readRefs(ObjectMapper json, String value) {
    try {
      return json.readValue(
          value,
          json.getTypeFactory().constructCollectionType(List.class, ComponentRef.class));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("组件引用记录无法读取。", e);
    }
  }

  static List<String> readList(ObjectMapper json, String value) {
    try {
      return json.readValue(
          value, json.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("文件扩展名记录无法读取。", e);
    }
  }

  static String write(ObjectMapper json, Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("私有组件记录无法编码。", e);
    }
  }

  static void text(String value, int max, String name) {
    if (value == null || value.isBlank() || value.strip().length() > max)
      throw new IllegalArgumentException(name + "为空或超过 " + max + " 字。");
  }

  static void optional(String value, int max, String name) {
    if (value != null && value.strip().length() > max)
      throw new IllegalArgumentException(name + "超过 " + max + " 字。");
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  static void requireEditable(String status) {
    if ("ARCHIVED".equals(status)) throw new IllegalStateException("已归档定义不能编辑或再发布。");
  }

  static String normalizeExtension(String value) {
    if (value == null) throw new IllegalArgumentException("文件扩展名为空。");
    String extension = value.strip().toLowerCase(Locale.ROOT);
    if (!extension.startsWith(".")) extension = "." + extension;
    if (!extension.matches("\\.[a-z0-9]{1,10}"))
      throw new IllegalArgumentException("文件扩展名无效：" + value);
    return extension;
  }

  static String privateToolName(String id, String executorKind) {
    String prefix = "SEARCH".equals(executorKind) ? "search_private_" : "read_private_";
    return prefix + id.replace("-", "").substring(0, 8);
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("无法计算私有组件摘要。", e);
    }
  }
}
