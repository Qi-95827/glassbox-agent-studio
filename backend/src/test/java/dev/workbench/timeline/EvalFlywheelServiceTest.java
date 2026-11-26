package dev.workbench.timeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.evaluation.EvalCustomDatasetService;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunRecordService;
import dev.workbench.store.TaskRecordService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 沉淀用例只写既有自定义数据集口径：断言值来自真实证据并先脱敏。 */
class EvalFlywheelServiceTest {
  private final RunRecordService records = mock(RunRecordService.class);
  private final TaskRecordService tasks = mock(TaskRecordService.class);
  private final AgentAssemblyRecordService agents = mock(AgentAssemblyRecordService.class);
  private final EvalCustomDatasetService datasets = mock(EvalCustomDatasetService.class);

  private EvalFlywheelService service;
  private final String runId = "abcdef12-3456-7890-abcd-ef1234567890";
  private final UUID taskId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new EvalFlywheelService(records, tasks, agents, datasets);
    when(records.runSnapshot(runId))
        .thenReturn(
            new RunSnapshot(
                UUID.fromString(runId), taskId, null, RunStatus.COMPLETED, 1, 1, 12, 12, 6, null, 3, 240,
                1000L, 10, 10));
    when(tasks.taskDefinition(taskId.toString()))
        .thenReturn(
            new TaskDefinition(
                taskId,
                "把排序网页做出来",
                Scenario.NORMAL,
                ModelMode.MOCK,
                "ws-1",
                "sort.js",
                true,
                null,
                false,
                List.of(),
                List.of("页面能排序"),
                TaskEntryKind.CODE,
                List.of("sort.js"),
                Map.of(),
                Map.of(),
                null,
                "zh-CN"));
    when(agents.agents(runId))
        .thenReturn(List.of(Map.of("ID", "a1", "RESULT", "排序完成，包含 3 个闲等节点")));
    when(datasets.list()).thenReturn(List.of());
    when(datasets.create(any()))
        .thenReturn(
            new EvalCustomDatasetService.DatasetView(
                "ds-1",
                "run-distilled",
                "从运行沉淀的用例",
                "CODING_REFLECTION",
                "",
                0,
                "now",
                "now"));
    when(datasets.samples(anyString())).thenReturn(List.of());
  }

  @Test
  void previewBuildsAssertionsFromRealEvidenceWithoutWriting() {
    var draft = service.preview(runId);
    assertNull(draft.datasetId());
    assertEquals("run-distilled", draft.datasetCode());
    assertEquals("run-abcdef12-345", draft.sampleKey());
    assertEquals("把排序网页做出来", draft.prompt());
    assertEquals("RUN_STATUS_MATCH", draft.assertions().getFirst().get("type"));
    assertEquals("COMPLETED", draft.assertions().getFirst().get("expected"));
    assertEquals("OUTPUT_CONTAINS", draft.assertions().get(1).get("type"));
    assertEquals(runId, draft.setup().get("sourceRunId"));
    assertEquals("RUN_DISTILLED", draft.setup().get("source"));
    assertTrue(draft.limitations().stream().anyMatch(item -> item.contains("不会进入评测数据集")));
    org.mockito.Mockito.verify(datasets, org.mockito.Mockito.never()).create(any());
    org.mockito.Mockito.verify(datasets, org.mockito.Mockito.never())
        .upsertSample(anyString(), anyString(), any());
  }

  @Test
  void exportWritesThroughTheExistingSampleContract() {
    var exported = service.export(runId);
    assertEquals("ds-1", exported.datasetId());
    var datasetCaptor =
        org.mockito.ArgumentCaptor.forClass(EvalCustomDatasetService.DatasetInput.class);
    verify(datasets).create(datasetCaptor.capture());
    assertEquals(
        dev.workbench.framework.evaluation.EvalCategory.CODING_REFLECTION,
        dev.workbench.framework.evaluation.EvalCategory.parse(
            datasetCaptor.getValue().category()));
    verify(datasets)
        .upsertSample(
            eq("ds-1"),
            eq("run-abcdef12-345"),
            org.mockito.ArgumentMatchers.any(EvalCustomDatasetService.SampleInput.class));
    assertEquals("0 题", exported.total());
  }

  @Test
  void withoutFinalOutputOnlyTheStatusAssertionIsDistilled() {
    when(agents.agents(runId)).thenReturn(List.of());
    var draft = service.preview(runId);
    assertEquals(1, draft.assertions().size());
    assertTrue(draft.limitations().stream().anyMatch(item -> item.contains("没有保存最终输出")));
  }

  @Test
  void existingDatasetIsReusedInsteadOfCreatingANewOne() {
    when(datasets.list())
        .thenReturn(
            List.of(
                new EvalCustomDatasetService.DatasetView(
                    "ds-existing", "run-distilled", "旧名", "CODE", "", 3, "now", "now")));
    var draft = service.preview(runId);
    assertEquals("ds-existing", draft.datasetId());
    org.mockito.Mockito.verify(datasets, org.mockito.Mockito.never()).create(any());
  }
}
