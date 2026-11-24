package dev.workbench.bug;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.TestJpa;
import dev.workbench.persistence.repository.ApprovalRepository;
import dev.workbench.persistence.repository.BugCheckCaseRepository;
import dev.workbench.persistence.repository.BugCheckRepository;
import dev.workbench.persistence.repository.BugFeedbackRepository;
import dev.workbench.persistence.repository.WorkspaceDeclaredFileRepository;
import dev.workbench.persistence.repository.WorkspaceRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

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
    JdbcTemplate db = newDatabase();
    var services = services(db, temp.resolve("workspaces").toString());
    WorkspaceService workspaces = services.workspaces();
    var workspace = workspaces.create(repo.toString(), "main");
    ApprovalService approvals = services.approvals();
    BugService bugs = services.bugs();
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
    JdbcTemplate db = newDatabase();
    var services = services(db, temp.resolve("workspaces-gate").toString());
    WorkspaceService workspaces = services.workspaces();
    ApprovalService approvals = services.approvals();
    BugService bugs = services.bugs();

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
    var matchedTarget =
        matched.cases().stream()
            .filter(test -> "test_calc.TestCalc.test_add".equals(test.testId()))
            .findFirst()
            .orElseThrow();
    assertEquals(UnittestResults.Outcome.FAILED, matchedTarget.outcome());
    assertTrue(matchedTarget.message().contains("AssertionError: 1 != 3"));

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
    assertEquals(BugService.CheckStatus.TARGET_NOT_EXECUTED, mismatch.status(), mismatch.output());
    assertTrue(mismatch.output().contains("FAIL: test_other"));
    assertTrue(mismatch.output().contains("test_calc.TestCalc.test_add"));
    assertTrue(mismatch.output().contains("synthetic-local v1"));
    assertTrue(
        mismatch.cases().stream()
            .noneMatch(test -> "test_calc.TestCalc.test_add".equals(test.testId())));
  }

  @Test
  void perCaseResultsRejectMixedSimilarAndSkippedTargets() throws Exception {
    JdbcTemplate db = newDatabase();
    var services = services(db, temp.resolve("workspaces-cases").toString());
    WorkspaceService workspaces = services.workspaces();
    BugService bugs = services.bugs();

    // 混合失败：目标用例自身是 1 != 4；1 != 3 来自另一个用例，不能替代。
    var mixed =
        createRepoWorkspace(
            workspaces,
            "repo-target-mixed",
            java.util.Map.of(
                "calculator.py",
                "def add(a,b):\n    return a-b\n",
                "tests/test_calc.py",
                "import unittest\n"
                    + "from calculator import add\n"
                    + "class TestCalc(unittest.TestCase):\n"
                    + "    def test_add(self):\n"
                    + "        self.assertEqual(add(2,1),4)\n"
                    + "    def test_other(self):\n"
                    + "        self.assertEqual(add(2,1),3)\n"));
    var mixedCheck = bugs.run("MOCK-PY-001", mixed.id(), BugService.CheckKind.REPRODUCE);
    assertEquals(BugService.CheckStatus.TARGET_MISMATCH, mixedCheck.status(), mixedCheck.output());
    var mixedTarget =
        mixedCheck.cases().stream()
            .filter(test -> "test_calc.TestCalc.test_add".equals(test.testId()))
            .findFirst()
            .orElseThrow();
    assertEquals(UnittestResults.Outcome.FAILED, mixedTarget.outcome());
    assertTrue(mixedTarget.message().contains("1 != 4"));
    assertFalse(mixedTarget.message().contains("1 != 3"));
    var other =
        mixedCheck.cases().stream()
            .filter(test -> "test_calc.TestCalc.test_other".equals(test.testId()))
            .findFirst()
            .orElseThrow();
    assertTrue(other.message().contains("1 != 3"));

    // 相似名称：只有 test_add_extra 失败，完整标识不能前缀匹配。
    var similar =
        createRepoWorkspace(
            workspaces,
            "repo-target-similar",
            java.util.Map.of(
                "calculator.py",
                "def add(a,b):\n    return a-b\n",
                "tests/test_calc.py",
                "import unittest\n"
                    + "from calculator import add\n"
                    + "class TestCalc(unittest.TestCase):\n"
                    + "    def test_add_extra(self):\n"
                    + "        self.assertEqual(add(2,1),3)\n"));
    var similarCheck = bugs.run("MOCK-PY-001", similar.id(), BugService.CheckKind.REPRODUCE);
    assertEquals(
        BugService.CheckStatus.TARGET_NOT_EXECUTED, similarCheck.status(), similarCheck.output());
    assertTrue(
        similarCheck.cases().stream().anyMatch(test -> test.testId().endsWith(".test_add_extra")));
    assertTrue(
        similarCheck.cases().stream()
            .noneMatch(test -> "test_calc.TestCalc.test_add".equals(test.testId())));

    // 目标被跳过：整体 exit 0，但目标用例未执行，复现和回归都不能通过。
    var skipped =
        createRepoWorkspace(
            workspaces,
            "repo-target-skipped",
            java.util.Map.of(
                "calculator.py",
                "def add(a,b):\n    return a-b\n",
                "tests/test_calc.py",
                "import unittest\n"
                    + "from calculator import add\n"
                    + "class TestCalc(unittest.TestCase):\n"
                    + "    @unittest.skip('target not ready')\n"
                    + "    def test_add(self):\n"
                    + "        self.assertEqual(add(2,1),3)\n"
                    + "    def test_ok(self):\n"
                    + "        self.assertEqual(add(2,1),1)\n"));
    var skippedReproduction = bugs.run("MOCK-PY-001", skipped.id(), BugService.CheckKind.REPRODUCE);
    assertEquals(
        BugService.CheckStatus.TARGET_SKIPPED,
        skippedReproduction.status(),
        skippedReproduction.output());
    var skippedRegression = bugs.run("MOCK-PY-001", skipped.id(), BugService.CheckKind.REGRESSION);
    assertEquals(
        BugService.CheckStatus.TARGET_SKIPPED,
        skippedRegression.status(),
        skippedRegression.output());
    var skippedTarget =
        skippedRegression.cases().stream()
            .filter(test -> "test_calc.TestCalc.test_add".equals(test.testId()))
            .findFirst()
            .orElseThrow();
    assertEquals(UnittestResults.Outcome.SKIPPED, skippedTarget.outcome());
    assertTrue(skippedTarget.message().contains("target not ready"));
  }

  @Test
  void assertionPrefixIsNotTargetSymptom() throws Exception {
    JdbcTemplate db = newDatabase();
    var services = services(db, temp.resolve("workspaces-prefix").toString());
    WorkspaceService workspaces = services.workspaces();
    BugService bugs = services.bugs();
    var prefix =
        createRepoWorkspace(
            workspaces,
            "repo-target-prefix",
            java.util.Map.of(
                "calculator.py",
                "def add(a,b):\n    return a-b\n",
                "tests/test_calc.py",
                "import unittest\n"
                    + "from calculator import add\n"
                    + "class TestCalc(unittest.TestCase):\n"
                    + "    def test_add(self):\n"
                    + "        self.assertEqual(add(2,1),30)\n"));
    var check = bugs.run("MOCK-PY-001", prefix.id(), BugService.CheckKind.REPRODUCE);
    assertEquals(BugService.CheckStatus.TARGET_MISMATCH, check.status(), check.output());
    var target =
        check.cases().stream()
            .filter(test -> "test_calc.TestCalc.test_add".equals(test.testId()))
            .findFirst()
            .orElseThrow();
    assertEquals(UnittestResults.Outcome.FAILED, target.outcome());
    assertTrue(target.message().contains("1 != 30"));
    assertFalse(target.message().contains("1 != 3\n"));
  }

  private JpaServices services(JdbcTemplate db, String root) {
    TestJpa jpa = new TestJpa(db.getDataSource());
    WorkspaceService workspaces =
        new WorkspaceService(
            jpa.repository(WorkspaceRepository.class),
            jpa.repository(WorkspaceDeclaredFileRepository.class),
            root);
    ApprovalService approvals =
        new ApprovalService(
            jpa.repository(ApprovalRepository.class),
            workspaces,
            // 归属校验由专门的越权测试覆盖；这里只验证 Bug 容器的复现/回归逻辑。
            org.mockito.Mockito.mock(dev.workbench.ownership.OwnershipService.class));
    TransactionTemplate tx =
        new TransactionTemplate(
            jpa.repository(org.springframework.transaction.PlatformTransactionManager.class));
    BugService bugs =
        new BugService(
            workspaces,
            jpa.repository(BugCheckRepository.class),
            jpa.repository(BugCheckCaseRepository.class),
            jpa.repository(BugFeedbackRepository.class),
            approvals,
            tx);
    return new JpaServices(jpa, workspaces, approvals, bugs);
  }

  private record JpaServices(
      TestJpa jpa, WorkspaceService workspaces, ApprovalService approvals, BugService bugs) {}

  private JdbcTemplate newDatabase() {
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
        "CREATE TABLE bug_check_cases (check_id VARCHAR(36), sequence_no INT, test_id VARCHAR(300),"
            + " result VARCHAR(30), message CLOB, PRIMARY KEY(check_id,sequence_no))");
    db.execute(
        "CREATE TABLE approvals (id VARCHAR(36) PRIMARY KEY, workspace_id VARCHAR(36), fingerprint"
            + " VARCHAR(64), status VARCHAR(30), created_at VARCHAR(40), updated_at VARCHAR(40))");
    db.execute(
        "CREATE TABLE bug_feedback (id VARCHAR(36) PRIMARY KEY, bug_id VARCHAR(80), workspace_id"
            + " VARCHAR(36), status VARCHAR(40), note VARCHAR(1000), created_at VARCHAR(40))");
    return db;
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
