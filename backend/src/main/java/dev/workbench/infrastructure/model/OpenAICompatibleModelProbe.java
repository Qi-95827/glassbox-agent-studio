package dev.workbench.infrastructure.model;

import dev.workbench.framework.i18n.LocalizedMessages;
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
      boolean english =
          dev.workbench.framework.prompt.PromptResourceLoader.isEnglish(
              dev.workbench.framework.i18n.LocalizedMessages.detectLocaleCode());
      model
          .stream(
              List.of(
                  Msg.builder()
                      .role(MsgRole.SYSTEM)
                      .textContent(english ? "Connectivity check; reply only ok." : "连接性测试，只回复 ok。")
                      .build(),
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
      return new ProbeResult(true, LocalizedMessages.get("probe.ok"));
    } catch (RuntimeException e) {
      return new ProbeResult(false, reason(e));
    }
  }

  private static String reason(Throwable error) {
    Throwable current = error;
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (current instanceof AuthenticationException)
        return LocalizedMessages.get("probe.auth");
      if (current instanceof PermissionDeniedException)
        return LocalizedMessages.get("probe.forbidden");
      if (current instanceof NotFoundException)
        return LocalizedMessages.get("probe.notFound");
      if (current instanceof RateLimitException)
        return LocalizedMessages.get("probe.rateLimit");
      if (current instanceof BadRequestException)
        return LocalizedMessages.get("probe.badRequest");
      if (current instanceof HttpTransportException transport
          && Integer.valueOf(401).equals(transport.getStatusCode()))
        return LocalizedMessages.get("probe.auth");
      if (current instanceof TimeoutException)
        return LocalizedMessages.get("probe.timeout");
      String text = current.getMessage();
      if (text != null
          && (text.toLowerCase(java.util.Locale.ROOT).contains("timeout")
              || text.contains("超时")))
        return LocalizedMessages.get("probe.timeout");
      if (current.getCause() == current) break;
      current = current.getCause();
    }
    String message = error.getMessage();
    if (message == null || message.isBlank())
      return LocalizedMessages.get("probe.failed");
    return SensitiveDataMasker.maskAndTruncate(message, 300).text();
  }
}
