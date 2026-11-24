package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.control.AgentBudgetPolicy;
import dev.workbench.framework.control.RunControl;
import dev.workbench.framework.model.AgentModelRegistry.ModelReference;
import dev.workbench.framework.tool.AgentToolContext;
import dev.workbench.framework.tool.ConservativeToolRiskPolicy;
import dev.workbench.framework.tool.ToolApprovalDecision;
import dev.workbench.framework.tool.ToolEffect;
import dev.workbench.framework.tool.ToolGateway;
import dev.workbench.framework.tool.ToolRiskLevel;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.tool.ToolApprovalService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 真实联动：{@code ToolGateway（真实 ConservativeToolRiskPolicy） → 真实 ControlledAgentRuntime → 审批服务}。
 *
 * <p>反例对应 `S-06`：网关必须用带 {@code mcp::} 前缀的真实工具名做风险分级，且装配记录里必须有 {@code ToolKind.MCP -> 1}，否则运行时
 * {@code beforeTool} 直接拒绝。本类使用真实的运行时与策略， 只把持久化与审批服务替换为 Mockito 桩。
 */
class McpApprovalLinkageTest {
  private static final String RUN = "run-mcp";
  private static final String AGENT = "agent-mcp";

  private final RunControl control = mock(RunControl.class);
  private final ToolApprovalService approvals = mock(ToolApprovalService.class);
  private final RunEvidenceRecordService evidence = mock(RunEvidenceRecordService.class);
  private final UUID nodeId = UUID.randomUUID();

  private ControlledAgentRuntime runtime() {
    var policy =
        new AgentBudgetPolicy(
            1,
            new dev.workbench.domain.RunLimits(4, 2, 2, null, null, 30, null, null),
            null,
            Map.of(),
            null);
    when(control.budgetPolicy(AgentRole.ANALYST, null)).thenReturn(policy);
    when(evidence.eventRefForAgent(anyString(), anyString()))
        .thenReturn(dev.workbench.domain.EventRef.empty());
    when(control.beginTool(anyString(), any(), anyInt(), anyString(), isNull()))
        .thenReturn(UUID.randomUUID());
    return new ControlledAgentRuntime(
        RUN,
        AGENT,
        control,
        mock(AgentAssemblyRecordService.class),
        mock(RunStateStore.class),
        evidence,
        null,
        null,
        null,
        null,
        null,
        approvals,
        mock(org.springframework.context.ApplicationEventPublisher.class),
        nodeId.toString());
  }

  private static AgentAssemblySnapshot.Components components(boolean withMcp) {
    var tools =
        withMcp
            ? Map.of(ToolKind.WORKSPACE_READ, 1, ToolKind.MCP, 1)
            : Map.of(ToolKind.WORKSPACE_READ, 1);
    return new AgentAssemblySnapshot.Components(
        new AgentDefinitionRef("analyst", 1),
        "a".repeat(64),
        new ModelReference(ModelMode.MOCK, "mock", 1),
        20,
        1,
        List.of(),
        tools,
        new AgentAssemblySnapshot.Authorization(
            1, AgentRole.ANALYST, 1, "workspace", 1, "b".repeat(64), false),
        new AgentAssemblySnapshot.Input("c".repeat(64), List.of()));
  }

  private ToolGateway gateway(ControlledAgentRuntime runtime) {
    var context =
        new AgentToolContext(
            new ObjectMapper().findAndRegisterModules(),
            "ws-1",
            List.of("README.md"),
            Scenario.NORMAL,
            runtime,
            new AtomicReference<>(),
            List.of("README.md"));
    return new ToolGateway(context.json(), context.runtime(), context.failure());
  }

  @Test
  void assembledMcpWriteIsAuthorizedAndRequiresApproval() {
    var runtime = runtime();
    runtime.assemblyResolved(components(true));
    var decision = ToolApprovalDecision.approve();
    when(approvals.await(
            eq(RUN),
            eq(nodeId),
            eq(AGENT),
            eq("mcp::notes::create"),
            eq(1),
            anyString(),
            any(),
            any()))
        .thenReturn(decision);

    var result =
        gateway(runtime)
            .execute(
                ToolKind.MCP,
                "mcp::notes::create",
                1,
                AgentAssemblySnapshot.sha256("{}"),
                "{}",
                ToolEffect.WRITE,
                "调用外部工具",
                value -> "ok",
                () -> {},
                null,
                null,
                null,
                () -> "server-response");

    assertTrue(result.contains("server-response"), "网关必须返回被审批放行的执行结果：" + result);
    assertTrue(result.contains("WRITE"), "写副作用必须被记录：" + result);
    verify(approvals)
        .await(
            eq(RUN),
            eq(nodeId),
            eq(AGENT),
            eq("mcp::notes::create"),
            eq(1),
            anyString(),
            argThat(risk -> risk.level() == ToolRiskLevel.HIGH),
            any());
  }

  @Test
  void mcpCallWithoutAssemblyRecordIsRejectedBeforeApproval() {
    var runtime = runtime();
    runtime.assemblyResolved(components(false));

    assertThrows(
        IllegalStateException.class,
        () ->
            gateway(runtime)
                .execute(
                    ToolKind.MCP,
                    "mcp::notes::create",
                    1,
                    "fp",
                    "{}",
                    ToolEffect.WRITE,
                    "调用外部工具",
                    value -> "ok",
                    () -> {},
                    null,
                    null,
                    null,
                    () -> "server-response"));

    verify(approvals, never())
        .await(anyString(), any(), anyString(), anyString(), anyInt(), anyString(), any(), any());
  }

  @Test
  void riskPolicyUsesMcpPrefixSoWriteCallsAreAlwaysHigh() {
    var policy = new ConservativeToolRiskPolicy();

    assertEquals(
        ToolRiskLevel.HIGH,
        policy
            .assess(
                new dev.workbench.framework.tool.ToolRiskContext(
                    "mcp::notes::create", ToolEffect.WRITE, "{}", List.of()))
            .level());
    assertEquals(
        ToolRiskLevel.LOW,
        policy
            .assess(
                new dev.workbench.framework.tool.ToolRiskContext(
                    "mcp::notes::search", ToolEffect.READ_ONLY, "{}", List.of()))
            .level());
  }
}
