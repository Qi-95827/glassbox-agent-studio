package dev.workbench.infrastructure.model;

import dev.workbench.framework.trace.SensitiveDataMasker;
import dev.workbench.modelconnection.ModelProbe;
import dev.workbench.modelconnection.ModelProviderType;
import dev.workbench.modelconnection.RuntimeModelConnection;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.exception.AuthenticationException;
import io.agentscope.extensions.model.openai.exception.BadRequestException;
import io.agentscope.extensions.model.openai.exception.NotFoundException;
import io.agentscope.extensions.model.openai.exception.PermissionDeniedException;
import io.agentscope.extensions.model.openai.exception.RateLimitException;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** 以极小 Token 调用真实服务，验证 Base URL、模型名与密钥是否可用。 */
@Component
public class OpenAICompatibleModelProbe implements ModelProbe {
  private static final int PROBE_MAX_TOKENS = 8;
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

  @Override
  public ProbeResult probe(RuntimeModelConnection connection) {
    try {
      var model =
          OpenAIChatModel.builder()
              .apiKey(connection.apiKey())
              .baseUrl(connection.baseUrl())
              .modelName(connection.modelName())
              .formatter(
                  connection.providerType() == ModelProviderType.DEEPSEEK
                      ? new DeepSeekFormatter()
                      : new OpenAIChatFormatter())
              .stream(false)
              .generateOptions(GenerateOptions.builder().parallelToolCalls(false).build())
              .build();
      model
          .stream(
              List.of(
                  Msg.builder().role(MsgRole.SYSTEM).textContent("连接性测试，只回复 ok。").build(),
                  Msg.builder().role(MsgRole.USER).textContent("ping").build()),
              List.of(),
              GenerateOptions.builder()
                  .maxTokens(PROBE_MAX_TOKENS)
                  .executionConfig(
                      ExecutionConfig.builder()
                          .timeout(PROBE_TIMEOUT)
                          .maxAttempts(1)
                          .build())
                  .build())
          .blockLast(PROBE_TIMEOUT.plusSeconds(5));
      return new ProbeResult(true, "已连通，模型返回正常。");
    } catch (RuntimeException e) {
      return new ProbeResult(false, reason(e));
    }
  }

  private static String reason(Throwable error) {
    Throwable current = error;
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (current instanceof AuthenticationException) return "401 密钥无效或已过期。";
      if (current instanceof PermissionDeniedException) return "403 无权访问该模型。";
      if (current instanceof NotFoundException) return "404 模型不存在或接口路径不正确。";
      if (current instanceof RateLimitException) return "429 服务限流或额度不足，请稍后重试。";
      if (current instanceof BadRequestException) return "400 请求被拒绝，请检查 Model ID 与参数。";
      if (current instanceof HttpTransportException transport
          && Integer.valueOf(401).equals(transport.getStatusCode()))
        return "401 密钥无效或已过期。";
      if (current instanceof TimeoutException) return "连接超时，请检查 Base URL 与网络。";
      String text = current.getMessage();
      if (text != null
          && (text.toLowerCase(java.util.Locale.ROOT).contains("timeout")
              || text.contains("超时")))
        return "连接超时，请检查 Base URL 与网络。";
      if (current.getCause() == current) break;
      current = current.getCause();
    }
    String message = error.getMessage() == null ? "连接失败。" : error.getMessage();
    return SensitiveDataMasker.maskAndTruncate(message, 300).text();
  }
}
