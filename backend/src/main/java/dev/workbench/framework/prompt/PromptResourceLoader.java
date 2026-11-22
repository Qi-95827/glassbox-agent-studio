package dev.workbench.framework.prompt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一的 Prompt 与工具契约资源加载器。
 *
 * <p>所有内置 Agent 提示词、Skill 指导文本、规划器 Prompt 与工具描述均外置在 classpath 下的
 * {@code prompts/} 目录，避免长提示词硬编码在 Java 源码中，并便于版本对比与开源学习。
 *
 * <p>英文资源镜像位于 {@code prompts/en/}。加载时优先命中当前语言；缺失时回退中文资源，
 * 保证历史任务与新增语言资源不会因为个别文件缺译而运行失败。
 */
public final class PromptResourceLoader {
  public static final String ZH_CN = "zh-CN";
  public static final String EN_US = "en-US";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Map<String, String> TEXT_CACHE = new ConcurrentHashMap<>();
  private static final Map<String, Map<String, ToolCopy>> TOOL_CATALOGS =
      new ConcurrentHashMap<>();

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

  /** 语言标识规范化：en* 统一为 en-US，其余统一回退 zh-CN。 */
  public static String normalizeLocale(String locale) {
    if (locale == null || locale.isBlank()) return ZH_CN;
    String text = locale.strip().replace('_', '-').toLowerCase(Locale.ROOT);
    return text.startsWith("en") ? EN_US : ZH_CN;
  }

  public static boolean isEnglish(String locale) {
    return EN_US.equals(normalizeLocale(locale));
  }

  /** 从 {@code prompts/<relativePath>} 读取完整 UTF-8 文本，缺失时快速失败。 */
  public static String load(String relativePath) {
    return load(ZH_CN, relativePath);
  }

  /**
   * 按语言读取 Prompt 资源：英文优先 {@code prompts/en/<relativePath>}，资源不存在时回退中文。
   *
   * <p>空文件按缺失处理，避免空提示词悄悄覆盖有内容的中文回退。
   */
  public static String load(String locale, String relativePath) {
    String normalized = normalizeLocale(locale);
    String key = normalized + ":" + relativePath;
    return TEXT_CACHE.computeIfAbsent(
        key, ignored -> readLocalizedText(normalized, relativePath));
  }

  /** 读取 {@code prompts/tools/tool-descriptions.json} 中指定工具键的描述与参数说明。 */
  public static ToolCopy tool(String key) {
    return tool(ZH_CN, key);
  }

  /** 按语言读取工具契约描述；英文目录缺失或缺少键时回退中文。 */
  public static ToolCopy tool(String locale, String key) {
    String normalized = normalizeLocale(locale);
    Map<String, ToolCopy> catalog =
        TOOL_CATALOGS.computeIfAbsent(normalized, PromptResourceLoader::loadToolCatalog);
    ToolCopy copy = catalog.get(key);
    if (copy == null && !ZH_CN.equals(normalized)) {
      copy = TOOL_CATALOGS.computeIfAbsent(ZH_CN, PromptResourceLoader::loadToolCatalog).get(key);
    }
    if (copy == null) throw new IllegalStateException("未找到工具描述配置：" + key);
    return copy;
  }

  /** 明确注入输出语言约束，避免模型在英文任务中混用中文。 */
  public static String outputLanguageDirective(String locale) {
    return isEnglish(locale)
        ? "## Output language\n"
            + "Write every analysis, decision, handoff summary and final answer in natural English. "
            + "Do not answer in Chinese unless quoting user-provided source text."
        : "## 输出语言\n"
            + "请全程使用简体中文进行分析、决策、交接说明与最终回答；"
            + "除引用用户提供的原文外，不要输出英文段落。";
  }

  private static String readLocalizedText(String locale, String relativePath) {
    if (EN_US.equals(locale)) {
      String english = readClasspathTextIfPresent("en/" + relativePath);
      if (english != null) return english;
    }
    return readClasspathText(relativePath);
  }

  private static Map<String, ToolCopy> loadToolCatalog(String locale) {
    String raw =
        EN_US.equals(locale)
            ? readClasspathTextIfPresent("en/tools/tool-descriptions.json")
            : null;
    if (raw == null) raw = readClasspathText("tools/tool-descriptions.json");
    try {
      return Map.copyOf(JSON.readValue(raw, new TypeReference<Map<String, ToolCopy>>() {}));
    } catch (IOException e) {
      throw new IllegalStateException("无法解析 prompts/tools/tool-descriptions.json", e);
    }
  }

  private static String readClasspathText(String relativePath) {
    String text = readClasspathTextIfPresent(relativePath);
    if (text == null) throw new IllegalStateException("缺少 Prompt 资源文件：prompts/" + relativePath);
    return text;
  }

  /** 读取存在的非空 classpath 文本；不存在或为空时返回 null，供语言回退使用。 */
  private static String readClasspathTextIfPresent(String relativePath) {
    String fullPath = "prompts/" + relativePath;
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (loader == null) loader = PromptResourceLoader.class.getClassLoader();
    try (InputStream in = loader.getResourceAsStream(fullPath)) {
      if (in == null) return null;
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      return text.isEmpty() ? null : text;
    } catch (IOException e) {
      throw new IllegalStateException("读取 Prompt 资源文件失败：" + fullPath, e);
    }
  }
}
