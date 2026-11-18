package dev.workbench.bug;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.workspace.ApprovalService;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 显式启用的容器集成验证，使用临时仓库和真实 unittest 证据。 */
@EnabledIfEnvironmentVariable(named = "RUN_DOCKER_TESTS", matches = "true")
class BugDockerIntegrationTest {
  @TempDir Path temp;

  @Test
  void reproducesAndRegressesInReadOnlyContainer() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo"));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(repo.resolve("calculator.py"), "def add(a,b):\n    return a-b\n");
    Files.createDirectory(repo.resolve("tests"));
    Files.writeString(
        repo.resolve("tests/test_calc.py"),
        "import unittest\n"
            + "from calculator import add\n"
            + "class TestCalc(unittest.TestCase):\n"
            + "    def test_add(self):\n"
            + "        self.assertEqual(add(2,1),3)\n");
    git(repo, "add", ".");
    git(repo, "commit", "-m", "fixture");
    var source =
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
    db.execute(
        "CREATE TABLE bug_checks (id VARCHAR(36) PRIMARY KEY, bug_id VARCHAR(80), workspace_id"
            + " VARCHAR(36), kind VARCHAR(30), status VARCHAR(30), output CLOB, duration_ms BIGINT,"
            + " created_at VARCHAR(40), workspace_fingerprint VARCHAR(64), run_id VARCHAR(36))");
    WorkspaceService workspaces = new WorkspaceService(db, temp.resolve("workspaces").toString());
    var workspace = workspaces.create(repo.toString(), "main");
    db.execute(
        "CREATE TABLE approvals (id VARCHAR(36) PRIMARY KEY, workspace_id VARCHAR(36), fingerprint"
            + " VARCHAR(64), status VARCHAR(30), created_at VARCHAR(40), updated_at VARCHAR(40))");
    db.execute(
        "CREATE TABLE bug_feedback (id VARCHAR(36) PRIMARY KEY, bug_id VARCHAR(80), workspace_id"
            + " VARCHAR(36), status VARCHAR(40), note VARCHAR(1000), created_at VARCHAR(40))");
    ApprovalService approvals = new ApprovalService(db, workspaces);
    BugService bugs = new BugService(workspaces, db, approvals);
    var first = bugs.run("MOCK-PY-001", workspace.id(), BugService.CheckKind.REPRODUCE);
    assertEquals(BugService.CheckStatus.FAILURES_OBSERVED, first.status(), first.output());
    var file = workspaces.read(workspace.id(), "calculator.py");
    var fixed =
        workspaces.write(
            workspace.id(), "calculator.py", file.sha256(), "def add(a,b):\n    return a+b\n");
    var regression = bugs.run("MOCK-PY-001", workspace.id(), BugService.CheckKind.REGRESSION);
    assertEquals(BugService.CheckStatus.PASSED, regression.status(), regression.output());
    assertThrows(
        IllegalStateException.class,
        () ->
            bugs.feedback(
                "MOCK-PY-001",
                workspace.id(),
                BugService.FeedbackStatus.USER_CONFIRMED_CLOSED,
                "用户确认"));
    var approval = approvals.propose(workspace.id());
    approvals.decide(approval.get("ID").toString(), ApprovalService.Decision.APPROVE);
    assertEquals(
        "USER_CONFIRMED_CLOSED",
        bugs.feedback(
                "MOCK-PY-001",
                workspace.id(),
                BugService.FeedbackStatus.USER_CONFIRMED_CLOSED,
                "已审查")
            .get("STATUS"));
    workspaces.write(
        workspace.id(), "calculator.py", fixed.sha256(), "def add(a,b):\n    return a+b+0\n");
    assertThrows(
        IllegalStateException.class,
        () ->
            bugs.feedback(
                "MOCK-PY-001",
                workspace.id(),
                BugService.FeedbackStatus.USER_CONFIRMED_CLOSED,
                "过期"));
    assertEquals(
        "def add(a,b):\n    return a-b\n", Files.readString(repo.resolve("calculator.py")));
  }

  @Test
  void targetSymptomGateDistinguishesMatchingAndUnrelatedFailures() throws Exception {
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
    db.execute(
        "CREATE TABLE bug_checks (id VARCHAR(36) PRIMARY KEY, bug_id VARCHAR(80), workspace_id"
            + " VARCHAR(36), kind VARCHAR(30), status VARCHAR(30), output CLOB, duration_ms BIGINT,"
            + " created_at VARCHAR(40), workspace_fingerprint VARCHAR(64), run_id VARCHAR(36))");
    db.execute(
        "CREATE TABLE approvals (id VARCHAR(36) PRIMARY KEY, workspace_id VARCHAR(36), fingerprint"
            + " VARCHAR(64), status VARCHAR(30), created_at VARCHAR(40), updated_at VARCHAR(40))");
    db.execute(
        "CREATE TABLE bug_feedback (id VARCHAR(36) PRIMARY KEY, bug_id VARCHAR(80), workspace_id"
            + " VARCHAR(36), status VARCHAR(40), note VARCHAR(1000), created_at VARCHAR(40))");
    WorkspaceService workspaces = new WorkspaceService(db, temp.resolve("workspaces-gate").toString());
    ApprovalService approvals = new ApprovalService(db, workspaces);
    BugService bugs = new BugService(workspaces, db, approvals);

    var matching =
        createRepoWorkspace(
            workspaces,
            "repo-target-match",
            java.util.Map.of(
                "calculator.py",
                "def add(a,b):\n    return a-b\n",
                "tests/test_calc.py",
                "import unittest\n"
                    + "from calculator import add\n"
                    + "class TestCalc(unittest.TestCase):\n"
                    + "    def test_add(self):\n"
                    + "        self.assertEqual(add(2,1),3)\n"));
    var matched = bugs.run("MOCK-PY-001", matching.id(), BugService.CheckKind.REPRODUCE);
    assertEquals(BugService.CheckStatus.FAILURES_OBSERVED, matched.status(), matched.output());
    assertTrue(matched.output().contains("synthetic-local v1"));
    assertTrue(matched.output().contains("预期现象"));

    var unrelated =
        createRepoWorkspace(
            workspaces,
            "repo-target-unrelated",
            java.util.Map.of(
                "calculator.py",
                "def add(a,b):\n    return a+b\n",
                "tests/test_other.py",
                "import unittest\n"
                    + "class TestOther(unittest.TestCase):\n"
                    + "    def test_other(self):\n"
                    + "        self.assertEqual(2 + 2, 5)\n"));
    var mismatch = bugs.run("MOCK-PY-001", unrelated.id(), BugService.CheckKind.REPRODUCE);
    assertEquals(BugService.CheckStatus.TARGET_MISMATCH, mismatch.status(), mismatch.output());
    assertTrue(mismatch.output().contains("FAIL: test_other"));
    assertTrue(mismatch.output().contains("test_add"));
    assertTrue(mismatch.output().contains("synthetic-local v1"));
  }

  private WorkspaceService.Workspace createRepoWorkspace(
      WorkspaceService workspaces, String name, java.util.Map<String, String> files)
      throws Exception {
    Path dir = Files.createDirectory(temp.resolve(name));
    git(dir, "init", "-b", "main");
    git(dir, "config", "user.email", "test@example.invalid");
    git(dir, "config", "user.name", "Test");
    for (var entry : files.entrySet()) {
      Path file = dir.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue());
    }
    git(dir, "add", ".");
    git(dir, "commit", "-m", "fixture");
    return workspaces.create(dir.toString(), "main");
  }

  private void git(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.add("-C");
    command.add(dir.toString());
    command.addAll(java.util.List.of(args));
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, p.waitFor(), out);
  }
}
