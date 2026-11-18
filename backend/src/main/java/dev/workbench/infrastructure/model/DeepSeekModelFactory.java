package dev.workbench.infrastructure.model;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import io.agentscope.extensions.model.openai.exception.RateLimitException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 创建 DeepSeek 模型；密钥只在请求前读取，不写入日志。 */
final class DeepSeekModelFactory {
  static boolean isRateLimitRejection(Throwable error) {
    // SDK 在实际 HTTP 拒绝路径传入空 errorCode；HTTP 200 的推断分类携带正文代码。
    if (error instanceof RateLimitException rejected && rejected.getErrorCode() == null)
      return true;
    var transport = error instanceof OpenAIException ? error.getCause() : error;
    return transport instanceof HttpTransportException response
        && Integer.valueOf(429).equals(response.getStatusCode());
  }

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
