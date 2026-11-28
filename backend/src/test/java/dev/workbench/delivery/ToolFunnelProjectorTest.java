package dev.workbench.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinition;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.WorkflowNodeBudget;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 工具权限漏斗必须使用真实 {@link ToolKind} 枚举与装配快照，而不是前端硬编码清单反推。
 *
 * <p>本用例直接驱动真实投影：已授予集合来自装配快照，未授予项按 Agent 定义声明、节点读写授权与运行写授权
 * 给出原因编码；任何不存在的工具名都无法出现在结果里（枚举编译期约束）。
 */
class ToolFunnelProjectorTest {
  private static final AgentDefinitionRef REF = new AgentDefinitionRef("workspace-edit", 1);

  @Test
  void grantedToolsComeFromTheAssemblySnapshotAndFilteredToolsCarryRealReasons() {
    PlanNode node = node(false, true);
    AgentDefinition definition =
        new AgentDefinition(
            REF,
            "按授权修改",
            "只修改授权文件",
            AgentRole.CODER,
            List.of(
                ToolKind.WORKSPACE_READ,
                ToolKind.WORKSPACE_SEARCH,
                ToolKind.WORKSPACE_WRITE,
                ToolKind.SIMULATED_INSPECTION),
            List.of());
    var granted = new EnumMap<ToolKind, Integer>(ToolKind.class);
    granted.put(ToolKind.WORKSPACE_SEARCH, 1);
    granted.put(ToolKind.SIMULATED_INSPECTION, 2);

    var funnel =
        ToolFunnelProjector.project(node, snapshot(granted, false), definition);

    assertNotNull(funnel);
    // 装配证据必须标注来自哪次尝试，前端据此显示「尝试 #N」。
    assertEquals(1, ToolFunnelProjector.project(node, snapshot(granted, false), definition, 1).attemptNumber());
    assertNull(funnel.attemptNumber(), "未指定尝试号时保持 null，而不是伪造 1");
    assertEquals("workspace-edit", funnel.definitionId());
    assertEquals(1, funnel.definitionVersion());
    assertEquals(
        List.of("SIMULATED_INSPECTION", "WORKSPACE_SEARCH"),
        funnel.granted().stream().map(ExecutionViewService.ToolGrantView::kind).sorted().toList());
    assertTrue(
        funnel.granted().stream().anyMatch(grant -> "SIMULATED_INSPECTION".equals(grant.kind()) && grant.version() == 2));
    assertTrue(
        funnel.granted().stream().noneMatch(grant -> "SIMULATED_ISSUE".equals(grant.kind())),
        "不存在的枚举值绝不能出现在真实投影里");

    var reasons =
        funnel.filtered().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ExecutionViewService.ToolFilterView::kind,
                    ExecutionViewService.ToolFilterView::reason));
    assertEquals("NODE_NOT_WORKSPACE_READ", reasons.get("WORKSPACE_READ"));
    assertEquals("RUN_NOT_WRITE_AUTHORIZED", reasons.get("WORKSPACE_WRITE"));
    // 定义未声明的工具也要如实说明是「定义没声明」，而不是笼统归为策略过滤。
    assertEquals("NOT_DECLARED_BY_DEFINITION", reasons.get("KNOWLEDGE_SEARCH"));
    assertFalse(reasons.containsKey("SIMULATED_INSPECTION"), "已授予的工具不得同时出现在裁剪列表");
  }

  @Test
  void nodeAuthorizationIsDistinguishedFromRunAuthorization() {
    // 节点没有写授权、运行有写授权：原因必须指向节点，而不是笼统归为运行策略。
    PlanNode readOnlyNode = node(true, false);
    AgentDefinition definition =
        new AgentDefinition(
            REF,
            "按授权修改",
            "只修改授权文件",
            AgentRole.CODER,
            List.of(ToolKind.WORKSPACE_READ, ToolKind.WORKSPACE_WRITE),
            List.of());
    var granted = new EnumMap<ToolKind, Integer>(ToolKind.class);
    granted.put(ToolKind.WORKSPACE_READ, 1);

    var nodeDenied = ToolFunnelProjector.project(readOnlyNode, snapshot(granted, true), definition);
    var write =
        nodeDenied.filtered().stream()
            .filter(item -> "WORKSPACE_WRITE".equals(item.kind()))
            .findFirst()
            .orElseThrow();
    assertEquals("NODE_NOT_WRITE_AUTHORIZED", write.reason());

    // 反过来：节点声明了写、运行未授权写 —— 原因必须指向运行授权。
    PlanNode writableNode = node(true, true);
    var runDenied = ToolFunnelProjector.project(writableNode, snapshot(granted, false), definition);
    var runWrite =
        runDenied.filtered().stream()
            .filter(item -> "WORKSPACE_WRITE".equals(item.kind()))
            .findFirst()
            .orElseThrow();
    assertEquals("RUN_NOT_WRITE_AUTHORIZED", runWrite.reason());
  }

  /**
   * 逐 MCP 工具准入必须原样透出，且 {@code ToolKind.MCP} 未被授权时原因要指向角色策略，
   * 而不是笼统的「定义未声明」（外部工具本来就不在 Agent 定义里）。
   */
  @Test
  void mcpToolDecisionsAreProjectedWithRoleReason() {
    AgentDefinition definition =
        new AgentDefinition(
            REF, "只读分析", "只读分析", AgentRole.ANALYST, List.of(ToolKind.WORKSPACE_READ), List.of());
    var granted = new EnumMap<ToolKind, Integer>(ToolKind.class);
    granted.put(ToolKind.WORKSPACE_READ, 1);
    var decisions =
        List.of(
            new AgentAssemblySnapshot.McpToolDecision("mcp::notes::search", "low", true, ""),
            new AgentAssemblySnapshot.McpToolDecision(
                "mcp::notes::create", "high", false, "ANALYST_HIGH_RISK_MCP_FORBIDDEN"));

    var funnel =
        ToolFunnelProjector.project(node(true, false), snapshot(granted, false, decisions), definition);

    assertEquals(2, funnel.mcpDecisions().size());
    var search = funnel.mcpDecisions().getFirst();
    assertEquals("mcp::notes::search", search.exposedName());
    assertEquals("LOW", search.riskLevel(), "风险等级必须归一成大写供前端 Chip 判定");
    assertTrue(search.allowed());
    assertEquals("", search.reasonCode());
    var create = funnel.mcpDecisions().get(1);
    assertFalse(create.allowed());
    assertEquals("ANALYST_HIGH_RISK_MCP_FORBIDDEN", create.reasonCode());

    String mcpReason =
        funnel.filtered().stream()
            .filter(item -> "MCP".equals(item.kind()))
            .findFirst()
            .orElseThrow()
            .reason();
    assertEquals("MCP_TOOLS_DENIED_BY_ROLE", mcpReason);
  }

  /** 没有逐工具证据时保持既有编码逻辑，不得凭空造出角色原因。 */
  @Test
  void withoutMcpDecisionsTheMcpKindKeepsItsHistoricalReason() {
    AgentDefinition definition =
        new AgentDefinition(
            REF, "只读分析", "只读分析", AgentRole.ANALYST, List.of(ToolKind.WORKSPACE_READ), List.of());
    var granted = new EnumMap<ToolKind, Integer>(ToolKind.class);
    granted.put(ToolKind.WORKSPACE_READ, 1);

    var funnel = ToolFunnelProjector.project(node(true, false), snapshot(granted, false), definition);

    assertTrue(funnel.mcpDecisions().isEmpty());
    String mcpReason =
        funnel.filtered().stream()
            .filter(item -> "MCP".equals(item.kind()))
            .findFirst()
            .orElseThrow()
            .reason();
    assertEquals("NOT_DECLARED_BY_DEFINITION", mcpReason);
  }

  private static AgentAssemblySnapshot snapshot(
      Map<ToolKind, Integer> tools,
      boolean writeAuthorized,
      List<AgentAssemblySnapshot.McpToolDecision> decisions) {
    var components =
        new AgentAssemblySnapshot.Components(
            REF,
            "a".repeat(64),
            new AgentModelRegistry.ModelReference(dev.workbench.domain.ModelMode.MOCK, "mock", 1),
            null,
            1,
            List.of(),
            tools,
            new AgentAssemblySnapshot.Authorization(
                1,
                AgentRole.CODER,
                1,
                writeAuthorized ? "ws-1" : null,
                writeAuthorized ? 1 : 0,
                writeAuthorized ? "d".repeat(64) : null,
                writeAuthorized),
            new AgentAssemblySnapshot.Input("b".repeat(64), List.of()),
            List.of(),
            List.of(),
            decisions);
    return new AgentAssemblySnapshot(
        1,
        components,
        new dev.workbench.framework.control.AgentBudgetPolicy(
            1,
            new dev.workbench.domain.RunLimits(null, null, null, null, null, null, null, null),
            null,
            Map.of(),
            null));
  }

  private static AgentAssemblySnapshot snapshot(
      Map<ToolKind, Integer> tools, boolean writeAuthorized) {
    var components =
        new AgentAssemblySnapshot.Components(
            REF,
            "a".repeat(64),
            new AgentModelRegistry.ModelReference(dev.workbench.domain.ModelMode.MOCK, "mock", 1),
            null,
            1,
            List.of(),
            tools,
            new AgentAssemblySnapshot.Authorization(
                1,
                AgentRole.CODER,
                1,
                writeAuthorized ? "ws-1" : null,
                writeAuthorized ? 1 : 0,
                writeAuthorized ? "d".repeat(64) : null,
                writeAuthorized),
            new AgentAssemblySnapshot.Input("b".repeat(64), List.of()),
            List.of());
    return new AgentAssemblySnapshot(
        1,
        components,
        new dev.workbench.framework.control.AgentBudgetPolicy(
            1,
            new dev.workbench.domain.RunLimits(null, null, null, null, null, null, null, null),
            null,
            Map.of(),
            null));
  }

  private static PlanNode node(boolean workspaceRead, boolean workspaceWrite) {
    return new PlanNode(
        UUID.randomUUID(),
        UUID.randomUUID(),
        0,
        AgentRole.CODER,
        null,
        PlanNodeStatus.PENDING,
        null,
        null,
        null,
        Instant.now(),
        List.of(),
        REF,
        "c".repeat(64),
        UUID.randomUUID(),
        "edit",
        "按授权修改",
        "implementation",
        workspaceRead,
        workspaceWrite,
        new WorkflowNodeBudget(4, 2),
        dev.workbench.framework.workflow.plan.PlanNodeKind.AGENT,
        null);
  }
}
