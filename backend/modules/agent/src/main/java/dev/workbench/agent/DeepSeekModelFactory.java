package dev.workbench.agent;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 创建 DeepSeek 模型；密钥只在请求前读取，不写入日志。 */
final class DeepSeekModelFactory {
  static Model deepSeekModel() {
    try {
      String key =
          Files.readString(Path.of("config", "deepseek-api-key.txt"), StandardCharsets.UTF_8)
              .replaceFirst("^\uFEFF", "")
              .strip();
      if (key.isBlank() || key.contains("\n") || key.contains("\r"))
        throw new IllegalStateException("请在密钥文件中填写一行有效 API Key。");
      return OpenAIChatModel.builder()
          .apiKey(key)
          .baseUrl("https://api.deepseek.com")
          .modelName("deepseek-flash")
          .stream(false)
          .generateOptions(
              GenerateOptions.builder()
                  .maxTokens(256)
                  .parallelToolCalls(false)
                  .additionalBodyParam("thinking", Map.of("type", "disabled"))
                  .build())
          .build();
    } catch (java.io.IOException e) {
      throw new IllegalStateException("无法读取 DeepSeek 密钥文件。");
    }
  }
}
