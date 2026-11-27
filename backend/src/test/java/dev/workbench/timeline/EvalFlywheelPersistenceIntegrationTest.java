package dev.workbench.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.evaluation.EvalCustomDatasetService;
import dev.workbench.evaluation.grader.CodeAssertionGrader;
import dev.workbench.framework.evaluation.EvalCaseExecution;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunRecordService;
import dev.workbench.store.TaskRecordService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Eval 飞轮端到端：飞轮产出的断言真实落库到 EvalCustomDatasetService，再交给 CodeAssertionGrader 判定。
 *
 * <p>回归：旧实现把上游 Agent 的叙述当作代码文件的 OUTPUT_CONTAINS，重跑必然失败。这里用同一份真实写入的
 * 数据集验证：纯文本运行的三条断言在匹配证据下全部通过；含文件写入的运行只沉淀终态断言，同样通过。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:eval_flywheel_e2e;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-eval-flywheel"
    })
class EvalFlywheelPersistenceIntegrationTest {
  @Autowired EvalCustomDatasetService datasets;

  private final RunRecordService records = mock(RunRecordService.class);
  private final TaskRecordService tasks = mock(TaskRecordService.class);
  private final AgentAssemblyRecordService agents = mock(AgentAssemblyRecordService.class);
  private final String runId = UUID.randomUUID().toString();
  private final UUID taskId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    when(records.runSnapshot(runId))
        .thenReturn(
            new RunSnapshot(
                UUID.fromString(runId), taskId, null, RunStatus.COMPLETED, 1, 1, 12, 12, 6, null, 3,
                240, 1000L, 10, 10));
    when(tasks.taskDefinition(taskId.toString()))
        .thenReturn(
            new TaskDefinition(
                taskId,
                "把排序网页做出来",
                Scenario.NORMAL,
                ModelMode.MOCK,
                "ws-e2e",
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
    // 同一内存库内两个用例都会确保默认沉淀数据集存在：先清掉上一用例留下的同名集，避免编码冲突。
    for (var view : datasets.list()) {
      if (EvalFlywheelService.DEFAULT_DATASET_CODE.equalsIgnoreCase(view.code()))
        datasets.delete(view.id());
    }
  }

  @Test
  void textRunAssertionsArePersistedAndPassAgainstMatchingEvidence() {
    when(agents.agents(runId))
        .thenReturn(List.of(Map.of("ID", "a1", "RESULT", "排序完成，包含 3 个闲等节点")));
    var flywheel = new EvalFlywheelService(records, tasks, agents, datasets);

    var exported = flywheel.export(runId);
    var samples = datasets.samples(exported.datasetId());

    assertEquals(1, samples.size());
    var persisted = samples.getFirst().assertions();
    assertEquals(3, persisted.size(), persisted.toString());
    assertEquals("RUN_STATUS_MATCH", persisted.get(0).get("type"));
    assertEquals("RUN_TERMINAL", persisted.get(1).get("type"));
    assertEquals("OUTPUT_CONTAINS", persisted.get(2).get("type"));

    var evidence = new java.util.LinkedHashMap<String, Object>();
    evidence.put("runStatus", "COMPLETED");
    evidence.put("terminal", true);
    evidence.put("rawOutput", "排序完成，包含 3 个闲等节点");
    var graded =
        new CodeAssertionGrader()
            .grade(
                persisted,
                new EvalCaseExecution(
                    evidence, Map.of(), Map.of(), runId, "排序完成，包含 3 个闲等节点", 1, 0, 0, 0));
    assertTrue(graded.passed(), graded.assertions().toString());
  }

  @Test
  void workspaceWriteRunPersistsOnlyTerminalAssertionsAndStillPasses() {
    when(agents.agents(runId))
        .thenReturn(List.of(Map.of("ID", "a1", "RESULT", "已修改授权文件并补充说明")));
    when(agents.tools(runId))
        .thenReturn(
            List.of(Map.of("ID", "t1", "TOOL_NAME", "write_authorized_file", "STATUS", "COMPLETED")));
    var flywheel = new EvalFlywheelService(records, tasks, agents, datasets);

    var exported = flywheel.export(runId);
    var persisted = datasets.samples(exported.datasetId()).getFirst().assertions();

    assertEquals(2, persisted.size(), persisted.toString());
    assertTrue(persisted.stream().noneMatch(row -> "OUTPUT_CONTAINS".equals(row.get("type"))));

    // 重跑后产物是代码文件正文：不含上游叙述；终态断言仍应通过，证明旧口径的 100% 误判已消除。
    var evidence = new java.util.LinkedHashMap<String, Object>();
    evidence.put("runStatus", "COMPLETED");
    evidence.put("terminal", true);
    evidence.put("rawOutput", "def sort(xs):\n    return sorted(xs)\n");
    var graded =
        new CodeAssertionGrader()
            .grade(
                persisted,
                new EvalCaseExecution(
                    evidence,
                    Map.of(),
                    Map.of(),
                    runId,
                    "def sort(xs):\n    return sorted(xs)\n",
                    1,
                    0,
                    0,
                    0));
    assertTrue(graded.passed(), graded.assertions().toString());
  }
}
