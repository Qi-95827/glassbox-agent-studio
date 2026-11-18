package dev.workbench.framework.context;

/** 可信的上下文来源适配器；返回的外部内容仍为低可信数据。 */
public interface AgentContextContributor {
  /** 稳定标识，用于发现重复注册；不能包含敏感内容。 */
  String id();

  /** 排序值相同时按标识排序，保证装配顺序稳定。 */
  int order();

  /** 来源的格式或行为发生不兼容变更时更新版本。 */
  default int version() {
    return 1;
  }

  /** 不适用时返回空文本；标记实际包含在文本中的来源引用。 */
  AgentInput contribute(AgentContextInput input);
}
