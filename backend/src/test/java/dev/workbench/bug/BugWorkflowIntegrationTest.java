package dev.workbench.bug;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.task.RunRepository;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 检查模拟 Bug 任务不会把只读分析当成已修复。 */
@EnabledIfEnvironmentVariable(named = "RUN_DOCKER_TESTS", matches = "true")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:bugworkflow;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-test-workspaces"
    })
class BugWorkflowIntegrationTest {
  @Autowired WorkspaceService workspaces;
  @Autowired RunService runs;
  @Autowired RunRepository repository;
  @Autowired BugService bugs;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired org.springframework.jdbc.core.JdbcTemplate db;
  @Autowired io.agentscope.core.state.AgentStateStore agentStates;
  @Autowired dev.workbench.report.HtmlReportService reports;
  @Autowired dev.workbench.evaluation.BugEvidenceAssessmentService assessments;
  @TempDir Path temp;

  @Test
  void observedFailureAndReadOnlyAnalysisRemainUnresolved() throws Exception {
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
    var workspace = workspaces.create(repo.toString(), "main");
    String otherCheckId = java.util.UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " bug_checks(id,bug_id,workspace_id,kind,status,output,duration_ms,created_at,workspace_fingerprint,run_id)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        otherCheckId,
        "MOCK-PY-001",
        workspace.id(),
        "REPRODUCE",
        "FAILURES_OBSERVED",
        "OTHER_RUN_UNTRUSTED_MARKER",
        1,
        "9999-01-01T00:00:00Z",
        "a".repeat(64),
        null);
    String id =
        runs.create(
            new CreateTaskRequest(
                "分析 Bug",
                "normal",
                4,
                2,
                30,
                "mock",
                workspace.id(),
                "calculator.py",
                false,
                "MOCK-PY-001"));
    java.util.Map<String, Object> run = null;
    for (int i = 0; i < 200; i++) {
      run = repository.run(id);
      if (!java.util.Set.of("PENDING", "RUNNING", "PAUSED").contains(run.get("STATUS"))) break;
      Thread.sleep(100);
    }
    assertNotNull(run);
    assertEquals("BLOCKED", run.get("STATUS"), String.valueOf(run.get("REASON")));
    assertTrue(run.get("REASON").toString().contains("未执行"));
    assertTrue(
        repository.events(id).stream()
            .anyMatch(
                event ->
                    "CHECKPOINT_SKIPPED".equals(event.get("EVENT_TYPE"))
                        && event.get("MESSAGE").toString().contains("Bug 任务")));

    assertEquals(2, repository.agents(id).size());
    assertEquals("读取授权文件", repository.tools(id).getFirst().get("TOOL_NAME"));
    assertTrue(
        repository.events(id).stream().anyMatch(e -> "TEST_FINISHED".equals(e.get("EVENT_TYPE"))));
    var evidence = bugs.checksForRun(id);
    assertEquals(1, evidence.size());
    assertEquals(id, evidence.getFirst().get("RUN_ID"));
    assertEquals(workspace.id(), evidence.getFirst().get("WORKSPACE_ID"));
    assertEquals("REPRODUCE", evidence.getFirst().get("KIND"));
    assertEquals("FAILURES_OBSERVED", evidence.getFirst().get("STATUS"));
    assertNotNull(evidence.getFirst().get("WORKSPACE_FINGERPRINT"));
    String analystId =
        repository.agents(id).stream()
            .filter(a -> "ANALYST".equals(a.get("ROLE_CODE")))
            .findFirst()
            .orElseThrow()
            .get("ID")
            .toString();
    var state =
        agentStates
            .get(null, analystId, "agent_state", io.agentscope.core.state.AgentState.class)
            .orElseThrow();
    String modelContext =
        state.getContext().stream()
            .map(io.agentscope.core.message.Msg::getTextContent)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.joining("\n"));
    assertTrue(modelContext.contains(evidence.getFirst().get("ID").toString()));
    assertFalse(modelContext.contains("OTHER_RUN_UNTRUSTED_MARKER"));
    var transferRow =
        repository.handoffs(id).stream()
            .filter(row -> "AGENT_TRANSFER".equals(row.get("DIRECTION")))
            .findFirst()
            .orElseThrow();
    var transfer = (dev.workbench.orchestration.Handoff) transferRow.get("PAYLOAD");
    var checkId = java.util.UUID.fromString(evidence.getFirst().get("ID").toString());
    assertEquals(2, transfer.schemaVersion());
    assertEquals(java.util.List.of(checkId), transfer.testCheckIds());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.handoff(
                dev.workbench.domain.HandoffDirection.AGENT_TRANSFER,
                transfer.withTestChecks(
                    java.util.List.of(java.util.UUID.fromString(otherCheckId)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.handoff(
                dev.workbench.domain.HandoffDirection.AGENT_TRANSFER,
                transfer.withTestChecks(java.util.List.of(checkId, checkId))));
    var assessment = assessments.assess(id);
    assertEquals(
        dev.workbench.evaluation.BugEvidenceAssessment.Observation.FAILURE_OBSERVED,
        assessment.observation());
    assertEquals(
        dev.workbench.evaluation.BugEvidenceAssessment.Regression.UNCONFIRMED,
        assessment.regression());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/evaluations/bug-evidence")
                .param("runId", id))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.observation")
                .value("FAILURE_OBSERVED"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.regression")
                .value("UNCONFIRMED"));
    assertEquals(
        java.util.List.of(evidence.getFirst().get("ID").toString()),
        assessment.observationEvidenceIds());
    String evidenceId = evidence.getFirst().get("ID").toString();
    db.update("UPDATE bug_checks SET bug_id='OTHER' WHERE id=?", evidenceId);
    var mismatched = assessments.assess(id);
    assertEquals(1, mismatched.ignoredEvidenceCount());
    assertEquals(
        dev.workbench.evaluation.BugEvidenceAssessment.Observation.UNCONFIRMED,
        mismatched.observation());
    db.update("UPDATE bug_checks SET bug_id='MOCK-PY-001' WHERE id=?", evidenceId);
    db.update("UPDATE bug_checks SET status='FUTURE_STATUS' WHERE id=?", evidenceId);
    assertEquals(1, assessments.assess(id).ignoredEvidenceCount());
    db.update("UPDATE bug_checks SET status='PASSED' WHERE id=?", evidenceId);
    assertEquals(1, assessments.assess(id).ignoredEvidenceCount());
    db.update(
        "UPDATE bug_checks SET status='FAILURES_OBSERVED', workspace_fingerprint='invalid' WHERE"
            + " id=?",
        evidenceId);
    assertEquals(1, assessments.assess(id).ignoredEvidenceCount());
    db.update(
        "UPDATE bug_checks SET workspace_fingerprint=? WHERE id=?",
        evidence.getFirst().get("WORKSPACE_FINGERPRINT"),
        evidenceId);
    String report = reports.render(id);
    assertTrue(report.contains("本次运行的检测证据"));
    assertTrue(report.contains(evidence.getFirst().get("ID").toString()));
    assertTrue(report.contains(evidence.getFirst().get("WORKSPACE_FINGERPRINT").toString()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            bugs.runForTask(
                id, "MOCK-PY-001", workspace.id(), BugService.CheckKind.REGRESSION, () -> {}));
    assertThrows(
        org.springframework.dao.EmptyResultDataAccessException.class,
        () -> bugs.checksForRun(java.util.UUID.randomUUID().toString()));

    String writeRun =
        runs.create(
            new CreateTaskRequest(
                "检查并修复 Bug",
                "normal",
                8,
                4,
                30,
                "mock",
                workspace.id(),
                "calculator.py",
                true,
                "MOCK-PY-001"));
    java.util.Map<String, Object> writeStatus = null;
    for (int i = 0; i < 250; i++) {
      writeStatus = repository.run(writeRun);
      if (!java.util.Set.of("PENDING", "RUNNING", "PAUSED").contains(writeStatus.get("STATUS")))
        break;
      Thread.sleep(100);
    }
    assertNotNull(writeStatus);
    assertEquals("BLOCKED", writeStatus.get("STATUS"), String.valueOf(writeStatus.get("REASON")));
    var checks = bugs.checksForRun(writeRun);
    assertEquals(2, checks.size());
    var checkIds =
        checks.stream()
            .map(c -> java.util.UUID.fromString(c.get("ID").toString()))
            .collect(java.util.stream.Collectors.toSet());
    var transfers =
        repository.handoffs(writeRun).stream()
            .filter(row -> "AGENT_TRANSFER".equals(row.get("DIRECTION")))
            .map(row -> (dev.workbench.orchestration.Handoff) row.get("PAYLOAD"))
            .toList();
    var coding =
        transfers.stream()
            .filter(h -> h.receiver() == dev.workbench.domain.HandoffParty.CODING_AGENT)
            .findFirst()
            .orElseThrow();
    var reviewing =
        transfers.stream()
            .filter(h -> h.receiver() == dev.workbench.domain.HandoffParty.REVIEW_AGENT)
            .findFirst()
            .orElseThrow();
    assertEquals(1, coding.testCheckIds().size());
    assertEquals(checkIds, java.util.Set.copyOf(reviewing.testCheckIds()));
    assertEquals(2, reviewing.schemaVersion());
  }

  private void git(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.add("-C");
    command.add(dir.toString());
    command.addAll(java.util.List.of(args));
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, p.waitFor(), output);
  }
}
