package dev.workbench.modelconnection;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 可选 Embedding 端点设置；只保存连接标识与模型名，API Key 始终由 BYOK 连接或环境变量提供。 */
@RestController
@RequestMapping("/api/rag-embedding")
public class RagEmbeddingController {
  private final RagEmbeddingSettings settings;

  public RagEmbeddingController(RagEmbeddingSettings settings) {
    this.settings = settings;
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
}
