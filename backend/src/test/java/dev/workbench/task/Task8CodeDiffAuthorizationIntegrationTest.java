package dev.workbench.task;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.tool.ResourceVersion;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
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

/** 代码差异只绑定来源运行实际写过的授权文件，预览和装配前复验当前授权。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task8codediff;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task8-codediff"
    })
class Task8CodeDiffAuthorizationIntegrationTest {
  @Autowired RunService runs;
  @Autowired RunRecordService records;
  @Autowired SourceArtifactService sources;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceAuthorizationService authorization;
  @Autowired dev.workbench.framework.workflow.plan.PlanService plans;
  @TempDir Path temp;

  @Test
  void literalPathspecDoesNotMatchSiblingFileWithBrackets() throws Exception {
    var workspace = workspace();
    String source =
        create(
            "带方括号的来源修改",
            TaskEntryKind.CODE,
            workspace.id(),
            true,
            List.of("src/allowed[1].txt"),
            List.of(),
            List.of(),
            false);
    waitTerminal(source);
    String sourceTask = records.runSnapshot(source).taskId().toString();
    var literal = workspaces.read(workspace.id(), "src/allowed[1].txt");
    workspaces.write(workspace.id(), "src/allowed[1].txt", literal.sha256(), "literal-v2\n");
    var sibling = workspaces.read(workspace.id(), "src/allowed1.txt");
    workspaces.write(workspace.id(), "src/allowed1.txt", sibling.sha256(), "sibling-v2\n");
    recordWrite(source, Map.of("src/allowed[1].txt", workspaces.read(workspace.id(), "src/allowed[1].txt").sha256()));
    String target =
        create(
            "只接续字面授权文件",
            TaskEntryKind.CODE,
            workspace.id(),
            true,
            List.of("src/allowed[1].txt"),
            List.of(),
            List.of(),
            true);
    String targetTask = records.runSnapshot(target).taskId().toString();
    var artifact =
        sources.attach(
            target,
            records.taskVersion(targetTask),
            source,
            SourceArtifactService.Kind.CODE_DIFF,
            records.taskVersion(sourceTask));
    assertTrue(artifact.contentPreview().contains("literal-v2"));
    assertFalse(artifact.contentPreview().contains("sibling-v2"));
    assertTrue(sources.constraints(targetTask).stream().noneMatch(item -> item.contains("sibling-v2")));
  }

  @Test
  void readOnlyTestTargetCanContinueFromCodeDiffWithoutWriteTools() throws Exception {
    var workspace = workspace();
    String source =
        create(
            "来源代码差异", TaskEntryKind.CODE, workspace.id(), true,
            List.of("src/allowed.txt"), List.of("src"), List.of(), false);
    waitTerminal(source);
    String sourceTask = records.runSnapshot(source).taskId().toString();
    var allowed = workspaces.read(workspace.id(), "src/allowed.txt");
    workspaces.write(workspace.id(), "src/allowed.txt", allowed.sha256(), "allowed-for-test\n");
    recordWrite(source, Map.of("src/allowed.txt", workspaces.read(workspace.id(), "src/allowed.txt").sha256()));

    String target =
        create(
            "只读测试接续代码差异", TaskEntryKind.TEST, workspace.id(), false,
            List.of("src/allowed.txt"), List.of(), List.of(), true);
    String targetTask = records.runSnapshot(target).taskId().toString();
    assertFalse(records.taskDefinition(targetTask).writeEnabled());
    var artifact =
        sources.attach(
            target,
            records.taskVersion(targetTask),
            source,
            SourceArtifactService.Kind.CODE_DIFF,
            records.taskVersion(sourceTask));
    assertTrue(artifact.contentPreview().contains("allowed-for-test"));
    var plan = plans.workflowPlan(target);
    assertEquals(
        dev.workbench.framework.workflow.plan.WorkflowTemplate.Scope.WORKSPACE_READ_ONLY,
        plan.scope());
    runs.approvePlan(target, plan.id());
    waitTerminal(target);
    assertEquals("COMPLETED", records.runSnapshot(target).status().name());
    assertTrue(
        records.tools(target).stream()
            .noneMatch(
                tool ->
                    ToolKind.WORKSPACE_WRITE
                        .recordName()
                        .equals(String.valueOf(tool.get("TOOL_NAME")))),
        "只读测试任务不得获得写入工具调用");
  }

  private void recordWrite(String runId, Map<String, String> versions) {
    String agentId = records.agents(runId).stream().findFirst().orElseThrow().get("ID").toString();
    String toolId = RunRecordService.id();
    records.startTool(toolId, runId, agentId, ToolKind.WORKSPACE_WRITE.recordName(), 1);
    records.finishTool(
        toolId,
        InvocationStatus.COMPLETED,
        "已修改授权文件",
        null,
        1,
        ResourceVersion.encode(versions));
  }

  @Test
  void codeDiffUsesOnlySourceRunWritesAndRevalidatesAuthorization() throws Exception {
    var workspace = workspace();
    String source =
        create(
            "来源代码修改",
            TaskEntryKind.CODE,
            workspace.id(),
            true,
            List.of("src/allowed.txt"),
            List.of("src"),
            List.of(),
            false);
    waitTerminal(source);
    String sourceTask = records.runSnapshot(source).taskId().toString();

    // 模拟来源运行实际写入 allowed.txt，并登记该次写入版本；restricted.txt 的后续改动不在证据内。
    var allowed = workspaces.read(workspace.id(), "src/allowed.txt");
    workspaces.write(workspace.id(), "src/allowed.txt", allowed.sha256(), "allowed-v2\n");
    String allowedSha = workspaces.read(workspace.id(), "src/allowed.txt").sha256();
    String agentId = records.agents(source).stream().findFirst().orElseThrow().get("ID").toString();
    String toolId = RunRecordService.id();
    records.startTool(toolId, source, agentId, ToolKind.WORKSPACE_WRITE.recordName(), 1);
    records.finishTool(
        toolId,
        InvocationStatus.COMPLETED,
        "已修改 authorized file",
        null,
        1,
        ResourceVersion.encode(Map.of("src/allowed.txt", allowedSha)));
    var restricted = workspaces.read(workspace.id(), "src/restricted.txt");
    workspaces.write(workspace.id(), "src/restricted.txt", restricted.sha256(), "restricted-secret\n");

    String target =
        create(
            "设计接续代码差异",
            TaskEntryKind.CODE,
            workspace.id(),
            true,
            List.of("outside.txt"),
            List.of("src"),
            List.of(),
            true);
    String targetTask = records.runSnapshot(target).taskId().toString();
    var artifact =
        sources.attach(
            target,
            records.taskVersion(targetTask),
            source,
            SourceArtifactService.Kind.CODE_DIFF,
            records.taskVersion(sourceTask));
    assertTrue(artifact.contentPreview().contains("allowed-v2"));
    assertFalse(artifact.contentPreview().contains("restricted-secret"));
    assertTrue(sources.constraints(targetTask).stream().noneMatch(item -> item.contains("restricted-secret")));

    // 接入后撤掉目录，预览隐藏正文，装配输入拒绝。
    authorization.revoke(targetTask, "src");
    var hidden = sources.find(targetTask).getFirst();
    assertFalse(hidden.contentAvailable());
    assertFalse(hidden.contentPreview().contains("allowed-v2"));
    assertThrows(IllegalStateException.class, () -> sources.constraints(targetTask));
  }

  @Test
  void unrelatedFileWriteEvidenceCannotBypassTargetAuthorization() throws Exception {
    var workspace = workspace();
    String source =
        create(
            "来源越权差异",
            TaskEntryKind.CODE,
            workspace.id(),
            true,
            List.of("src/allowed.txt"),
            List.of("src"),
            List.of(),
            false);
    waitTerminal(source);
    String sourceTask = records.runSnapshot(source).taskId().toString();
    var restricted = workspaces.read(workspace.id(), "src/restricted.txt");
    String agentId = records.agents(source).stream().findFirst().orElseThrow().get("ID").toString();
    String toolId = RunRecordService.id();
    records.startTool(toolId, source, agentId, ToolKind.WORKSPACE_WRITE.recordName(), 1);
    records.finishTool(
        toolId,
        InvocationStatus.COMPLETED,
        "越权写入",
        null,
        1,
        ResourceVersion.encode(Map.of("src/restricted.txt", restricted.sha256())));
    String target =
        create(
            "仅授权 allowed",
            TaskEntryKind.CODE,
            workspace.id(),
            true,
            List.of("src/allowed.txt"),
            List.of(),
            List.of(),
            true);
    String targetTask = records.runSnapshot(target).taskId().toString();
    assertThrows(
        IllegalStateException.class,
        () ->
            sources.attach(
                target,
                records.taskVersion(targetTask),
                source,
                SourceArtifactService.Kind.CODE_DIFF,
                records.taskVersion(sourceTask)));
  }

  private String create(
      String goal,
      TaskEntryKind kind,
      String workspaceId,
      boolean write,
      List<String> files,
      List<String> directories,
      List<String> exclusions,
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
            reviewPlan,
            null,
            directories,
            exclusions,
            null));
  }

  private WorkspaceService.Workspace workspace() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo"));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.createDirectories(repo.resolve("src"));
    Files.writeString(repo.resolve("src/allowed.txt"), "allowed-v1\n", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve("src/restricted.txt"), "restricted-v1\n", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve("src/allowed[1].txt"), "literal-v1\n", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve("src/allowed1.txt"), "sibling-v1\n", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve("outside.txt"), "outside\n", StandardCharsets.UTF_8);
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
      if (System.nanoTime() > deadline)
        fail("运行未结束：" + runId + "；状态 " + records.runSnapshot(runId).status());
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
  }
}
