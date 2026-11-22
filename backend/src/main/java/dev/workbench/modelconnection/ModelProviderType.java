package dev.workbench.modelconnection;

import java.util.Arrays;
import java.util.List;

/** 内置的 OpenAI 兼容服务商预设；自定义服务商复用同一套请求协议。 */
public enum ModelProviderType {
  DEEPSEEK(
      "DeepSeek",
      "https://api.deepseek.com",
      List.of("deepseek-chat", "deepseek-reasoner", "deepseek-flash"),
      "https://api-docs.deepseek.com/"),
  OPENAI(
      "OpenAI",
      "https://api.openai.com/v1",
      List.of("gpt-4o-mini", "gpt-4o", "o4-mini"),
      "https://platform.openai.com/docs/models"),
  QWEN(
      "通义千问",
      "https://dashscope.aliyuncs.com/compatible-mode/v1",
      List.of("qwen-plus", "qwen-max", "qwen-turbo"),
      "https://help.aliyun.com/zh/model-studio/"),
  ZHIPU(
      "智谱 GLM",
      "https://open.bigmodel.cn/api/paas/v4",
      List.of("glm-4-plus", "glm-4-flash", "glm-4-air"),
      "https://open.bigmodel.cn/dev/api"),
  SILICONFLOW(
      "硅基流动",
      "https://api.siliconflow.cn/v1",
      List.of("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct"),
      "https://docs.siliconflow.cn/"),
  CUSTOM_OPENAI(
      "自定义 OpenAI 兼容",
      "",
      List.of(),
      "https://platform.openai.com/docs/api-reference");

  private final String label;
  private final String defaultBaseUrl;
  private final List<String> models;
  private final String docsUrl;

  ModelProviderType(
      String label, String defaultBaseUrl, List<String> models, String docsUrl) {
    this.label = label;
    this.defaultBaseUrl = defaultBaseUrl;
    this.models = List.copyOf(models);
    this.docsUrl = docsUrl;
  }

  public String label() {
    return label;
  }

  public String defaultBaseUrl() {
    return defaultBaseUrl;
  }

  public List<String> models() {
    return models;
  }

  public String docsUrl() {
    return docsUrl;
  }

  public static ModelProviderType fromCode(String code) {
    return Arrays.stream(values())
        .filter(value -> value.name().equalsIgnoreCase(code))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知模型服务商：" + code));
  }

  public static List<ModelProviderType> presets() {
    return List.of(values());
  }
}
