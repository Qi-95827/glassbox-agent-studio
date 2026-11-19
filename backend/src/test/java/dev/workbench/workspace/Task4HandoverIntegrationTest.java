package dev.workbench.workspace;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.workflow.view.ExecutionViewService;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.task.RunRecordService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 暂停后人工接手：记录版本、交还后旧审批失效、流程图显示接手状态。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task4handover;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task4-handover"
    })
class Task4HandoverIntegrationTest {
  @Autowired RunService runs;  @Autowired RunRecordService records;
  @Autowired WorkspaceService workspaces;
  @Autowired ApprovalService approvals;
  @Autowired RunHandoverService handovers;
  @Autowired ExecutionViewService views;
  @TempDir Path temp;

  @Test
  void takeoverInvalidatesOldApprovalWhenWorkspaceChanges() throws Exception {
    var workspace = workspace();
    String runId = createPausedTask(workspace.id());
    var file = workspaces.read(workspace.id(), "calculator.py");
    workspaces.write(
        workspace.id(),
        "calculator.py",
        file.sha256(),
        "def add(a,b):\n    return a-b+1\n");
    var approval = approvals.propose(workspace.id());
    approvals.decide(approval.get("ID").toString(), ApprovalService.Decision.APPROVE);

    var taken = handovers.take(runId, "用户检查代码");
    assertEquals("ACTIVE", taken.status());
    assertFalse(taken.evidenceInvalidated());
    assertThrows(IllegalStateException.class, () -> runs.resume(runId));
    assertThrows(IllegalStateException.class, () -> handovers.take(runId, "重复接手"));

    var edited = workspaces.read(workspace.id(), "calculator.py");
    workspaces.write(
        workspace.id(),
        "calculator.py",
        edited.sha256(),
        "def add(a,b):\n    return a+b\n");
    var returned = handovers.returnControl(runId, "修改完成");
    assertEquals("RETURNED", returned.status());
    assertTrue(returned.evidenceInvalidated());
    assertNotEquals(returned.baselineFingerprint(), returned.currentFingerprint());
    assertThrows(IllegalStateException.class, () -> runs.resume(runId));

    var after = approvals.list(workspace.id());
    assertEquals("STALE", after.getFirst().get("STATUS"));
    var view = views.view(runId);
    assertNotNull(view.handover());
    assertTrue(view.handover().evidenceInvalidated());
    assertTrue(
        view.availableActions().stream()
            .anyMatch(
                action ->
                    "RESUME".equals(action.actionCode())
                        && !action.enabled()
                        && action.disabledReason() != null
                        && action.disabledReason().contains("重新验证")));

    // 再次接手但不修改，不能清除上一次代码变化留下的重新验证要求。
    handovers.take(runId, "再次接手");
    var secondReturn = handovers.returnControl(runId, "再次交还但未修改");
    assertFalse(secondReturn.evidenceInvalidated());
    assertTrue(handovers.requiresRevalidation(runId));
    assertThrows(IllegalStateException.class, () -> runs.resume(runId));
    assertTrue(
        views.view(runId).availableActions().stream()
            .anyMatch(
                action ->
                    "RESUME".equals(action.actionCode())
                        && !action.enabled()
                        && action.disabledReason().contains("重新验证")));
    assertEquals("PAUSED", view.runStatus());
    assertTrue(
        records.events(runId).stream()
            .anyMatch(event -> "HANDOVER_TAKEN".equals(event.get("EVENT_TYPE"))));
    assertTrue(
        records.events(runId).stream()
            .anyMatch(event -> "HANDOVER_RETURNED".equals(event.get("EVENT_TYPE"))));
  }

  @Test
  void unchangedReturnKeepsApprovalUsable() throws Exception {
    var workspace = workspace();
    String runId = createPausedTask(workspace.id());
    var file = workspaces.read(workspace.id(), "calculator.py");
    workspaces.write(
        workspace.id(),
        "calculator.py",
        file.sha256(),
        "def add(a,b):\n    return a-b+2\n");
    var approval = approvals.propose(workspace.id());
    approvals.decide(approval.get("ID").toString(), ApprovalService.Decision.APPROVE);
    handovers.take(runId, "只读检查");
    var returned = handovers.returnControl(runId, "未修改");
    assertFalse(returned.evidenceInvalidated());
    assertDoesNotThrow(() -> runs.resume(runId));
    assertEquals("APPROVED", approvals.list(workspace.id()).getFirst().get("STATUS"));
  }

  private String createPausedTask(String workspaceId) {
    String runId =
        runs.create(
            new CreateTaskRequest(
                "人工接手测试",
                "normal",
                6,
                4,
                60,
                "mock",
                workspaceId,
                null,
                true,
                null,
                false,
                null,
                List.of("只修改授权文件"),
                List.of("人工修改后重新验证"),
                null,
                null,
                TaskEntryKind.CODE,
                null,
                null,
                List.of("calculator.py"),
                null,
                null,
                "workspace-change",
                true,
                null));
    records.update(runId, RunStatus.PAUSED, null, null, 0, 0, 0);
    return runId;
  }

  private WorkspaceService.Workspace workspace() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo-" + java.util.UUID.randomUUID()));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(repo.resolve("calculator.py"), "def add(a,b):\n    return a-b\n", StandardCharsets.UTF_8);
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
