package dev.workbench.framework.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * 零重依赖的 Embedding SPI。
 *
 * <p>默认实现不启用；未配置真实端点时 {@link #isAvailable()} 必须返回 {@code false}，调用方自动降级为
 * BM25 词法检索。批量接口默认逐条回退，允许真实实现覆盖为单次 HTTP 批请求。
 */
public interface MemoryEmbeddingProvider {
  boolean isAvailable();

  float[] embed(String text);

  /** 生成该向量的模型标识；不同模型/维度不得混用，落库时一并记录。 */
  default String modelName() {
    return getClass().getSimpleName();
  }

  /** 期望维度；0 表示由实现自行决定。 */
  default int dimension() {
    return 0;
  }

  /** 批请求默认逐条降级；真实实现应覆盖以降低延迟与费用。 */
  default List<float[]> embedAll(List<String> texts) {
    if (texts == null || texts.isEmpty()) return List.of();
    var result = new ArrayList<float[]>(texts.size());
    for (String text : texts) result.add(embed(text));
    return List.copyOf(result);
  }

  /** 主动探测端点是否真的可用；默认发一条极短文本，避免把“已保存”误报成“可用”。 */
  default Probe probe() {
    if (!isAvailable()) return new Probe(false, 0, "UNAVAILABLE");
    try {
      var vector = embed("embedding connectivity probe");
      if (vector == null || vector.length == 0) return new Probe(false, 0, "EMPTY");
      return new Probe(true, vector.length, "OK");
    } catch (RuntimeException e) {
      return new Probe(false, 0, "ERROR");
    }
  }

  record Probe(boolean ok, int dimension, String status, String detail) {
    public Probe(boolean ok, int dimension, String status) {
      this(ok, dimension, status, "");
    }
  }
}
