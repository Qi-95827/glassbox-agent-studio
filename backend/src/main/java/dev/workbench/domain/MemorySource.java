package dev.workbench.domain;

/** 记忆来源；系统只能生成候选，不能直接写入已确认记忆。 */
public enum MemorySource {
  USER_CONFIRMED,
  RUN_EXTRACTION
}
