package dev.workbench.framework.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次只读工具调用实际观察到的资源版本。
 *
 * <p>值按工作区相对路径保存 SHA-256，用于恢复时证明被复用的分析结果仍对应当前文件内容。编码为稳定 JSON； 不带路径的摘要不能证明具体资源，因此调用方必须提供完整映射。
 */
public final class ResourceVersion {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {};

  private ResourceVersion() {}

  /** 按路径排序编码；路径和摘要必须有效，避免把未知内容当作版本证据。 */
  public static String encode(Map<String, String> versions) {
    if (versions == null || versions.isEmpty()) throw new IllegalArgumentException("资源版本不能为空。");
    var sorted = new LinkedHashMap<String, String>();
    for (var entry : versions.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      String path = entry.getKey();
      String sha = entry.getValue();
      if (path == null
          || path.isBlank()
          || path.length() > 300
          || sha == null
          || !sha.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("资源版本路径或摘要无效。");
      sorted.put(path, sha);
    }
    try {
      return JSON.writeValueAsString(sorted);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("无法编码资源版本。", e);
    }
  }

  /** 解析并校验资源版本；非法值不能作为恢复证据。 */
  public static Map<String, String> decode(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("资源版本缺失。");
    Map<String, String> parsed;
    try {
      parsed = JSON.readValue(value, TYPE);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("资源版本格式无效。", e);
    }
    if (parsed == null || parsed.isEmpty()) throw new IllegalArgumentException("资源版本为空。");
    for (var entry : parsed.entrySet()) {
      if (entry.getKey() == null
          || entry.getKey().isBlank()
          || entry.getKey().length() > 300
          || entry.getValue() == null
          || !entry.getValue().matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("资源版本路径或摘要无效。");
    }
    return Map.copyOf(parsed);
  }
}
