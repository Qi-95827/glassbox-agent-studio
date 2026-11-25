package dev.workbench.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.tool.ToolApprovalAction;
import dev.workbench.framework.tool.ToolApprovalDecision;
import dev.workbench.framework.tool.ToolApprovalStatus;
import dev.workbench.framework.tool.ToolRiskAssessment;
import dev.workbench.framework.tool.ToolRiskLevel;
import dev.workbench.persistence.entity.ToolApprovalRequestEntity;
import dev.workbench.persistence.repository.PlanStateRepository;
import dev.workbench.persistence.repository.ToolApprovalRequestRepository;
import dev.workbench.store.RunEvidenceRecordService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 审批单复用、拒绝理由必填、改参 JSON 校验与过期保护。 */
class ToolApprovalServiceTest {
  private final ToolApprovalRequestRepository approvals = mock(ToolApprovalRequestRepository.class);
  private final PlanStateRepository plans = mock(PlanStateRepository.class);
  private final RunEvidenceRecordService evidence = mock(RunEvidenceRecordService.class);
  private final ToolApprovalService service =
      new ToolApprovalService(approvals, plans, evidence, new ObjectMapper(), 1);

  @Test
  void reusableApprovedDecisionDoesNotCreateAnotherInterrupt() {
    String runId = UUID.randomUUID().toString();
    String nodeId = UUID.randomUUID().toString();
    var row = pending(runId, nodeId, "WORKSPACE_WRITE", "{\"path\":\"pom.xml\"}");
    row.decide(
        ToolApprovalStatus.APPROVED,
        ToolApprovalAction.APPROVE.name(),
        null,
        "已批准",
        Instant.now().toString());
    when(approvals.findByRunIdOrderByRequestedAtDesc(runId)).thenReturn(List.of(row));

    ToolApprovalDecision decision =
        service.await(
            runId,
            UUID.fromString(nodeId),
            UUID.randomUUID().toString(),
            "WORKSPACE_WRITE",
            1,
            "{\"path\":\"pom.xml\"}",
            new ToolRiskAssessment(ToolRiskLevel.HIGH, "敏感路径"),
            () -> {});
    assertEquals(ToolApprovalAction.APPROVE, decision.action());
    verify(approvals, never()).save(any());
    verify(plans, never()).waitForToolApproval(any(), any(), any());
  }

  @Test
  void rejectRequiresReasonAndRecordsDecision() {
    String runId = UUID.randomUUID().toString();
    var row = pending(runId, UUID.randomUUID().toString(), "mcp::server::write", "{}");
    when(approvals.findByIdAndRunId(row.getId(), runId)).thenReturn(Optional.of(row));
    // 决策走条件更新：模拟数据库只对 PENDING 成功改写。
    when(approvals.decideIfPending(
            eq(row.getId()),
            eq(runId),
            eq(ToolApprovalStatus.PENDING),
            any(),
            anyString(),
            any(),
            anyString(),
            anyString()))
        .thenAnswer(
            invocation -> {
              row.decide(
                  invocation.getArgument(3),
                  invocation.getArgument(4),
                  invocation.getArgument(5),
                  invocation.getArgument(6),
                  invocation.getArgument(7));
              return 1;
            });

    assertThrows(
        IllegalArgumentException.class,
        () -> service.decide(runId, row.getId(), ToolApprovalAction.REJECT, null, " "));
    var decided = service.decide(runId, row.getId(), ToolApprovalAction.REJECT, null, "不要访问外部系统");
    assertEquals("REJECTED", decided.status());
    assertEquals("不要访问外部系统", decided.decisionReason());
    verify(approvals)
        .decideIfPending(
            eq(row.getId()),
            eq(runId),
            eq(ToolApprovalStatus.PENDING),
            eq(ToolApprovalStatus.REJECTED),
            eq(ToolApprovalAction.REJECT.name()),
            isNull(),
            eq("不要访问外部系统"),
            anyString());
  }

  @Test
  void concurrentSecondDecisionIsRejectedInsteadOfOverwriting() {
    String runId = UUID.randomUUID().toString();
    var row =
        pending(runId, UUID.randomUUID().toString(), "WORKSPACE_WRITE", "{\"path\":\"a.txt\"}");
    when(approvals.findByIdAndRunId(row.getId(), runId)).thenReturn(Optional.of(row));
    // 条件更新影响 0 行：说明已被其它线程或超时路径处理。
    when(approvals.decideIfPending(
            any(), any(), any(), any(), anyString(), any(), anyString(), anyString()))
        .thenReturn(0);

    var error =
        assertThrows(
            IllegalStateException.class,
            () -> service.decide(runId, row.getId(), ToolApprovalAction.APPROVE, null, "同意"));

    assertTrue(
        error.getMessage().contains("已经处理") || error.getMessage().contains("已过期"),
        error.getMessage());
    verify(approvals, never()).save(any());
  }

  @Test
  void editedApprovalMustBeJsonObject() {
    String runId = UUID.randomUUID().toString();
    var row = pending(runId, UUID.randomUUID().toString(), "WORKSPACE_WRITE", "{}");
    when(approvals.findByIdAndRunId(row.getId(), runId)).thenReturn(Optional.of(row));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.decide(
                runId, row.getId(), ToolApprovalAction.APPROVE_WITH_EDITED_ARGS, "{broken", "改参"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.decide(
                runId, row.getId(), ToolApprovalAction.APPROVE_WITH_EDITED_ARGS, "[]", "改参"));
  }

  @Test
  void expiredRequestCannotBeApproved() {
    String runId = UUID.randomUUID().toString();
    var row = pending(runId, UUID.randomUUID().toString(), "WORKSPACE_WRITE", "{}");
    var expired =
        new ToolApprovalRequestEntity(
            row.getId(),
            row.getRunId(),
            row.getNodeId(),
            row.getAgentId(),
            row.getToolName(),
            row.getToolVersion(),
            row.getOriginalArgsJson(),
            row.getRiskLevel(),
            row.getRiskReason(),
            Instant.now().minusSeconds(10).toString(),
            Instant.now().minusSeconds(1).toString());
    when(approvals.findByIdAndRunId(row.getId(), runId)).thenReturn(Optional.of(expired));
    assertThrows(
        IllegalStateException.class,
        () -> service.decide(runId, row.getId(), ToolApprovalAction.APPROVE, null, null));
  }

  private static ToolApprovalRequestEntity pending(
      String runId, String nodeId, String tool, String args) {
    return new ToolApprovalRequestEntity(
        UUID.randomUUID().toString(),
        runId,
        nodeId,
        UUID.randomUUID().toString(),
        tool,
        1,
        args,
        "HIGH",
        "敏感路径",
        Instant.now().toString(),
        Instant.now().plusSeconds(60).toString());
  }
}
