package dev.workbench.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.domain.AgentRole;
import org.junit.jupiter.api.Test;

/** 外部 MCP 工具的角色准入：评审禁用、分析角色剥离高风险写工具。 */
class McpToolAdmissionTest {
  @Test
  void reviewerNeverGetsExternalTools() {
    assertFalse(McpToolAdmission.decide(AgentRole.REVIEWER, "LOW").allowed());
    assertFalse(McpToolAdmission.decide(AgentRole.REVIEWER, "HIGH").allowed());
    assertTrue(
        McpToolAdmission.decide(AgentRole.REVIEWER, "LOW").reason().contains("评审角色"),
        "剥离原因必须可用于玻璃盒展示");
  }

  @Test
  void analystOnlyGetsLowRiskTools() {
    assertTrue(McpToolAdmission.decide(AgentRole.ANALYST, "LOW").allowed());
    assertTrue(McpToolAdmission.decide(AgentRole.ANALYST, null).allowed());
    assertFalse(McpToolAdmission.decide(AgentRole.ANALYST, "HIGH").allowed());
    assertTrue(McpToolAdmission.decide(AgentRole.ANALYST, "HIGH").reason().contains("低风险"));
  }

  @Test
  void executableRolesMayUseHighRiskToolsButStayUnderApproval() {
    assertTrue(McpToolAdmission.decide(AgentRole.CODER, "HIGH").allowed());
    assertTrue(McpToolAdmission.decide(AgentRole.CODER, "LOW").allowed());
  }

  @Test
  void riskLevelComparisonIsCaseInsensitiveAndTrimmed() {
    assertFalse(McpToolAdmission.decide(AgentRole.ANALYST, " high ").allowed());
  }
}
