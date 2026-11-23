package dev.workbench.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.memory.MemoryEmbeddingProvider;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.modelconnection.RagEmbeddingSettings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 标准 OpenAI 兼容 {@code /v1/embeddings} 适配器。
 *
 * <p>支持 DeepSeek / SiliconFlow / OpenAI / Ollama / vLLM 等兼容端点；可复用已保存的 BYOK 连接
 * （仅使用其 Base URL 与密钥，不写入日志），也可独立配置。未启用或不可达时返回
 * {@code isAvailable() == false}，调用方自动降级为 BM25，绝不阻塞主流程。
 */
@Component
public class OpenAiCompatibleEmbeddingProvider implements MemoryEmbeddingProvider {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_BATCH = 64;

  private final ModelConnectionService connections;
  private final RagEmbeddingSettings settings;
  private final String apiKey;
  private final Duration timeout;
  private final int batchSize;

  public OpenAiCompatibleEmbeddingProvider(
      ModelConnectionService connections,
      RagEmbeddingSettings settings,
      @Value("${workbench.rag.embedding.api-key:}") String apiKey,
      @Value("${workbench.rag.embedding.timeout-seconds:20}") int timeoutSeconds,
      @Value("${workbench.rag.embedding.batch-size:32}") int batchSize) {
    this.connections = connections;
    this.settings = settings;
    this.apiKey = apiKey == null ? "" : apiKey.strip();
    this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    this.batchSize = Math.max(1, Math.min(MAX_BATCH, batchSize));
  }

  @Override
  public boolean isAvailable() {
    var config = settings.current();
    return config.enabled() && endpoint(config) != null;
  }

  @Override
  public String modelName() {
    var endpoint = endpoint(settings.current());
    return endpoint == null ? "" : endpoint.model();
  }

  @Override
  public int dimension() {
    return settings.current().dimension();
  }

  @Override
  public float[] embed(String text) {
    if (text == null || text.isBlank()) return new float[0];
    var result = embedAll(List.of(text));
    return result.isEmpty() ? new float[0] : result.getFirst();
  }

  @Override
  public List<float[]> embedAll(List<String> texts) {
    if (texts == null || texts.isEmpty()) return List.of();
    var config = settings.current();
    var endpoint = endpoint(config);
    if (endpoint == null) return List.of();
    var result = new ArrayList<float[]>(texts.size());
    for (int start = 0; start < texts.size(); start += batchSize) {
      int end = Math.min(texts.size(), start + batchSize);
      result.addAll(requestBatch(endpoint, texts.subList(start, end), true));
    }
    return List.copyOf(result);
  }

  @Override
  public Probe probe() {
    var config = settings.current();
    var endpoint = endpoint(config);
    if (endpoint == null) return new Probe(false, 0, "UNAVAILABLE");
    var vectors = requestBatch(endpoint, List.of("embedding connectivity probe"), false);
    if (vectors.isEmpty() || vectors.getFirst() == null || vectors.getFirst().length == 0)
      return new Probe(false, 0, "EMPTY");
    int actual = vectors.getFirst().length;
    String status = config.dimension() > 0 && actual != config.dimension() ? "DIMENSION_MISMATCH" : "OK";
    return new Probe(true, actual, status);
  }

  private List<float[]> requestBatch(Endpoint endpoint, List<String> texts, boolean enforceDimension) {
    try {
      int dimension = settings.current().dimension();
      var payload = new LinkedHashMap<String, Object>();
      payload.put("model", endpoint.model());
      payload.put("input", texts);
      if (dimension > 0) payload.put("dimensions", dimension);
      var requestBuilder =
          HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + "/embeddings"))
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload)));
      if (!endpoint.apiKey().isBlank())
        requestBuilder.header("Authorization", "Bearer " + endpoint.apiKey());
      var client =
          HttpClient.newBuilder()
              .connectTimeout(timeout)
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
      var response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) return List.of();
      JsonNode root = JSON.readTree(response.body());
      var vectors = new ArrayList<float[]>(texts.size());
      for (JsonNode item : root.path("data")) {
        var embedding = item.path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) continue;
        float[] values = new float[embedding.size()];
        for (int index = 0; index < embedding.size(); index++)
          values[index] = (float) embedding.get(index).asDouble();
        if (enforceDimension && dimension > 0 && values.length != dimension) continue;
        vectors.add(values);
      }
      return vectors.size() == texts.size() ? List.copyOf(vectors) : List.of();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    } catch (Exception e) {
      // 端点不可达或返回非兼容结构时静默降级；调用方继续使用 BM25。
      return List.of();
    }
  }

  private Endpoint endpoint(RagEmbeddingSettings.Config config) {
    if (!config.enabled()) return null;
    if (!config.connectionId().isBlank()) {
      try {
        var connection = connections.resolveForExecution(config.connectionId());
        if (connection.baseUrl() == null || connection.baseUrl().isBlank()) return null;
        return new Endpoint(
            stripTrailingSlash(connection.baseUrl()), connection.modelName(), connection.apiKey());
      } catch (RuntimeException e) {
        return null;
      }
    }
    if (config.baseUrl().isBlank() || config.model().isBlank()) return null;
    return new Endpoint(stripTrailingSlash(config.baseUrl()), config.model(), apiKey);
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private record Endpoint(String baseUrl, String model, String apiKey) {}
}
