package dev.workbench.framework.tool;

/** 工具声明的操作性质，不表示写操作一定已发生或可撤回。 */
public enum ToolEffect {
  READ_ONLY,
  WRITE
}
