package dev.workbench.framework.memory;

/** 三层长期记忆类型。 */
public enum MemoryType {
  /** 项目规范、架构事实。 */
  SEMANTIC,
  /** 运行结束提炼的经验卡片，默认 CANDIDATE。 */
  EPISODIC,
  /** 可晋升为私有 Skill 的方法论。 */
  PROCEDURAL
}
