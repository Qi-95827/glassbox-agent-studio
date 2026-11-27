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
    // 终态断言优先于叙述式输出断言：重跑时状态与终态一定可核对。
    assertEquals("RUN_TERMINAL", draft.assertions().get(1).get("type"));
    assertEquals("OUTPUT_CONTAINS", draft.assertions().get(2).get("type"));
    assertEquals(runId, draft.setup().get("sourceRunId"));
    assertEquals("RUN_DISTILLED", draft.setup().get("source"));
    assertEquals(false, draft.setup().get("workspaceWrite"));
    assertTrue(draft.limitations().stream().anyMatch(item -> item.contains("不会进入评测数据集")));
    org.mockito.Mockito.verify(datasets, org.mockito.Mockito.never()).create(any());
    org.mockito.Mockito.verify(datasets, org.mockito.Mockito.never())
        .upsertSample(anyString(), anyString(), any());
  }

  @Test
  void workspaceWriteRunsNeverGetANarrativeOutputAssertion() {
    // 真实写工具成功：产物是文件正文，不能用上游分析叙述去做 OUTPUT_CONTAINS（否则重跑必失败）。
    when(agents.tools(runId))
        .thenReturn(
            List.of(
                Map.of("ID", "t1", "TOOL_NAME", "write_authorized_file", "STATUS", "COMPLETED"),
                Map.of("ID", "t2", "TOOL_NAME", "read_authorized_file", "STATUS", "COMPLETED")));

    var draft = service.preview(runId);

    assertEquals(2, draft.assertions().size());
    assertEquals("RUN_STATUS_MATCH", draft.assertions().get(0).get("type"));
    assertEquals("RUN_TERMINAL", draft.assertions().get(1).get("type"));
    assertTrue(
        draft.assertions().stream().noneMatch(row -> "OUTPUT_CONTAINS".equals(row.get("type"))),
        "含文件写入的运行不得生成叙述式包含断言：" + draft.assertions());
    assertEquals(true, draft.setup().get("workspaceWrite"));
    assertTrue(draft.limitations().stream().anyMatch(item -> item.contains("产物是授权文件正文")));
  }

  @Test
  void distilledAssertionsPassAgainstMatchingRealEvidence() {
    var draft = service.preview(runId);
    var grader = new dev.workbench.evaluation.grader.CodeAssertionGrader();
    var evidence = new java.util.LinkedHashMap<String, Object>();
    evidence.put("runStatus", "COMPLETED");
    evidence.put("terminal", true);
    evidence.put("rawOutput", "排序完成，包含 3 个闲等节点");

    var graded =
        grader.grade(
            draft.assertions(),
            new dev.workbench.framework.evaluation.EvalCaseExecution(
                evidence, Map.of(), Map.of(), runId, "排序完成，包含 3 个闲等节点", 1, 0, 0, 0));

    assertTrue(graded.passed(), graded.assertions().toString());
    assertEquals(draft.assertions().size(), graded.assertions().size());
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
  void withoutFinalOutputOnlyTheTerminalAssertionsAreDistilled() {
    when(agents.agents(runId)).thenReturn(List.of());
    var draft = service.preview(runId);
    assertEquals(2, draft.assertions().size());
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
