package dev.workbench.domain;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.store.RunRecordService;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import dev.workbench.planning.EntryPolicyService;
import dev.workbench.store.RunRecordService;

/** 任务 8 第一段：四类入口校验、交付物、默认验收条件和持久化。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task8entries;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task8-entries"
    })
class Task8EntryPolicyIntegrationTest {
  @Autowired EntryPolicyService policies;
  @Autowired RunService runs;
  @Autowired RunRecordService records;
  @Autowired WorkspaceService workspaces;
  @TempDir Path temp;

  @Test
  void fourEntryPoliciesExposeDistinctDeliverablesAndAcceptance() {
    var all = policies.all();
    assertEquals(4, all.size());
    assertEquals(
        java.util.Set.of("REQUIREMENT", "DESIGN", "CODE", "TEST"),
        all.stream().map(EntryPolicyService.EntryPolicy::kind).collect(java.util.stream.Collectors.toSet()));
    assertTrue(all.stream().allMatch(policy -> !policy.deliverables().isEmpty()));
    assertTrue(all.stream().allMatch(policy -> !policy.defaultAcceptance().isEmpty()));
  }

  @Test
  void codeAndTestEntriesRejectMissingWorkspaceAuthorization() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            policies.validate(
                TaskEntryKind.CODE, "实现登录", null, true, List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            policies.validate(
                TaskEntryKind.CODE, "实现登录", "workspace", false, List.of("a.py"), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            policies.validate(
                TaskEntryKind.TEST, "运行回归", null, false, List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            policies.validate(
                TaskEntryKind.TEST, "运行回归", "workspace", false, List.of(), List.of()));
  }

  @Test
  void eachEntryPersistsEffectiveAcceptanceAndDeliverables() throws Exception {
    var workspace = workspace();

    String requirement = create("梳理登录需求", TaskEntryKind.REQUIREMENT, null, false, List.of(), null, false);
    String design = create("设计登录模块", TaskEntryKind.DESIGN, null, false, List.of(), null, false);
    String code =
        create(
            "实现登录模块",
            TaskEntryKind.CODE,
            workspace.id(),
            true,
            List.of("calculator.py"),
            "workspace-change",
            true);
    String test =
        create(
            "验证登录模块",
            TaskEntryKind.TEST,
            workspace.id(),
            false,
            List.of("calculator.py"),
            "workspace-analysis",
            true);

    for (String runId : List.of(requirement, design, code, test)) {
      String taskId = records.runSnapshot(runId).taskId().toString();
      var saved = policies.find(taskId);
      assertNotNull(saved, "入口策略未持久化：" + runId);
      assertTrue(!saved.get("DELIVERABLES_JSON").toString().isBlank());
      assertEquals(EntryPolicyService.POLICY_VERSION, saved.get("POLICY_VERSION"));
    }
    String codeTaskId = records.runSnapshot(code).taskId().toString();
    assertTrue(
        records.taskDefinition(codeTaskId).acceptanceCriteria().stream()
            .anyMatch(item -> item.contains("授权范围内相关验证通过")),
        String.valueOf(records.taskDefinition(codeTaskId).acceptanceCriteria()));
  }

  private String create(
      String goal,
      TaskEntryKind kind,
      String workspaceId,
      boolean write,
      List<String> files,
      String template,
      boolean reviewPlan) {
    return runs.create(
        new CreateTaskRequest(
            goal,
            "normal",
            4,
            2,
            30,
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
            template,
            reviewPlan,
            null));
  }

  private WorkspaceService.Workspace workspace() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo-" + java.util.UUID.randomUUID()));
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
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, p.waitFor(), output);
  }
}
