package dev.workbench.timeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.persistence.repository.ModelCallPayloadRepository;
import dev.workbench.persistence.repository.RunCheckpointRepository;
import dev.workbench.persistence.repository.ToolApprovalRequestRepository;
import dev.workbench.planning.PlanService;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunRecordService;
import dev.workbench.store.TaskRecordService;
import dev.workbench.task.WorkflowExecutionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 只读投影如实反映「记录里有什么」：没有记录就是空，缺字段只进 limitations，不伪造区间。 */
class RunObservationServiceTest {
  private final RunRecordService records = mock(RunRecordService.class);
  private final TaskRecordService tasks = mock(TaskRecordService.class);
  private final PlanService plans = mock(PlanService.class);
  private final AgentAssemblyRecordService agents = mock(AgentAssemblyRecordService.class);
  private final WorkflowExecutionService workflows = mock(WorkflowExecutionService.class);
  private final RunCheckpointRepository checkpoints = mock(RunCheckpointRepository.class);
  private final ToolApprovalRequestRepository approvals = mock(ToolApprovalRequestRepository.class);
  private final ModelCallPayloadRepository payloads = mock(ModelCallPayloadRepository.class);

  private RunObservationService service;
  private final UUID runId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new RunObservationService(
            records, tasks, plans, agents, workflows, checkpoints, approvals, payloads);
    when(records.runSnapshot(runId.toString())).thenReturn(snapshot());
    when(plans.find(runId.toString())).thenReturn(List.of());
    when(plans.specsByNode(runId.toString())).thenReturn(Map.of());
    when(agents.models(runId.toString())).thenReturn(List.of());
    when(agents.tools(runId.toString())).thenReturn(List.of());
    when(agents.agents(runId.toString())).thenReturn(List.of());
    when(workflows.workflowAttempts(runId.toString())).thenReturn(List.of());
    when(workflows.workflowHandoffs(runId.toString())).thenReturn(List.of());
    when(checkpoints.findByRunId(runId.toString())).thenReturn(List.of());
    when(approvals.findByRunIdOrderByRequestedAtDesc(runId.toString())).thenReturn(List.of());
    when(payloads.findByRunId(runId.toString())).thenReturn(List.of());
  }

  @Test
  void timelineWithoutRecordedCallsReportsZeroAndSaysWhy() {
    var timeline = service.timeline(runId.toString());
    assertEquals(runId.toString(), timeline.runId());
    assertEquals("COMPLETED", timeline.summary().status());
    assertEquals(0, timeline.summary().modelCalls());
    assertTrue(timeline.spans().isEmpty());
    assertTrue(
        timeline.limitations().stream().anyMatch(item -> item.contains("没有可用的模型调用记录")));
  }

  @Test
  void timelineCountsOnlySpansBackedByRecordedRows() {
    when(approvals.findByRunIdOrderByRequestedAtDesc(runId.toString()))
        .thenReturn(List.of(approval()));
    var timeline = service.timeline(runId.toString());
    assertEquals(1, timeline.spans().size());
    var span = timeline.spans().getFirst();
    assertEquals("APPROVAL", span.kind());
    assertNotNull(span.startedAt());
    assertNotNull(span.startMs(), "审批单有请求时间就必须给出可定位的起点");
  }

  @Test
  void checkpointsReportTheFrozenStateThatActuallyExisted() {
    when(checkpoints.findByRunId(runId.toString())).thenReturn(List.of(checkpoint()));
    var list = service.checkpoints(runId.toString());
    assertEquals(1, list.checkpoints().size());
    var frozen = list.checkpoints().getFirst().frozen();
    assertEquals(0, frozen.completedNodes());
    assertEquals(0, frozen.totalNodes());
    assertEquals(0, frozen.modelCalls());
    assertEquals(0L, frozen.inputTokens());
  }

  @Test
  void diffKeepsBothSidesSeparateAndDeclaresTheFileContentLimit() {
    var other = UUID.randomUUID();
    when(records.runSnapshot(other.toString())).thenReturn(snapshot());
    when(plans.find(other.toString())).thenReturn(List.of());
    when(plans.specsByNode(other.toString())).thenReturn(Map.of());
    when(agents.models(other.toString())).thenReturn(List.of());
    when(agents.tools(other.toString())).thenReturn(List.of());
    when(agents.agents(other.toString())).thenReturn(List.of());
    var diff = service.diff(runId.toString(), other.toString());
    assertEquals(runId.toString(), diff.baseRunId());
    assertEquals(other.toString(), diff.targetRunId());
    assertEquals("COMPLETED", diff.base().status());
    assertTrue(diff.nodes().isEmpty());
    assertTrue(diff.onlyInBase().isEmpty());
    assertTrue(diff.onlyInTarget().isEmpty());
    assertTrue(diff.limitations().stream().anyMatch(item -> item.contains("代码逐行 diff")));
  }

  private static dev.workbench.persistence.entity.ToolApprovalRequestEntity approval() {
    return new dev.workbench.persistence.entity.ToolApprovalRequestEntity(
        "approval-1",
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        "workspace_write",
        1,
        "{}",
        "HIGH",
        "写入工作区",
        "2026-11-26T09:00:00Z",
        "2026-11-26T09:10:00Z");
  }

  private static RunCheckpointRepository.CheckpointData checkpoint() {
    return new RunCheckpointRepository.CheckpointData(
        "cp-1",
        UUID.randomUUID().toString(),
        3,
        "BEFORE_TOOL",
        1,
        1,
        1200L,
        null,
        7L,
        "2026-11-26T09:00:10Z",
        "checksum",
        RunCheckpointRepository.AGENT_SCOPE_ENGINE);
  }

  private RunSnapshot snapshot() {
    return new RunSnapshot(
        runId, UUID.randomUUID(), null, RunStatus.COMPLETED, 2, 3, 12, 12, 6, null, 3, 240, 5000L,
        10, 10);
  }
}
