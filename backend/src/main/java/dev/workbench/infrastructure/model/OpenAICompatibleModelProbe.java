package dev.workbench.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.i18n.LocalizedMessages;
import dev.workbench.framework.outbound.OutboundHttpClients;
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
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** 以极小 Token 调用真实服务，验证 Base URL、模型名与密钥是否可用。 */
@Component
public class OpenAICompatibleModelProbe implements ModelProbe {
  private static final int PROBE_MAX_TOKENS = 8;
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper JSON = new ObjectMapper();

  @Override
  public ProbeResult probe(RuntimeModelConnection connection) {
    if (isEmbeddingModel(connection.modelName())) return probeEmbedding(connection);
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
              // 探活同样禁止 302 跟随，避免把已校验地址重定向到内网端点。
              .httpTransport(ModelHttpTransports.hardened(PROBE_TIMEOUT))
              .generateOptions(GenerateOptions.builder().parallelToolCalls(false).build())
              .build();
      boolean english =
          dev.workbench.framework.prompt.PromptResourceLoader.isEnglish(
              dev.workbench.framework.i18n.LocalizedMessages.detectLocaleCode());
      model.stream(
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
                      ExecutionConfig.builder().timeout(PROBE_TIMEOUT).maxAttempts(1).build())
                  .build())
          .blockLast(PROBE_TIMEOUT.plusSeconds(5));
      return new ProbeResult(true, LocalizedMessages.get("probe.ok"));
    } catch (RuntimeException e) {
      return new ProbeResult(false, reason(e));
    }
  }

  private static boolean isEmbeddingModel(String modelName) {
    String name = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
    return name.contains("embedding")
        || name.contains("embed")
        || name.startsWith("bge-")
        || name.startsWith("gte-")
        || name.startsWith("m3e-");
  }

  /** Embedding 模型只有 {@code /embeddings}，不能复用聊天探针，否则必然 404。 */
  private static ProbeResult probeEmbedding(RuntimeModelConnection connection) {
    try {
      String base = connection.baseUrl() == null ? "" : connection.baseUrl().strip();
      if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
      var payload = Map.of("model", connection.modelName(), "input", List.of("embedding probe"));
      var request =
          HttpRequest.newBuilder(URI.create(base + "/embeddings"))
              .timeout(PROBE_TIMEOUT)
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + connection.apiKey())
              .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload)))
              .build();
      var client = OutboundHttpClients.shared();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2)
        return new ProbeResult(false, statusMessage(response.statusCode()));
      var embedding = JSON.readTree(response.body()).path("data").path(0).path("embedding");
      if (!embedding.isArray() || embedding.isEmpty())
        return new ProbeResult(false, LocalizedMessages.get("probe.embeddingEmpty"));
      return new ProbeResult(true, LocalizedMessages.get("probe.embeddingOk", embedding.size()));
    } catch (RuntimeException e) {
      return new ProbeResult(false, reason(e));
    } catch (Exception e) {
      return new ProbeResult(false, reason(e));
    }
  }

  private static String statusMessage(int status) {
    return switch (status) {
      case 401 -> LocalizedMessages.get("probe.auth");
      case 403 -> LocalizedMessages.get("probe.forbidden");
      case 404 -> LocalizedMessages.get("probe.notFound");
      case 429 -> LocalizedMessages.get("probe.rateLimit");
      default ->
          status >= 400 && status < 500
              ? LocalizedMessages.get("probe.badRequest")
              : LocalizedMessages.get("probe.failed");
    };
  }

  private static String reason(Throwable error) {
    Throwable current = error;
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (current instanceof AuthenticationException) return LocalizedMessages.get("probe.auth");
      if (current instanceof PermissionDeniedException)
        return LocalizedMessages.get("probe.forbidden");
      if (current instanceof NotFoundException) return LocalizedMessages.get("probe.notFound");
      if (current instanceof RateLimitException) return LocalizedMessages.get("probe.rateLimit");
      if (current instanceof BadRequestException) return LocalizedMessages.get("probe.badRequest");
      if (current instanceof HttpTransportException transport
          && Integer.valueOf(401).equals(transport.getStatusCode()))
        return LocalizedMessages.get("probe.auth");
      if (current instanceof TimeoutException) return LocalizedMessages.get("probe.timeout");
      String text = current.getMessage();
      if (text != null
          && (text.toLowerCase(java.util.Locale.ROOT).contains("timeout") || text.contains("超时")))
        return LocalizedMessages.get("probe.timeout");
      if (current.getCause() == current) break;
      current = current.getCause();
    }
    String message = error.getMessage();
    if (message == null || message.isBlank()) return LocalizedMessages.get("probe.failed");
    return SensitiveDataMasker.maskAndTruncate(message, 300).text();
  }
}
