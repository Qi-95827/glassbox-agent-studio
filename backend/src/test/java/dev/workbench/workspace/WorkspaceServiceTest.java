package dev.workbench.workspace;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.TestJpa;
import dev.workbench.persistence.repository.ApprovalRepository;
import dev.workbench.persistence.repository.WorkspaceDeclaredFileRepository;
import dev.workbench.persistence.repository.WorkspaceRepository;
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
    db.execute(
        "CREATE TABLE workspace_declared_files (workspace_id VARCHAR(36), path VARCHAR(200),"
            + " sha256 VARCHAR(64), source VARCHAR(30), created_at VARCHAR(40),"
            + " PRIMARY KEY(workspace_id,path))");
    TestJpa jpa = new TestJpa(source);
    WorkspaceService service = workspaceService(jpa, temp.resolve("workspaces").toString());
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
    var approvals =
        new ApprovalService(
            jpa.repository(ApprovalRepository.class),
            service,
            // 归属校验由专门的越权测试覆盖；这里只验证审批状态机本身。
            org.mockito.Mockito.mock(dev.workbench.ownership.OwnershipService.class));
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
    var jpa = new TestJpa(source);
    var service = workspaceService(jpa, temp.resolve("unused").toString());
    assertThrows(IllegalArgumentException.class, () -> service.read(id, "link.txt"));
  }

  @Test
  void declaredSupplementFilesBindContentButOtherUntrackedFilesStillBlock() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo-supplement"));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(repo.resolve("demo.txt"), "原始内容", StandardCharsets.UTF_8);
    git(repo, "add", "demo.txt");
    git(repo, "commit", "-m", "initial");
    DataSource source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate db = new JdbcTemplate(source);
    db.execute(
        "CREATE TABLE workspaces (id VARCHAR(36) PRIMARY KEY, repository_path VARCHAR(2000),"
            + " workspace_path VARCHAR(2000), base_branch VARCHAR(500), base_commit VARCHAR(64),"
            + " branch_name VARCHAR(500), created_at VARCHAR(40))");
    db.execute(
        "CREATE TABLE workspace_declared_files (workspace_id VARCHAR(36), path VARCHAR(200),"
            + " sha256 VARCHAR(64), source VARCHAR(30), created_at VARCHAR(40),"
            + " PRIMARY KEY(workspace_id,path))");
    var jpa = new TestJpa(source);
    var service = workspaceService(jpa, temp.resolve("workspaces-supplement").toString());
    var workspace = service.create(repo.toString(), "main");
    Path root = Path.of(workspace.workspacePath());

    String baseline = service.verificationFingerprint(workspace.id());
    // 未声明的未跟踪文件仍不能绑定测试证据。
    Files.writeString(root.resolve("unrelated.txt"), "未声明", StandardCharsets.UTF_8);
    assertThrows(
        IllegalStateException.class, () -> service.verificationFingerprint(workspace.id()));
    Files.delete(root.resolve("unrelated.txt"));

    String sha =
        service.createSupplementTestFile(workspace.id(), "tests/test_calc.py", "import unittest\n");
    assertEquals(64, sha.length());
    String withSupplement = service.verificationFingerprint(workspace.id());
    assertNotEquals(baseline, withSupplement);
    // 已声明文件的内容变化会改变指纹，使依赖旧指纹的检查证据失效。
    Files.writeString(
        root.resolve("tests").resolve("test_calc.py"), "import unittest\n# changed\n");
    String changed = service.verificationFingerprint(workspace.id());
    assertNotEquals(withSupplement, changed);
    // 声明文件被删除后不能继续声称可绑定。
    Files.delete(root.resolve("tests").resolve("test_calc.py"));
    assertThrows(
        IllegalStateException.class, () -> service.verificationFingerprint(workspace.id()));
  }

  @Test
  void concurrentSupplementCreationHasOneWinnerAndDeclarationFailureCleansOnlyNewFile()
      throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo-race"));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(repo.resolve("demo.txt"), "原始内容", StandardCharsets.UTF_8);
    git(repo, "add", "demo.txt");
    git(repo, "commit", "-m", "initial");
    DataSource source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate db = new JdbcTemplate(source);
    db.execute(
        "CREATE TABLE workspaces (id VARCHAR(36) PRIMARY KEY, repository_path VARCHAR(2000),"
            + " workspace_path VARCHAR(2000), base_branch VARCHAR(500), base_commit VARCHAR(64),"
            + " branch_name VARCHAR(500), created_at VARCHAR(40))");
    db.execute(
        "CREATE TABLE workspace_declared_files (workspace_id VARCHAR(36), path VARCHAR(200),"
            + " sha256 VARCHAR(64), source VARCHAR(30), created_at VARCHAR(40),"
            + " PRIMARY KEY(workspace_id,path))");
    var jpa = new TestJpa(source);
    var service = workspaceService(jpa, temp.resolve("workspaces-race").toString());
    var workspace = service.create(repo.toString(), "main");
    Path root = Path.of(workspace.workspacePath());

    String contentA = "import unittest\n# A\n";
    String contentB = "import unittest\n# B\n";
    var start = new java.util.concurrent.CountDownLatch(1);
    var resultA = new java.util.concurrent.atomic.AtomicReference<String>();
    var errorA = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
    var resultB = new java.util.concurrent.atomic.AtomicReference<String>();
    var errorB = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
    Thread first =
        new Thread(
            () -> {
              try {
                start.await();
                resultA.set(
                    service.createSupplementTestFile(
                        workspace.id(), "tests/test_race.py", contentA));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (RuntimeException e) {
                errorA.set(e);
              }
            });
    Thread second =
        new Thread(
            () -> {
              try {
                start.await();
                resultB.set(
                    service.createSupplementTestFile(
                        workspace.id(), "tests/test_race.py", contentB));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (RuntimeException e) {
                errorB.set(e);
              }
            });
    first.start();
    second.start();
    start.countDown();
    first.join(5000);
    second.join(5000);
    assertFalse(first.isAlive());
    assertFalse(second.isAlive());
    int successes = (resultA.get() != null ? 1 : 0) + (resultB.get() != null ? 1 : 0);
    assertEquals(1, successes, "同名并发创建只能有一个成功。");
    assertTrue((errorA.get() == null) != (errorB.get() == null), "失败请求必须有明确异常。");
    String winnerContent = resultA.get() != null ? contentA : contentB;
    Path raceFile = root.resolve("tests").resolve("test_race.py");
    assertEquals(winnerContent, Files.readString(raceFile));
    String declared =
        db.queryForObject(
            "SELECT sha256 FROM workspace_declared_files WHERE workspace_id=? AND path=?",
            String.class,
            workspace.id(),
            "tests/test_race.py");
    assertEquals(sha256(winnerContent), declared);

    // 声明入库失败：只删除本次创建的文件，已有补充文件与声明保留，同一路径可在恢复后重试。
    service.createSupplementTestFile(workspace.id(), "tests/test_keep.py", "keep\n");
    db.execute("ALTER TABLE workspace_declared_files RENAME TO workspace_declared_files_off");
    try {
      assertThrows(
          RuntimeException.class,
          () -> service.createSupplementTestFile(workspace.id(), "tests/test_retry.py", "retry\n"));
      assertFalse(Files.exists(root.resolve("tests").resolve("test_retry.py")));
      assertTrue(Files.exists(root.resolve("tests").resolve("test_keep.py")));
    } finally {
      db.execute("ALTER TABLE workspace_declared_files_off RENAME TO workspace_declared_files");
    }
    service.createSupplementTestFile(workspace.id(), "tests/test_retry.py", "retry\n");
    assertTrue(Files.exists(root.resolve("tests").resolve("test_retry.py")));
  }

  private static WorkspaceService workspaceService(TestJpa jpa, String root) {
    return new WorkspaceService(
        jpa.repository(WorkspaceRepository.class),
        jpa.repository(WorkspaceDeclaredFileRepository.class),
        root);
  }

  private static String sha256(String text) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
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
