package dev.workbench.domain;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.planning.PlanService;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.store.RunRecordService;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import dev.workbench.planning.EntryRouteService;
import dev.workbench.store.RunRecordService;

/** 四类入口各走一次真实创建与规则选择的执行模板，核对各自的计划与产物边界。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task8entriesrun;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task8-entries-run"
    })
class Task8EntryExecutionIntegrationTest {
  @Autowired RunService runs;
  @Autowired RunRecordService records;
  @Autowired EntryRouteService routes;
  @Autowired PlanService plans;
  @Autowired WorkspaceService workspaces;
  @TempDir Path temp;

  @Test
  void requirementAndDesignProduceDocumentPlans() {
    String requirement = create("梳理需求入口产物", TaskEntryKind.REQUIREMENT, null, false, List.of(), 4, 2);
    waitTerminal(requirement);
    assertCompleted(requirement);
    assertEquals(EntryRouteService.REQUIREMENT_TEMPLATE, route(requirement));
    assertPlanLabels(requirement, List.of("需求", "缺失信息", "需求说明"));
    assertFalse(records.run(requirement).get("RESULT").toString().isBlank());

    String design = create("设计入口产物", TaskEntryKind.DESIGN, null, false, List.of(), 4, 2);
    waitTerminal(design);
    assertCompleted(design);
    assertEquals(EntryRouteService.DESIGN_TEMPLATE, route(design));
    assertPlanLabels(design, List.of("模块边界", "失败路径", "概要设计"));
  }

  @Test
  void codeAndTestEntriesRunAuthorizedWorkspacePlans() throws Exception {
    var workspace = workspace();
    String code =
        create(
            "实现授权文件中的小改动",
            TaskEntryKind.CODE,
            workspace.id(),
            true,
            List.of("calculator.py"),
            6,
            4);
    waitTerminal(code);
    assertEquals("BLOCKED", records.runSnapshot(code).status().name());
    assertTrue(
        plans.find(code).stream()
            .allMatch(node -> node.status() == PlanNodeStatus.COMPLETED));
    assertEquals(EntryRouteService.CODE_TEMPLATE, route(code));
    assertPlanLabels(code, List.of("分析授权文件", "按授权修改", "独立审查"));
    assertNotNull(records.run(code).get("RESULT"));

    String test =
        create(
            "核对测试覆盖与证据",
            TaskEntryKind.TEST,
            workspace.id(),
            false,
            List.of("calculator.py"),
            6,
            3);
    waitTerminal(test);
    assertCompleted(test);
    assertEquals(EntryRouteService.TEST_TEMPLATE, route(test));
    assertTrue(
        plans.find(test).stream()
            .anyMatch(node -> node.systemCheck() && node.status() == PlanNodeStatus.COMPLETED),
        "测试入口必须完成授权读取证据检查");
  }

  private String route(String runId) {
    String taskId = records.runSnapshot(runId).taskId().toString();
    return String.valueOf(routes.find(taskId).get("TEMPLATE_ID"));
  }

  private void assertPlanLabels(String runId, List<String> expected) {
    String labels = plans.find(runId).stream().map(node -> node.label()).toList().toString();
    for (String item : expected) assertTrue(labels.contains(item), labels);
  }

  private void assertCompleted(String runId) {
    assertEquals("COMPLETED", records.runSnapshot(runId).status().name());
    assertTrue(
        plans.find(runId).stream()
            .allMatch(
                node ->
                    node.status() == PlanNodeStatus.COMPLETED
                        || node.status() == PlanNodeStatus.REUSED));
  }

  private String create(
      String goal,
      TaskEntryKind kind,
      String workspaceId,
      boolean write,
      List<String> files,
      int rounds,
      int tools) {
    return runs.create(
        new CreateTaskRequest(
            goal,
            "normal",
            rounds,
            tools,
            60,
            "mock",
            workspaceId,
            null,
            write,
            null,
            false,
            null,
            List.of(),
            List.of(),
            null,
            null,
            kind,
            null,
            null,
            files.isEmpty() ? null : files,
            null,
            null,
            null,
            false,
            null));
  }

  private WorkspaceService.Workspace workspace() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo"));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(repo.resolve("calculator.py"), "def add(a,b):\n    return a+b\n", StandardCharsets.UTF_8);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "fixture");
    return workspaces.create(repo.toString(), "main");
  }

  private void git(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.add("-C");
    command.add(dir.toString());
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }

  private void waitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (records.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline) fail("运行未结束：" + runId);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
  }
}
