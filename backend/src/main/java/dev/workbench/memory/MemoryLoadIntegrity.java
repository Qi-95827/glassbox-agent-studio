package dev.workbench.memory;

/** 记忆装配引用的数量和顺序检查结果。 */
public enum MemoryLoadIntegrity {
  /** 旧运行未留下装配标记。 */
  NOT_RECORDED,
  /** 声明数量与现存引用数量、连续顺序一致。 */
  CONSISTENT,
  /** 数量或顺序不一致，不能推断原始装配内容。 */
  INCONSISTENT
}
