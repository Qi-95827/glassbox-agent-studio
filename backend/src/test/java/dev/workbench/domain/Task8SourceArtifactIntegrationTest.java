package dev.workbench.domain;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.planning.PlanService;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.store.RunRecordService;
import dev.workbench.planning.SourceArtifactService;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import dev.workbench.store.RunRecordService;

/** 既有需求文档接续设计任务：授权正文进入输入，结束运行拒绝再接成果。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task8source;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task8-source"
    })
class Task8SourceArtifactIntegrationTest {
  @Autowired RunService runs;
  @Autowired RunRecordService records;
  @Autowired SourceArtifactService sources;
  @Autowired WorkspaceService workspaces;
  @Autowired PlanService plans;
  @TempDir Path temp;

  @Test
  void authorizedRequirementDocumentIsCarriedIntoPendingDesignAndTraceable() throws Exception {
    var workspace = workspace();
    String source =
        create(
            "整理需求文档",
            TaskEntryKind.REQUIREMENT,
            workspace.id(),
            List.of("requirements.md"),
            false);
    waitTerminal(source);
    String sourceTask = records.runSnapshot(source).taskId().toString();

    String target =
        create(
            "根据需求文档设计登录模块",
            TaskEntryKind.DESIGN,
            workspace.id(),
            List.of("requirements.md"),
            true);
    String targetTask = records.runSnapshot(target).taskId().toString();
    var artifact =
        sources.attach(
            target,
            records.taskVersion(targetTask),
            source,
            SourceArtifactService.Kind.DOCUMENT,
            records.taskVersion(sourceTask));
    assertTrue(artifact.summary().contains("来源文档"));
    assertTrue(artifact.contentPreview().contains("需求正文：登录必须记录失败次数"));
    assertTrue(
        sources.constraints(targetTask).stream()
            .anyMatch(item -> item.contains("需求正文：登录必须记录失败次数")),
        "设计节点输入必须包含已授权需求正文");
    String input = sources.constraints(targetTask).getFirst();
    String body = input.substring(input.indexOf("内容：\n") + 4);
    assertEquals(artifact.contentPreview(), body, "接入预览与节点输入正文应一致");
    sources.requireCurrent(targetTask);
    assertEquals(1, sources.find(targetTask).size());

    // 计划批准后设计任务可运行，来源成果在运行详情的来源记录中可追溯。
    runs.approvePlan(target, plans.workflowPlan(target).id());
    waitTerminal(target);
    assertTrue(
        List.of("COMPLETED", "BLOCKED").contains(records.runSnapshot(target).status().name()));
    assertTrue(records.run(target).get("RESULT").toString().contains("模拟"));
  }

  @Test
  void endedTargetRejectsNewArtifact() throws Exception {
    var workspace = workspace();
    String source =
        create("来源需求", TaskEntryKind.REQUIREMENT, workspace.id(), List.of("requirements.md"), false);
    waitTerminal(source);
    String target =
        create("已取消设计", TaskEntryKind.DESIGN, workspace.id(), List.of("requirements.md"), true);
    String targetTask = records.runSnapshot(target).taskId().toString();
    runs.cancel(target);
    assertThrows(
        IllegalStateException.class,
        () ->
            sources.attach(
                target,
                records.taskVersion(targetTask),
                source,
                SourceArtifactService.Kind.DOCUMENT,
                records.taskVersion(records.runSnapshot(source).taskId().toString())));
  }

  private String create(
      String goal,
      TaskEntryKind kind,
      String workspaceId,
      List<String> files,
      boolean reviewPlan) {
    return runs.create(
        new CreateTaskRequest(
            goal,
            "normal",
            4,
            2,
            60,
            "mock",
            workspaceId,
            null,
            false,
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
            files,
            null,
            null,
            null,
            reviewPlan,
            null));
  }

  private WorkspaceService.Workspace workspace() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo"));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(
        repo.resolve("requirements.md"),
        "需求正文：登录必须记录失败次数，并在连续失败后锁定账户。\n",
        StandardCharsets.UTF_8);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "requirement");
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
