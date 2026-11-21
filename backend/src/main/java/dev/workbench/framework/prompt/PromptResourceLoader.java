package dev.workbench.framework.prompt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一的 Prompt 与工具契约资源加载器。
 *
 * <p>所有内置 Agent 提示词、Skill 指导文本、规划器 Prompt 与工具描述均外置在 classpath 下的
 * {@code prompts/} 目录，避免长提示词硬编码在 Java 源码中，并便于版本对比与开源学习。
 */
public final class PromptResourceLoader {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Map<String, String> TEXT_CACHE = new ConcurrentHashMap<>();
  private static volatile Map<String, ToolCopy> toolCatalog;

  private PromptResourceLoader() {}

  public record ToolCopy(String description, Map<String, String> parameters) {
    public ToolCopy {
      parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public String parameter(String name) {
      String value = parameters.get(name);
      if (value == null || value.isBlank()) {
        throw new IllegalStateException("工具参数描述缺失：" + name);
      }
      return value;
    }
  }

  /** 从 {@code prompts/<relativePath>} 读取完整 UTF-8 文本，缺失时快速失败。 */
  public static String load(String relativePath) {
    return TEXT_CACHE.computeIfAbsent(relativePath, PromptResourceLoader::readClasspathText);
  }

  /** 读取 {@code prompts/tools/tool-descriptions.json} 中指定工具键的描述与参数说明。 */
  public static ToolCopy tool(String key) {
    Map<String, ToolCopy> catalog = toolCatalog;
    if (catalog == null) {
      synchronized (PromptResourceLoader.class) {
        catalog = toolCatalog;
        if (catalog == null) {
          String raw = readClasspathText("tools/tool-descriptions.json");
          try {
            catalog = Map.copyOf(JSON.readValue(raw, new TypeReference<Map<String, ToolCopy>>() {}));
          } catch (IOException e) {
            throw new IllegalStateException("无法解析 prompts/tools/tool-descriptions.json", e);
          }
          toolCatalog = catalog;
        }
      }
    }
    ToolCopy copy = catalog.get(key);
    if (copy == null) throw new IllegalStateException("未找到工具描述配置：" + key);
    return copy;
  }

  private static String readClasspathText(String relativePath) {
    String fullPath = "prompts/" + relativePath;
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (loader == null) loader = PromptResourceLoader.class.getClassLoader();
    try (InputStream in = loader.getResourceAsStream(fullPath)) {
      if (in == null) throw new IllegalStateException("缺少 Prompt 资源文件：" + fullPath);
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      if (text.isEmpty()) throw new IllegalStateException("Prompt 资源文件为空：" + fullPath);
      return text;
    } catch (IOException e) {
      throw new IllegalStateException("读取 Prompt 资源文件失败：" + fullPath, e);
    }
  }
}
