package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.planning.PlanService;
import dev.workbench.store.RunRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.workspace.WorkspaceService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 收口包 G-2：未显式指定模板的新建任务必须 100% 物化为标准内置工作流并真实执行完成。
 *
 * <p>覆盖四条推导路径：纯文本（general-analysis-review）、只读工作区（workspace-analysis）、写授权工作区
 * （workspace-change）与模拟 Bug（bug-fix）。任一路径若落回「无计划运行」，这里都会失败。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:standard_workflow_materialization;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-materialization"
    })
class StandardWorkflowMaterializationTest {
  @Autowired RunService runs;
  @Autowired RunStateStore states;
  @Autowired RunRecordService records;
  @Autowired PlanService plans;
  @Autowired WorkspaceService workspaces;

  @Test
  void plainTextTaskMaterializesAnalysisReviewWorkflowAndCompletes() {
    String runId = runs.create(CreateTaskRequest.builder().goal("整理发布说明").scenario("normal").limits(4, 2, 30).modelMode("mock").build());
    assertEquals("COMPLETED", await(runId).get("STATUS"));
    var plan = plans.workflowPlan(runId);
    assertNotNull(plan, "纯文本任务必须物化标准工作流计划");
    assertEquals("general-analysis-review", plan.templateId());
    assertEquals(
        List.of("ANALYST", "REVIEWER"),
        plans.find(runId).stream().map(node -> node.key()).toList());
  }

  @Test
  void authorizedWorkspaceTaskMaterializesWorkspaceWorkflowAndCompletes() {
    String workspaceId = workspaces.createIdeaWorkspace(List.of("sort.js")).id();
    String readOnly =
        runs.create(
            CreateTaskRequest.builder()
                .goal("分析排序脚本")
                .scenario("normal")
                .limits(6, 2, 30)
                .modelMode("mock")
                .workspace(workspaceId, null)
                .fileScopes(List.of("sort.js"))
                .build());
    assertEquals("COMPLETED", await(readOnly).get("STATUS"));
    assertEquals("workspace-analysis", plans.workflowPlan(readOnly).templateId());

    String write =
        runs.create(
            CreateTaskRequest.builder()
                .goal("修改排序脚本")
                .scenario("normal")
                .limits(6, 3, 30)
                .modelMode("mock")
                .workspace(workspaceId, null)
                .fileScopes(List.of("sort.js"))
                .writeEnabled(true)
                .build());
    var finished = await(write);
    // 模拟模型从不写文件：标准工作流按真实证据诚实阻断，而不是伪造写入或直接报告成功。
    assertEquals("BLOCKED", finished.get("STATUS"));
    assertEquals("OUTPUT_MISSING", finished.get("STOP_CAUSE"));
    assertEquals("workspace-change", plans.workflowPlan(write).templateId());
  }

  @Test
  void boundBugTaskMaterializesBugFixWorkflow() {
    String workspaceId = workspaces.createIdeaWorkspace(List.of("calculator.py")).id();
    String bugRun =
        runs.create(
            CreateTaskRequest.builder()
                .goal("复现并修复模拟 Bug")
                .scenario("normal")
                .limits(6, 3, 30)
                .modelMode("mock")
                .workspace(workspaceId, null)
                .fileScopes(List.of("calculator.py"))
                .writeEnabled(true)
                .bugId("MOCK-PY-001")
                .entryKind(TaskEntryKind.BUG)
                .build());
    await(bugRun);
    var plan = plans.workflowPlan(bugRun);
    assertNotNull(plan, "模拟 Bug 任务必须物化 bug-fix 工作流计划");
    assertEquals("bug-fix", plan.templateId());
    assertTrue(
        plans.find(bugRun).stream().anyMatch(node -> node.systemCheck()),
        "bug-fix 工作流必须包含复现/回归系统检查节点");
  }

  /** 轮询到终态，避免在异步派发尚未结束时断言。 */
  private Map<String, Object> await(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (true) {
      var run = records.run(runId);
      if (!RunStatus.valueOf(String.valueOf(run.get("STATUS"))).canCancel()) return run;
      if (System.nanoTime() >= deadline) throw new AssertionError("运行未结束：" + runId);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("等待被中断");
      }
    }
  }
}
