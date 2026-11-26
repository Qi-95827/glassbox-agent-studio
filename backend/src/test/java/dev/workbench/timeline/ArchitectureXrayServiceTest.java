package dev.workbench.timeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.persistence.repository.ModelCallPayloadRepository;
import dev.workbench.store.RunRecordService;
import dev.workbench.task.WorkflowExecutionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** X 光只统计真实走过的模块：没被走到就是空闲，拦截只算被拒绝的审批。 */
class ArchitectureXrayServiceTest {
  private final RunObservationService observations = mock(RunObservationService.class);
  private final RunRecordService records = mock(RunRecordService.class);
  private final WorkflowExecutionService workflows = mock(WorkflowExecutionService.class);
  private final ModelCallPayloadRepository payloads = mock(ModelCallPayloadRepository.class);

  private ArchitectureXrayService service;
  private final String runId = UUID.randomUUID().toString();

  @BeforeEach
  void setUp() {
    service =
        new ArchitectureXrayService(observations, records, workflows, payloads, new ObjectMapper());
    when(records.runSnapshot(runId))
        .thenReturn(
            new RunSnapshot(
                UUID.fromString(runId),
                UUID.randomUUID(),
                null,
                RunStatus.COMPLETED,
                2,
                3,
                12,
                12,
                6,
                null,
                3,
                240,
                5400L,
                10,
                10));
    when(workflows.workflowHandoffs(runId)).thenReturn(List.of());
    when(payloads.findByRunId(runId)).thenReturn(List.of());
  }

  @Test
  void reportsEveryModuleAsIdleWhenNothingWasRecorded() {
    when(observations.timeline(runId)).thenReturn(timeline(List.of()));
    var xray = service.xray(runId);
    assertEquals(0, xray.summary().activeModules());
    assertTrue(xray.modules().stream().allMatch(module -> "IDLE".equals(module.status())));
    assertTrue(xray.steps().isEmpty());
    assertTrue(xray.limitations().stream().anyMatch(item -> item.contains("压缩比例")));
  }

  @Test
  void mapsRealSpansToModulesStepsAndGateBlocks() {
    when(observations.timeline(runId))
        .thenReturn(
            timeline(
                List.of(
                    span("node:n1", "NODE", "n1", "COMPLETED", 1000L, 2000L),
                    span("model:m1", "MODEL", "n1", "COMPLETED", 1000L, 1500L),
                    span("tool:t1", "TOOL", "n2", "COMPLETED", 2000L, 2500L),
                    span("approval:a1", "APPROVAL", "n2", "DENIED", 2100L, 2400L))));
    var xray = service.xray(runId);

    // 节点 / 模型 / 工具（含审批）三类记录真的走到了，共三个模块被激活。
    assertEquals(3, xray.summary().activeModules());
    assertEquals(4, xray.steps().size());
    // 步骤按时间升序；同一时刻的先后由稳定的 id 次序决定，因此按类型断言而不是按下标。
    assertEquals(1000L, xray.steps().getFirst().atMs());
    assertEquals(2100L, xray.steps().getLast().atMs());
    var byKind =
        xray.steps().stream()
            .collect(java.util.stream.Collectors.toMap(step -> step.kind(), step -> step));
    assertEquals("llm", byKind.get("MODEL").glassboxTab());
    assertEquals("gateway", byKind.get("TOOL").glassboxTab());
    assertEquals("gateway", byKind.get("APPROVAL").glassboxTab());
    assertEquals("n1", byKind.get("NODE").nodeId());

    var gateway =
        xray.modules().stream().filter(module -> "tool-gateway".equals(module.id())).findFirst().orElseThrow();
    assertEquals("ACTIVE", gateway.status());
    assertEquals(2, gateway.callCount());
    assertEquals(1, gateway.gateBlocks(), "只有被拒绝的审批单才算拦截");
    var lifecycle =
        xray.modules().stream().filter(module -> "run-lifecycle".equals(module.id())).findFirst().orElseThrow();
    assertEquals(1000L, lifecycle.durationMs());
    assertEquals(List.of("n1"), lifecycle.anchorNodes());
  }

  @Test
  void countsHandoffsFromRecordedEnvelopes() {
    when(observations.timeline(runId)).thenReturn(timeline(List.of()));
    var receiver = UUID.randomUUID();
    when(workflows.workflowHandoffs(runId))
        .thenReturn(
            List.of(
                new dev.workbench.framework.workflow.plan.WorkflowHandoff(
                    1,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    receiver,
                    UUID.randomUUID(),
                    java.time.Instant.parse("2026-11-26T09:00:00Z"),
                    null,
                    "结论",
                    List.of())));
    var xray = service.xray(runId);
    var handoffs =
        xray.modules().stream()
            .filter(module -> "handoff-envelope".equals(module.id()))
            .findFirst()
            .orElseThrow();
    assertEquals("ACTIVE", handoffs.status());
    assertEquals(1, handoffs.callCount());
    assertEquals(List.of(receiver.toString()), handoffs.anchorNodes());
    assertEquals(1, xray.summary().handoffs());
  }

  private static RunObservationService.Timeline timeline(List<RunObservationService.Span> spans) {
    long input = spans.stream()
        .filter(span -> "MODEL".equals(span.kind()))
        .mapToLong(span -> 0L)
        .sum();
    return new RunObservationService.Timeline(
        1,
        "run",
        new RunObservationService.RunSummary(
            "COMPLETED", 5400L, 2, 3, 2, 3, 120L, 80L, 2, 2),
        spans,
        List.of());
  }

  private static RunObservationService.Span span(
      String id, String kind, String nodeId, String status, Long startMs, Long endMs) {
    return new RunObservationService.Span(
        id,
        kind,
        nodeId,
        nodeId,
        kind.toLowerCase(java.util.Locale.ROOT),
        status,
        java.time.Instant.ofEpochMilli(startMs).toString(),
        java.time.Instant.ofEpochMilli(endMs).toString(),
        startMs,
        endMs,
        Map.of());
  }
}
