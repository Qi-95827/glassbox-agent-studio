package dev.workbench.domain;

/** 协作流程中的角色；不同角色拥有独立上下文和工具范围。 */
public enum AgentRole {
  /** 使用模拟工具产生可追溯的分析结果。 */
  ANALYST("分析 Agent", 3),
  /** 根据结构化分析在用户授权范围内进行代码开发。 */
  CODER("开发 Agent", 3),
  /** 仅接收交接内容并检查证据与限制，不使用工具。 */
  REVIEWER("审查 Agent", 2);

  private final String displayName;
  private final int version;

  AgentRole(String displayName, int version) {
    this.displayName = displayName;
    this.version = version;
  }

  public String displayName() {
    return displayName;
  }

  /** 角色职责与工具声明的受控版本；旧历史记录可能未保存。 */
  public int version() {
    return version;
  }
}
