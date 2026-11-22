package dev.workbench.framework.memory;

/** 零重依赖的 Embedding SPI；默认实现不启用，后续可无缝接入本地向量模型。 */
public interface MemoryEmbeddingProvider {
  boolean isAvailable();

  float[] embed(String text);
}
