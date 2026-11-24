package dev.workbench.persistence.view;

import dev.workbench.domain.MemorySource;
import dev.workbench.framework.memory.MemoryType;

/**
 * 记忆记录的轻量投影：召回预筛与后置打分只需要这些字段，绝不把 `embedding_json`（约 20KB/条）拉进堆。
 *
 * <p>真正的向量余弦只对最终候选集按需回表计算，避免对整库做 Jackson 反序列化造成 OOM。
 */
public interface MemoryRecordSummary {
  String getId();

  String getContent();

  String getPathPatternsJson();

  String getUpdatedAt();

  double getConfidence();

  int getVersion();

  MemoryType getMemoryType();

  MemorySource getSource();
}
