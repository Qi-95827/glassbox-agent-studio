package dev.workbench.modelconnection;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 可选的 Embedding 端点运行时设置。
 *
 * <p>只在本地 {@code data/rag-embedding-settings.json} 保存连接标识、Base URL、模型名与维度，
 * <b>绝不保存 API Key</b>。复用 BYOK 连接时由 {@link ModelConnectionService} 在调用瞬间解密。
 * 文件不存在或未启用时完全回落到 application.properties，默认不改变 H2 测试路径。
 */
@Service
public class RagEmbeddingSettings {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Path file;
  private final boolean defaultEnabled;
  private final String defaultConnectionId;
  private final String defaultBaseUrl;
  private final String defaultModel;
  private final int defaultDimension;
  private final String apiKey;

  public RagEmbeddingSettings(
      @Value("${workbench.rag.embedding.settings-file:data/rag-embedding-settings.json}")
          String file,
      @Value("${workbench.rag.embedding.enabled:false}") boolean defaultEnabled,
      @Value("${workbench.rag.embedding.connection-id:}") String defaultConnectionId,
      @Value("${workbench.rag.embedding.base-url:}") String defaultBaseUrl,
      @Value("${workbench.rag.embedding.model:}") String defaultModel,
      @Value("${workbench.rag.embedding.dimension:1536}") int defaultDimension,
      @Value("${workbench.rag.embedding.api-key:}") String apiKey) {
    this.file = Path.of(file).toAbsolutePath().normalize();
    this.defaultEnabled = defaultEnabled;
    this.defaultConnectionId = safe(defaultConnectionId);
    this.defaultBaseUrl = safe(defaultBaseUrl);
    this.defaultModel = safe(defaultModel);
    this.defaultDimension = Math.max(0, defaultDimension);
    this.apiKey = safe(apiKey);
  }

  public record Config(
      boolean enabled, String connectionId, String baseUrl, String model, int dimension) {}

  public record View(
      boolean enabled,
      String connectionId,
      String baseUrl,
      String model,
      int dimension,
      boolean endpointConfigured,
      boolean apiKeyFromEnvironment) {}

  public record Update(
      Boolean enabled, String connectionId, String baseUrl, String model, Integer dimension) {}

  public synchronized Config current() {
    var stored = read();
    if (stored != null) return stored;
    return new Config(
        defaultEnabled, defaultConnectionId, defaultBaseUrl, defaultModel, defaultDimension);
  }

  public synchronized View view() {
    var config = current();
    boolean hasKey = !apiKey.isBlank();
    boolean endpoint =
        !config.connectionId().isBlank()
            || (!config.baseUrl().isBlank() && !config.model().isBlank() && hasKey);
    return new View(
        config.enabled(),
        config.connectionId(),
        config.baseUrl(),
        config.model(),
        config.dimension(),
        endpoint,
        hasKey);
  }

  public synchronized View save(Update update) {
    if (update == null) throw new IllegalArgumentException("Embedding 设置不能为空。");
    var current = current();
    boolean enabled = update.enabled() == null ? current.enabled() : update.enabled();
    String connectionId = text(update.connectionId(), current.connectionId());
    String baseUrl = text(update.baseUrl(), current.baseUrl());
    String model = text(update.model(), current.model());
    int dimension =
        update.dimension() == null ? current.dimension() : Math.max(0, update.dimension());
    if (enabled && connectionId.isBlank() && (baseUrl.isBlank() || model.isBlank()))
      throw new IllegalArgumentException("请选择已保存的模型连接，或同时填写 Base URL 与模型名。");
    if (!baseUrl.isBlank() && !(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")))
      throw new IllegalArgumentException("Embedding Base URL 必须以 http:// 或 https:// 开头。");
    var next = new Config(enabled, connectionId, stripSlash(baseUrl), model, dimension);
    write(next);
    return view();
  }

  private Config read() {
    if (!Files.isRegularFile(file)) return null;
    try {
      var node = JSON.readTree(Files.readString(file));
      return new Config(
          node.path("enabled").asBoolean(false),
          node.path("connectionId").asText(""),
          node.path("baseUrl").asText(""),
          node.path("model").asText(""),
          node.path("dimension").asInt(defaultDimension));
    } catch (Exception e) {
      return null;
    }
  }

  private void write(Config config) {
    try {
      Files.createDirectories(file.getParent());
      var payload = new LinkedHashMap<String, Object>();
      payload.put("enabled", config.enabled());
      payload.put("connectionId", config.connectionId());
      payload.put("baseUrl", config.baseUrl());
      payload.put("model", config.model());
      payload.put("dimension", config.dimension());
      Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
    } catch (Exception e) {
      throw new IllegalStateException("保存 Embedding 设置失败：" + e.getMessage(), e);
    }
  }

  private static String text(String value, String fallback) {
    return value == null ? fallback : value.strip();
  }

  private static String safe(String value) {
    return value == null ? "" : value.strip();
  }

  private static String stripSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  /** 供只读状态接口输出，不包含任何密钥。 */
  public Map<String, Object> summary() {
    var view = view();
    var result = new LinkedHashMap<String, Object>();
    result.put("enabled", view.enabled());
    result.put("connectionId", view.connectionId());
    result.put("baseUrl", view.baseUrl());
    result.put("model", view.model());
    result.put("dimension", view.dimension());
    result.put("endpointConfigured", view.endpointConfigured());
    result.put("apiKeyFromEnvironment", view.apiKeyFromEnvironment());
    return result;
  }
}
