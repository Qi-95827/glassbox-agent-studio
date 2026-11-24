package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.workbench.framework.control.RunControl;
import dev.workbench.framework.tool.ToolApprovalDecision;
import dev.workbench.framework.tool.ToolRiskAssessment;
import dev.workbench.framework.tool.ToolRiskLevel;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.tool.ToolApprovalService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Gate 5.5 审批通道与可选 nodePolicy 解耦。
 *
 * <p>反例对应 `F-03`：此前 `approvalDecision()` 以 {@code nodePolicy != null} 为前提，所有未配置预算的
 * 内置模板与候选计划节点都会走「无审批通道」分支，高危工具被一律拒绝——HITL 在生产链路形同虚设。
 */
class ControlledAgentRuntimeHitlTest {
  private final RunControl control = mock(RunControl.class);
  private final ToolApprovalService approvals = mock(ToolApprovalService.class);
  private final String runId = "run-1";
  private final String agentId = "agent-1";
  private final UUID nodeId = UUID.randomUUID();

  private ControlledAgentRuntime runtime(String hitlNodeId, RunControl.NodePolicy policy) {
    return new ControlledAgentRuntime(
        runId,
        agentId,
        control,
        mock(AgentAssemblyRecordService.class),
        mock(RunStateStore.class),
        mock(RunEvidenceRecordService.class),
        policy,
        null,
        null,
        null,
        null,
        approvals,
        mock(org.springframework.context.ApplicationEventPublisher.class),
        hitlNodeId);
  }

  private static ToolRiskAssessment risk() {
    return new ToolRiskAssessment(ToolRiskLevel.HIGH, "写入敏感配置");
  }

  @Test
  void nodeWithoutBudgetStillReachesHitlApproval() {
    var decision = ToolApprovalDecision.approve();
    when(approvals.await(
            eq(runId),
            eq(nodeId),
            eq(agentId),
            eq("workspace_write"),
            eq(3),
            anyString(),
            any(),
            any()))
        .thenReturn(decision);

    var result =
        runtime(nodeId.toString(), null).approvalDecision("workspace_write", 3, "{}", risk());

    assertSame(decision, result);
    verify(approvals)
        .await(
            eq(runId),
            eq(nodeId),
            eq(agentId),
            eq("workspace_write"),
            eq(3),
            anyString(),
            any(),
            any());
  }

  @Test
  void legacyPolicyStillSuppliesNodeId() {
    var decision = ToolApprovalDecision.reject("用户拒绝");
    when(approvals.await(
            anyString(), any(), anyString(), anyString(), anyInt(), anyString(), any(), any()))
        .thenReturn(decision);
    var policy = new RunControl.NodePolicy(nodeId, null);

    var result = runtime(null, policy).approvalDecision("workspace_write", 1, "{}", risk());

    assertSame(decision, result);
    verify(approvals)
        .await(
            eq(runId), eq(nodeId), anyString(), anyString(), anyInt(), anyString(), any(), any());
  }

  @Test
  void missingNodeAndPolicyRejectsWithoutCallingApproval() {
    var result = runtime(null, null).approvalDecision("workspace_write", 1, "{}", risk());

    assertTrue(result.rejected());
    assertTrue(result.reason().contains("审批通道"));
    verify(approvals, never())
        .await(anyString(), any(), anyString(), anyString(), anyInt(), anyString(), any(), any());
  }

  @Test
  void malformedNodeIdIsRejectedInsteadOfThrowing() {
    var result = runtime("not-a-uuid", null).approvalDecision("workspace_write", 1, "{}", risk());

    assertTrue(result.rejected());
    assertTrue(result.reason().contains("节点标识无效"));
  }
}
