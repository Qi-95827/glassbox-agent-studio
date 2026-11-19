package dev.workbench.workspace;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.audit.AuditService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 推送、合并冲突和未知推送使用临时远端验证。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task4git;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task4-git"
    })
class Task4GitDeliveryIntegrationTest {
  @Autowired WorkspaceService workspaces;
  @Autowired ApprovalService approvals;
  @Autowired WorkspaceCommitService commits;
  @Autowired GitDeliveryService deliveries;
  @Autowired AuditService audit;
  @TempDir Path temp;

  @Test
  void pushMergeConflictAndUnknownPushAreRecorded() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo"));
    Path remote = temp.resolve("remote.git");
    git(temp, "init", "-b", "main", repo.toString());
    git(temp, "init", "--bare", remote.toString());
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(repo.resolve("calculator.py"), "def add(a,b):\n    return a-b\n", StandardCharsets.UTF_8);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "fixture");
    git(repo, "remote", "add", "origin", remote.toString());
    var workspace = workspaces.create(repo.toString(), "main");

    var push = deliveries.prepare(workspace.id(), "PUSH", "origin", "main", "首次交付");
    assertEquals(GitDeliveryService.DeliveryStatus.PENDING.name(), push.status());
    deliveries.decide(push.id().toString(), true);
    var pushed = deliveries.execute(push.id().toString());
    assertEquals(GitDeliveryService.DeliveryStatus.CONFIRMED.name(), pushed.status());
    assertThrows(
        IllegalStateException.class,
        () -> deliveries.execute(push.id().toString()),
        "同一交付请求不能重复执行");
    assertEquals(
        workspaces.head(workspace.id()),
        gitOutput(temp, "--git-dir", remote.toString(), "rev-parse", "refs/heads/main").strip());

    // 批准后改写 origin：原批准必须失效，不能向新远端推送。
    Path remoteB = temp.resolve("remote-b.git");
    git(temp, "init", "--bare", remoteB.toString());
    var changedRemote = deliveries.prepare(workspace.id(), "PUSH", "origin", "main", "改指前");
    deliveries.decide(changedRemote.id().toString(), true);
    git(repo, "remote", "set-url", "origin", remoteB.toString());
    assertThrows(
        IllegalStateException.class,
        () -> deliveries.execute(changedRemote.id().toString()),
        "远端地址变化后原批准必须失效");
    assertNotEquals(
        0,
        gitExit(temp, "--git-dir", remoteB.toString(), "rev-parse", "--verify", "refs/heads/main"));
    var rebound = deliveries.prepare(workspace.id(), "PUSH", "origin", "main", "新地址");
    deliveries.decide(rebound.id().toString(), true);
    assertEquals(
        GitDeliveryService.DeliveryStatus.CONFIRMED.name(),
        deliveries.execute(rebound.id().toString()).status());
    assertEquals(
        workspaces.head(workspace.id()),
        gitOutput(temp, "--git-dir", remoteB.toString(), "rev-parse", "refs/heads/main").strip());

    // 在源仓库创建冲突分支；工作区修改同一文件并先做本地提交。
    git(repo, "checkout", "-b", "conflict");
    Files.writeString(repo.resolve("calculator.py"), "def add(a,b):\n    return 100\n", StandardCharsets.UTF_8);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "conflict side");
    git(repo, "checkout", "main");

    var file = workspaces.read(workspace.id(), "calculator.py");
    workspaces.write(
        workspace.id(),
        "calculator.py",
        file.sha256(),
        "def add(a,b):\n    return 200\n");
    var approval = approvals.propose(workspace.id());
    approvals.decide(approval.get("ID").toString(), ApprovalService.Decision.APPROVE);
    String workspaceCommit =
        commits.commit(workspace.id(), approval.get("ID").toString(), "workspace side").commitId();
    assertTrue(workspaceCommit.matches("[0-9a-f]{40,64}"));

    var merge = deliveries.prepare(workspace.id(), "MERGE", "origin", "conflict", "合并冲突分支");
    deliveries.decide(merge.id().toString(), true);
    // 批准后待合入分支新增提交：原批准必须失效，不能把新内容带入。
    git(repo, "checkout", "conflict");
    Files.writeString(repo.resolve("late.txt"), "late\n", StandardCharsets.UTF_8);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "late target change");
    git(repo, "checkout", "main");
    assertThrows(
        IllegalStateException.class,
        () -> deliveries.execute(merge.id().toString()),
        "待合入分支变化后原批准必须失效");
    assertEquals(workspaceCommit, workspaces.head(workspace.id()));
    assertFalse(Files.exists(Path.of(workspace.workspacePath()).resolve("late.txt")));
    var reapproved =
        deliveries.prepare(workspace.id(), "MERGE", "origin", "conflict", "重新批准合并");
    deliveries.decide(reapproved.id().toString(), true);
    var conflicted = deliveries.execute(reapproved.id().toString());
    assertEquals(GitDeliveryService.DeliveryStatus.CONFLICT.name(), conflicted.status());
    assertTrue(conflicted.resultDetail().contains("合并冲突"));
    assertEquals(workspaceCommit, workspaces.head(workspace.id()));
    assertTrue(workspaces.status(workspace.id()).changes().isBlank());

    // 远端存在但不可访问：准备时能绑定地址，执行后必须人工核对。
    git(repo, "remote", "add", "broken", temp.resolve("no-such-remote.git").toString());
    var unknown = deliveries.prepare(workspace.id(), "PUSH", "broken", "main", "不可达远端");
    deliveries.decide(unknown.id().toString(), true);
    var unknownResult = deliveries.execute(unknown.id().toString());
    assertEquals(GitDeliveryService.DeliveryStatus.UNKNOWN.name(), unknownResult.status());
    assertTrue(unknownResult.resultDetail() != null && !unknownResult.resultDetail().isBlank());

    assertTrue(
        audit.list(50).stream().anyMatch(row -> "GIT_DELIVERY_EXECUTE".equals(row.get("ACTION"))));
    assertTrue(
        commits.list(workspace.id()).stream()
            .anyMatch(action -> action.kind() == GitAction.Kind.MERGE
                && action.status() == GitActionStatus.CONFLICT));
  }

  @Test
  void pushUsesValidatedAddressAndApprovedCommitAfterRemoteSwitch() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo-race"));
    Path remoteA = temp.resolve("remote-a.git");
    Path remoteB = temp.resolve("remote-b.git");
    git(temp, "init", "-b", "main", repo.toString());
    git(temp, "init", "--bare", remoteA.toString());
    git(temp, "init", "--bare", remoteB.toString());
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(repo.resolve("calculator.py"), "def add(a,b):\n    return a+b\n", StandardCharsets.UTF_8);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "fixture");
    git(repo, "remote", "add", "origin", remoteA.toString());
    var workspace = workspaces.create(repo.toString(), "main");

    var request = deliveries.prepare(workspace.id(), "PUSH", "origin", "main", "竞态验证");
    deliveries.decide(request.id().toString(), true);
    String approvedCommit = request.headCommit();
    Runnable originalHook = deliveries.beforePush;
    deliveries.beforePush =
        () -> {
          try {
            git(repo, "remote", "set-url", "origin", remoteB.toString());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    try {
      var result = deliveries.execute(request.id().toString());
      assertEquals(GitDeliveryService.DeliveryStatus.CONFIRMED.name(), result.status());
      assertEquals(
          approvedCommit,
          gitOutput(temp, "--git-dir", remoteA.toString(), "rev-parse", "refs/heads/main").strip());
      assertNotEquals(
          0,
          gitExit(temp, "--git-dir", remoteB.toString(), "rev-parse", "refs/heads/main"));
    } finally {
      deliveries.beforePush = originalHook;
    }
  }

  @Test
  void newFileAndUntrackedReviewAreIncludedInApprovedCommit() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo-files"));
    git(temp, "init", "-b", "main", repo.toString());
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(repo.resolve("calculator.py"), "def add(a,b):\n    return a+b\n", StandardCharsets.UTF_8);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "fixture");
    var workspace = workspaces.create(repo.toString(), "main");

    workspaces.createUserFile(workspace.id(), "notes/new.txt", "created by user\n");
    Files.writeString(
        Path.of(workspace.workspacePath()).resolve("manual.txt"),
        "untracked\n",
        StandardCharsets.UTF_8);
    var untracked = workspaces.untrackedFiles(workspace.id());
    assertTrue(
        untracked.stream()
            .anyMatch(file -> "notes/new.txt".equals(file.path()) && file.declared()));
    var manual =
        untracked.stream().filter(file -> "manual.txt".equals(file.path())).findFirst().orElseThrow();
    assertFalse(manual.declared());
    assertThrows(IllegalStateException.class, () -> approvals.propose(workspace.id()));

    workspaces.declareUntracked(workspace.id(), "manual.txt", manual.sha256());
    var approval = approvals.propose(workspace.id());
    approvals.decide(approval.get("ID").toString(), ApprovalService.Decision.APPROVE);
    commits.commit(workspace.id(), approval.get("ID").toString(), "add reviewed files");

    assertEquals(
        "created by user\n",
        gitOutput(Path.of(workspace.workspacePath()), "show", "HEAD:notes/new.txt"));
    assertEquals(
        "untracked\n", gitOutput(Path.of(workspace.workspacePath()), "show", "HEAD:manual.txt"));
    assertTrue(workspaces.status(workspace.id()).changes().isBlank());
  }

  private static String gitOutput(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.addAll(List.of(args));
    Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, p.waitFor(), output);
    return output;
  }

  private static int gitExit(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.addAll(List.of(args));
    Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    return p.waitFor();
  }

  private static void git(Path dir, String... args) throws Exception {
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
