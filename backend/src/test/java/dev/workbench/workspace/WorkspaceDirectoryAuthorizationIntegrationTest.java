package dev.workbench.workspace;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.TaskEntryKind;
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

/** 目录授权、排除规则、服务端复验和撤权后重试。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task8dirs;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task8-dirs"
    })
class WorkspaceDirectoryAuthorizationIntegrationTest {
  @Autowired RunService runs;
  @Autowired RunRecordService records;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceAuthorizationService authorization;
  @TempDir Path temp;

  @Test
  void directoryScopeExpandsExcludesAndRevalidatesServerSide() throws Exception {
    var workspace = workspace();
    String run =
        create(
            workspace.id(),
            List.of(),
            List.of("src"),
            List.of("src/skip.py"));
    String taskId = records.runSnapshot(run).taskId().toString();
    var saved = authorization.find(taskId);
    assertEquals(List.of("src/keep.py"), saved.get("EFFECTIVE_FILES"));
    assertEquals(List.of("src/keep.py"), records.taskDefinition(taskId).fileScopes());
    authorization.requireCurrent(taskId);

    assertThrows(
        IllegalArgumentException.class,
        () -> create(workspace.id(), List.of(), List.of("../outside"), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> create(workspace.id(), List.of(), List.of("src"), List.of("../outside")));

    // 撤权后任务文件范围不再包含旧目录文件。
    String revokeRun =
        create(workspace.id(), List.of("outside.py"), List.of("src"), List.of());
    String revokeTask = records.runSnapshot(revokeRun).taskId().toString();
    authorization.revoke(revokeTask, "src");
    List<String> remaining = records.taskDefinition(revokeTask).fileScopes();
    assertFalse(remaining.contains("src/keep.py"));
    assertTrue(remaining.contains("outside.py"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthorizedFileSet(remaining).require("src/keep.py"));

    // 文件被删除后，服务端复验必须拒绝旧授权。
    Files.delete(Path.of(workspace.workspacePath()).resolve("src/keep.py"));
    assertThrows(IllegalStateException.class, () -> authorization.requireCurrent(taskId));
  }

  @Test
  void symlinkInsideAuthorizedDirectoryIsExcludedAndSymlinkDirectoryRejected() throws Exception {
    var workspace = workspace();
    Path root = Path.of(workspace.workspacePath());
    Path link = root.resolve("src/link.py");
    boolean created;
    try {
      Files.createSymbolicLink(link, root.resolve("outside.py"));
      created = true;
    } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
      created = false;
    }
    org.junit.jupiter.api.Assumptions.assumeTrue(created, "当前文件系统不允许创建符号链接");
    String run = create(workspace.id(), List.of(), List.of("src"), List.of());
    assertEquals(
        List.of("src/keep.py"),
        authorization.find(records.runSnapshot(run).taskId().toString()).get("EFFECTIVE_FILES"));
    Path directoryLink = root.resolve("src-link");
    try {
      Files.createSymbolicLink(directoryLink, root.resolve("src"));
    } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
      return;
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> create(workspace.id(), List.of(), List.of("src-link"), List.of()));
  }

  private String create(
      String workspaceId,
      List<String> files,
      List<String> directories,
      List<String> exclusions) {
    return runs.create(
        new CreateTaskRequest(
            "目录授权验证",
            "normal",
            2,
            1,
            30,
            "mock",
            workspaceId,
            null,
            true,
            null,
            false,
            null,
            List.of(),
            List.of(),
            null,
            null,
            TaskEntryKind.CODE,
            null,
            null,
            files.isEmpty() ? null : files,
            null,
            null,
            null,
            true,
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
    Files.writeString(repo.resolve("src/keep.py"), "def keep():\n    return 1\n", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve("src/skip.py"), "SECRET = 'skip'\n", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve("outside.py"), "OUTSIDE = True\n", StandardCharsets.UTF_8);
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
}
