package dev.workbench.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 使用完全独立的临时仓库，验证工作区不会改写原工作区。 */
class WorkspaceServiceTest {
  @TempDir Path temp;

  @Test
  void isolatedWorktreeRejectsTraversalAndStaleWrites() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo"));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(repo.resolve("demo.txt"), "原始内容", StandardCharsets.UTF_8);
    git(repo, "add", "demo.txt");
    git(repo, "commit", "-m", "initial");
    Files.writeString(repo.resolve("demo.txt"), "用户未提交的内容", StandardCharsets.UTF_8);
    DataSource source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate db = new JdbcTemplate(source);
    db.execute(
        "CREATE TABLE workspaces (id VARCHAR(36) PRIMARY KEY, repository_path VARCHAR(2000),"
            + " workspace_path VARCHAR(2000), base_branch VARCHAR(500), base_commit VARCHAR(64),"
            + " branch_name VARCHAR(500), created_at VARCHAR(40))");
    db.execute(
        "CREATE TABLE approvals (id VARCHAR(36) PRIMARY KEY, workspace_id VARCHAR(36), fingerprint"
            + " VARCHAR(64), status VARCHAR(30), created_at VARCHAR(40), updated_at VARCHAR(40))");
    WorkspaceService service = new WorkspaceService(db, temp.resolve("workspaces").toString());
    var workspace = service.create(repo.toString(), "main");
    assertEquals("用户未提交的内容", Files.readString(repo.resolve("demo.txt")));
    var before = service.read(workspace.id(), "demo.txt");
    assertEquals("原始内容", before.content());
    assertThrows(
        IllegalArgumentException.class, () -> service.read(workspace.id(), "../repo/demo.txt"));
    assertThrows(IllegalArgumentException.class, () -> service.read(workspace.id(), ".git"));
    var modified = service.write(workspace.id(), "demo.txt", before.sha256(), "工作区修改");
    assertThrows(
        IllegalStateException.class,
        () -> service.write(workspace.id(), "demo.txt", before.sha256(), "覆盖"));
    assertTrue(service.status(workspace.id()).diff().contains("工作区修改"));
    var approvals = new ApprovalService(db, service);
    var request = approvals.propose(workspace.id());
    service.write(workspace.id(), "demo.txt", modified.sha256(), "更新后的修改");
    assertThrows(
        IllegalStateException.class,
        () -> approvals.decide(request.get("ID").toString(), ApprovalService.Decision.APPROVE));
    assertEquals("STALE", approvals.list(workspace.id()).getFirst().get("STATUS"));
    var next = approvals.propose(workspace.id());
    assertEquals(
        "APPROVED",
        approvals
            .decide(next.get("ID").toString(), ApprovalService.Decision.APPROVE)
            .get("STATUS"));
    assertEquals("用户未提交的内容", Files.readString(repo.resolve("demo.txt")));
  }

  @Test
  void symbolicLinksAreRejectedWhenHostCanCreateThem() throws Exception {
    Path root = Files.createDirectory(temp.resolve("workspace"));
    Path target = Files.writeString(temp.resolve("outside.txt"), "外部内容", StandardCharsets.UTF_8);
    try {
      Files.createSymbolicLink(root.resolve("link.txt"), target);
    } catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "当前系统无权限创建符号链接。");
      return;
    }
    DataSource source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate db = new JdbcTemplate(source);
    db.execute(
        "CREATE TABLE workspaces (id VARCHAR(36) PRIMARY KEY, repository_path VARCHAR(2000),"
            + " workspace_path VARCHAR(2000), base_branch VARCHAR(500), base_commit VARCHAR(64),"
            + " branch_name VARCHAR(500), created_at VARCHAR(40))");
    String id = java.util.UUID.randomUUID().toString();
    db.update(
        "INSERT INTO workspaces VALUES(?,?,?,?,?,?,?)",
        id,
        temp.toString(),
        root.toString(),
        "main",
        "commit",
        "branch",
        "time");
    var service = new WorkspaceService(db, temp.resolve("unused").toString());
    assertThrows(IllegalArgumentException.class, () -> service.read(id, "link.txt"));
  }

  private void git(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.add("-C");
    command.add(dir.toString());
    command.addAll(java.util.List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }
}
