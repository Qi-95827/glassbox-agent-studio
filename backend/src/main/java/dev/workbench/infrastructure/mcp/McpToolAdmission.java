package dev.workbench.infrastructure.mcp;

import dev.workbench.domain.AgentRole;

/**
 * 外部 MCP 工具的节点级准入。
 *
 * <p>MCP 工具由用户连接引入，能力边界不受本仓库控制，因此必须按节点角色收口：
 *
 * <ul>
 *   <li>{@code REVIEWER} 只做证据评审，禁止装配任何外部工具；
 *   <li>{@code ANALYST} 只允许低风险只读工具，高风险（写/外发）一律剥离；
 *   <li>其余可执行角色允许装配，高风险调用仍要经过 Gate 5.5 人工审批。
 * </ul>
 *
 * <p>被剥离的工具必须带明确原因，供玻璃盒透视「为什么不给我这个工具」。
 */
public final class McpToolAdmission {
  private McpToolAdmission() {}

  /** 评审角色禁用外部工具的稳定原因编码。 */
  public static final String REVIEWER_FORBIDDEN = "REVIEWER_MCP_FORBIDDEN";

  /** 分析角色剥离高风险外部工具的原因编码。 */
  public static final String ANALYST_HIGH_RISK_FORBIDDEN = "ANALYST_HIGH_RISK_MCP_FORBIDDEN";

  /**
   * 准入结论：允许或拒绝并给出原因。
   *
   * <p>{@code reasonCode} 是结构化原因编码，供玻璃盒逐工具展示与前端翻译；{@code reason} 保留中文自然语言，
   * 兼容既有事件日志与断言。允许时两者均为空串。
   */
  public record Decision(boolean allowed, String reasonCode, String reason) {
    public Decision {
      reasonCode = reasonCode == null ? "" : reasonCode;
      reason = reason == null ? "" : reason;
    }

    static Decision allow() {
      return new Decision(true, "", "");
    }

    static Decision deny(String reasonCode, String reason) {
      return new Decision(false, reasonCode, reason);
    }
  }

  /** 判定某个角色能否装配指定风险等级的 MCP 工具。 */
  public static Decision decide(AgentRole role, String riskLevel) {
    boolean highRisk = "HIGH".equalsIgnoreCase(riskLevel == null ? "" : riskLevel.strip());
    if (role == AgentRole.REVIEWER)
      return Decision.deny(REVIEWER_FORBIDDEN, "评审角色禁止使用外部 MCP 工具。");
    if (role == AgentRole.ANALYST && highRisk)
      return Decision.deny(ANALYST_HIGH_RISK_FORBIDDEN, "分析角色只能使用低风险只读 MCP 工具。");
    return Decision.allow();
  }
}
