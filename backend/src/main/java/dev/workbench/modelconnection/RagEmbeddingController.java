package dev.workbench.modelconnection;

import dev.workbench.framework.memory.MemoryEmbeddingProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 可选 Embedding 端点设置；只保存连接标识与模型名，API Key 始终由 BYOK 连接或环境变量提供。 */
@RestController
@RequestMapping("/api/rag-embedding")
public class RagEmbeddingController {
  private final RagEmbeddingSettings settings;
  private final List<MemoryEmbeddingProvider> providers;

  public RagEmbeddingController(
      RagEmbeddingSettings settings, List<MemoryEmbeddingProvider> providers) {
    this.settings = settings;
    this.providers = providers == null ? List.of() : List.copyOf(providers);
  }

  @GetMapping
  public Map<String, Object> view() {
    return settings.summary();
  }

  @PutMapping
  public Map<String, Object> save(@RequestBody RagEmbeddingSettings.Update update) {
    settings.save(update);
    return settings.summary();
  }

  /**
   * 真实探测端点。注意：保存成功只代表地址与模型名已落盘，不代表密钥、维度或网络可用；
   * 该接口会发一条极短文本，返回实际维度与状态码供前端显示。
   */
  @PostMapping("/probe")
  public Map<String, Object> probe() {
    var view = settings.view();
    var result = new LinkedHashMap<String, Object>();
    result.put("configured", view.endpointConfigured());
    result.put("apiKeyFromEnvironment", view.apiKeyFromEnvironment());
    result.put("expectedDimension", view.dimension());
    if (!view.enabled()) {
      result.put("ok", false);
      result.put("status", "DISABLED");
      return result;
    }
    if (!view.endpointConfigured()) {
      result.put("ok", false);
      result.put("status", "MISSING_KEY");
      return result;
    }
    for (var provider : providers) {
      if (provider == null || !provider.isAvailable()) continue;
      var probe = provider.probe();
      result.put("ok", probe.ok());
      result.put("status", probe.status());
      result.put("actualDimension", probe.dimension());
      if (probe.detail() != null && !probe.detail().isBlank())
        result.put("detail", probe.detail());
      return result;
    }
    result.put("ok", false);
    result.put("status", "UNAVAILABLE");
    return result;
  }
}
