package dev.workbench.bug;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.control.StopExecution;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.framework.workflow.check.StaleEvidenceException;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.PlanRepository;
import dev.workbench.framework.workflow.view.ExecutionViewService;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.task.RunRepository;
import dev.workbench.task.WorkflowExecutionRepository;
import dev.workbench.workspace.WorkspaceService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Flux;

/** 模拟 Bug 动态修复流程：真实容器证据与可控状态反例。 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:bugdynamic;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-test-workspaces"
    })
class BugDynamicWorkflowIntegrationTest {
  @Autowired RunService service;
  @Autowired RunRepository repo;
  @Autowired WorkspaceService workspaces;
  @Autowired BugService bugs;
  @Autowired PlanRepository plans;
  @Autowired ExecutionViewService executionViews;
  @Autowired WorkflowExecutionRepository workflowRuns;
  @Autowired JdbcTemplate db;
  @Autowired dev.workbench.report.HtmlReportService reports;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @MockitoSpyBean AgentModelRegistry workflowModelRegistry;
  @MockitoSpyBean BugService bugService;
  @TempDir Path temp;

  @Test
  @EnabledIfEnvironmentVariable(named = "RUN_DOCKER_TESTS", matches = "true")
  void realContainerEvidenceDrivesReproductionFixAndRegression() throws Exception {
    var fixture = failingPythonWorkspace();
    stubFileWriter();
    String id = createBugTask(fixture.workspace().id(), true, null);

    var run = await(id);
    assertEquals("BLOCKED", run.get("STATUS"), String.valueOf(run.get("REASON")));
    assertTrue(run.get("REASON").toString().contains("回归检查通过"));
    assertTrue(run.get("REASON").toString().contains("不会自动合并"));

    var nodes = plans.find(id);
    assertEquals(5, nodes.size());
    assertEquals(PlanNodeKind.SYSTEM_CHECK, nodes.get(0).kind());
    assertEquals(PlanNodeKind.SYSTEM_CHECK, nodes.get(3).kind());
    assertTrue(nodes.stream().allMatch(n -> n.status() == PlanNodeStatus.COMPLETED));
    assertEquals(3, workflowRuns.workflowAttempts(id).size());
    assertEquals(3, repo.agents(id).size());

    var checks = workflowRuns.workflowChecks(id);
    assertEquals(2, checks.size());
    assertTrue(checks.stream().allMatch(c -> c.status() == SystemCheckStatus.PASSED));
    assertTrue(checks.stream().allMatch(c -> c.externalEvidenceId() != null));
    assertEquals(
        Set.of("bug-reproduction", "bug-regression"),
        Set.copyOf(
            checks.stream()
                .map(check -> check.check().id())
                .collect(java.util.stream.Collectors.toSet())));

    var bugChecks = bugs.checksForRun(id);
    assertEquals(2, bugChecks.size());
    assertTrue(bugChecks.stream().allMatch(c -> id.equals(c.get("RUN_ID"))));
    assertTrue(bugChecks.stream().allMatch(c -> c.get("WORKSPACE_FINGERPRINT") != null));
    assertEquals(
        Set.of("REPRODUCE", "REGRESSION"),
        Set.copyOf(
            bugChecks.stream()
                .map(c -> c.get("KIND").toString())
                .collect(java.util.stream.Collectors.toSet())));
    var regression =
        bugChecks.stream()
            .filter(c -> "REGRESSION".equals(c.get("KIND")))
            .findFirst()
            .orElseThrow();
    assertEquals("PASSED", regression.get("STATUS"));
    assertEquals(
        workspaces.verificationFingerprint(fixture.workspace().id()),
        regression.get("WORKSPACE_FINGERPRINT"));

    var reproduction =
        bugChecks.stream().filter(c -> "REPRODUCE".equals(c.get("KIND"))).findFirst().orElseThrow();
    assertEquals("FAILURES_OBSERVED", reproduction.get("STATUS"));
    assertNotEquals(
        reproduction.get("WORKSPACE_FINGERPRINT"), regression.get("WORKSPACE_FINGERPRINT"));
    assertTrue(
        checks.stream()
            .anyMatch(
                check -> check.externalEvidenceId().equals(reproduction.get("ID").toString())));
    assertTrue(
        checks.stream()
            .anyMatch(check -> check.externalEvidenceId().equals(regression.get("ID").toString())));
    assertEquals(
        3,
        db.queryForObject(
            "SELECT COUNT(*) FROM workflow_handoff_checks WHERE check_id IN (?,?)",
            Integer.class,
            checks.getFirst().id().toString(),
            checks.getLast().id().toString()));

    Path fixed = Path.of(fixture.workspace().workspacePath()).resolve("calculator.py");
    assertTrue(Files.readString(fixed).contains("a+b"));
    assertEquals(
        "def add(a,b):\n    return a-b\n",
        Files.readString(Path.of(fixture.workspace().repositoryPath()).resolve("calculator.py")));
    assertTrue(
        repo.tools(id).stream()
            .anyMatch(
                tool ->
                    dev.workbench.domain.ToolKind.WORKSPACE_WRITE
                        .recordName()
                        .equals(tool.get("TOOL_NAME"))));

    var view = executionViews.view(id);
    assertEquals("BLOCKED", view.runStatus());
    assertEquals(5, view.nodes().size());
    assertTrue(
        view.nodes().stream()
            .anyMatch(node -> "SYSTEM_CHECK".equals(node.kind()) && "COMPLETED".equals(node.status())));
    assertTrue(view.nodes().stream().anyMatch(node -> node.workspaceWrite()));
    assertEquals(2, view.counts().checks());
    assertEquals(6, view.counts().modelCalls());
    assertTrue(view.counts().toolCalls() >= 1);
    var writeNode = view.nodes().stream().filter(node -> node.workspaceWrite()).findFirst().orElseThrow();
    var writeDetail = executionViews.detail(id, writeNode.id());
    assertTrue(
        writeDetail.attempts().stream()
            .flatMap(attempt -> attempt.tools().stream())
            .anyMatch(
                tool ->
                    dev.workbench.domain.ToolKind.WORKSPACE_WRITE
                        .recordName()
                        .equals(tool.name())));
    var regressionNode =
        view.nodes().stream().filter(node -> "SYSTEM_CHECK".equals(node.kind())).toList().getLast();
    var regressionDetail = executionViews.detail(id, regressionNode.id());
    assertTrue(
        regressionDetail.checks().stream()
            .anyMatch(
                check -> "PASSED".equals(check.status()) && check.externalEvidenceId() != null));

    var report = reports.render(id);
    assertTrue(report.contains(reproduction.get("ID").toString()));
    assertTrue(report.contains(regression.get("ID").toString()));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM bug_feedback WHERE workspace_id=?",
            Integer.class,
            fixture.workspace().id()));
  }

  @Test
  void failedReproductionBlocksAllDownstreamWithoutAgentOrModelCalls() throws Exception {
    var fixture = failingPythonWorkspace();
    var status = new AtomicReference<>(BugService.CheckStatus.NEEDS_INFO);
    org.mockito.Mockito.doAnswer(
            invocation ->
                fakeCheck(
                    fixture.workspace().id(),
                    invocation.getArgument(0),
                    BugService.CheckKind.REPRODUCE,
                    status.get(),
                    "缺少 tests 目录，无法执行验证。"))
        .when(bugService)
        .runForTask(
            anyString(),
            eq("MOCK-PY-001"),
            eq(fixture.workspace().id()),
            eq(BugService.CheckKind.REPRODUCE),
            any(Runnable.class));
    for (var entry :
        List.of(
            Map.entry(BugService.CheckStatus.NEEDS_INFO, SystemCheckStatus.BLOCKED),
            Map.entry(BugService.CheckStatus.ERROR, SystemCheckStatus.BLOCKED),
            Map.entry(BugService.CheckStatus.NOT_REPRODUCED, SystemCheckStatus.FAILED),
            Map.entry(BugService.CheckStatus.STALE, SystemCheckStatus.BLOCKED))) {
      status.set(entry.getKey());
      String id = createBugTask(fixture.workspace().id(), true, null);
      var run = await(id);
      assertEquals("BLOCKED", run.get("STATUS"), String.valueOf(run.get("REASON")));
      assertEquals("CHECK_FAILED", run.get("STOP_CAUSE"));
      var nodes = plans.find(id);
      assertEquals(PlanNodeStatus.FAILED, nodes.get(0).status());
      assertTrue(
          nodes.subList(1, nodes.size()).stream()
              .allMatch(n -> n.status() == PlanNodeStatus.SKIPPED));
      assertTrue(repo.agents(id).isEmpty());
      assertTrue(repo.models(id).isEmpty());
      assertTrue(repo.tools(id).isEmpty());
      var checks = workflowRuns.workflowChecks(id);
      assertEquals(1, checks.size());
      assertEquals(entry.getValue(), checks.getFirst().status());
      assertEquals("bug-reproduction", checks.getFirst().check().id());
    }
  }

  @Test
  void failedRegressionBlocksIndependentReviewAndKeepsWriteEvidence() throws Exception {
    var fixture = failingPythonWorkspace();
    stubFileWriter();
    stubBugCheck(
        fixture.workspace().id(),
        BugService.CheckKind.REPRODUCE,
        BugService.CheckStatus.FAILURES_OBSERVED,
        "Traceback: assertion failed");
    stubBugCheck(
        fixture.workspace().id(),
        BugService.CheckKind.REGRESSION,
        BugService.CheckStatus.FAILED,
        "仍有测试失败");

    String id = createBugTask(fixture.workspace().id(), true, null);
    var run = await(id);
    assertEquals("BLOCKED", run.get("STATUS"));
    assertEquals("CHECK_FAILED", run.get("STOP_CAUSE"));
    var nodes = plans.find(id);
    assertEquals(PlanNodeStatus.COMPLETED, nodes.get(0).status());
    assertEquals(PlanNodeStatus.COMPLETED, nodes.get(2).status());
    assertEquals(PlanNodeStatus.FAILED, nodes.get(3).status());
    assertEquals(PlanNodeStatus.SKIPPED, nodes.get(4).status());
    var checks = workflowRuns.workflowChecks(id);
    assertEquals(2, checks.size());
    assertEquals(SystemCheckStatus.PASSED, checks.getFirst().status());
    assertEquals(SystemCheckStatus.FAILED, checks.getLast().status());
    assertTrue(
        repo.tools(id).stream()
            .anyMatch(
                tool ->
                    dev.workbench.domain.ToolKind.WORKSPACE_WRITE
                        .recordName()
                        .equals(tool.get("TOOL_NAME"))),
        () -> repo.tools(id).toString());
    assertEquals(2, repo.agents(id).size());
    assertTrue(
        repo.events(id).stream().anyMatch(event -> "BLOCKED".equals(event.get("EVENT_TYPE"))));
  }

  @Test
  void cancellationDuringReproductionLeavesInterruptedCheck() throws Exception {
    var fixture = failingPythonWorkspace();
    org.mockito.Mockito.doThrow(new StopExecution(StopCause.USER_CANCELLED, "用户取消"))
        .when(bugService)
        .runForTask(
            anyString(),
            eq("MOCK-PY-001"),
            eq(fixture.workspace().id()),
            eq(BugService.CheckKind.REPRODUCE),
            any(Runnable.class));
    String id = createBugTask(fixture.workspace().id(), true, null);
    var run = await(id);
    assertEquals("CANCELLED", run.get("STATUS"));
    assertEquals("USER_CANCELLED", run.get("STOP_CAUSE"));
    var checks = workflowRuns.workflowChecks(id);
    assertEquals(1, checks.size());
    assertEquals(SystemCheckStatus.INTERRUPTED, checks.getFirst().status());
    assertTrue(repo.agents(id).isEmpty());
    assertTrue(repo.models(id).isEmpty());
  }

  @Test
  void staleReproductionEvidenceBlocksDownstreamAfterWorkspaceChanges() throws Exception {
    var fixture = failingPythonWorkspace();
    String id = createBugTask(fixture.workspace().id(), true, null, "MOCK-PY-001", true);
    var plan = plans.workflowPlan(id);
    workflowRuns.approveWorkflow(id, plan.id());
    repo.update(id, RunStatus.RUNNING, null, null, 0, 0, 0);
    var nodes = plans.find(id);
    var claimed = workflowRuns.claimWorkflowCheck(id, plan.id(), nodes.getFirst().id());
    String staleFingerprint = "a".repeat(64);
    String bugCheckId = java.util.UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " bug_checks(id,bug_id,workspace_id,kind,status,output,duration_ms,created_at,workspace_fingerprint,run_id)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        bugCheckId,
        "MOCK-PY-001",
        fixture.workspace().id(),
        "REPRODUCE",
        "FAILURES_OBSERVED",
        "模拟复现输出",
        1,
        RunRepository.now(),
        staleFingerprint,
        id);
    db.update(
        "UPDATE workflow_checks SET status='PASSED',summary=?,external_evidence_id=?,finished_at=?"
            + " WHERE id=?",
        "模拟复现检查通过",
        bugCheckId,
        RunRepository.now(),
        claimed.checkId());
    plans.finishCheckNode(id, nodes.getFirst().id(), PlanNodeStatus.COMPLETED, RunRepository.now());

    var check = workflowRuns.workflowChecks(id).getFirst();
    assertEquals(Boolean.FALSE, check.evidenceCurrent());
    var failure =
        assertThrows(
            StaleEvidenceException.class,
            () -> workflowRuns.claimWorkflowNode(id, plan.id(), nodes.get(1).id(), "定位失败原因"));
    assertTrue(failure.getMessage().contains("工作区已变化"));
    assertTrue(plans.find(id).get(1).status() == PlanNodeStatus.PENDING, "过期证据不能启动下游节点");
    assertTrue(repo.agents(id).isEmpty());
  }

  @Test
  void bugTemplateRequiresBugEntryWriteAuthorizationAndAuthorizedFiles() throws Exception {
    var fixture = failingPythonWorkspace();
    assertThrows(
        IllegalArgumentException.class, () -> createBugTask(fixture.workspace().id(), false, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> createBugTask(fixture.workspace().id(), true, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> createBugTask(fixture.workspace().id(), true, null, "OTHER-BUG"));
  }

  @Test
  void httpEntryCreatesSavedBugPlanBeforeAnyContainerRun() throws Exception {
    var fixture = failingPythonWorkspace();
    String body =
        """
        {"goal":"修复模拟 Bug","entryKind":"BUG","workflowTemplate":"bug-fix","reviewPlan":true,
         "modelMode":"mock","workspaceId":"%s","fileScopes":["calculator.py"],"writeEnabled":true,
         "bugId":"MOCK-PY-001","maxRounds":6,"maxTools":4,"maxSeconds":60}
        """
            .formatted(fixture.workspace().id());
    var response =
        mvc.perform(
                post("/api/tasks")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    String runId =
        new ObjectMapper()
            .readTree(response.getResponse().getContentAsString())
            .path("runId")
            .asText();
    mvc.perform(get("/api/runs/{id}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowPlan.templateId").value("bug-fix"))
        .andExpect(jsonPath("$.workflowPlan.scope").value("WORKSPACE_BUG_FIX"))
        .andExpect(jsonPath("$.planNodes[0].kind").value("SYSTEM_CHECK"))
        .andExpect(jsonPath("$.planNodes[0].checkRef.id").value("bug-reproduction"))
        .andExpect(jsonPath("$.workflowChecks.length()").value(0));
    assertTrue(bugs.checksForRun(runId).isEmpty());
    assertTrue(repo.agents(runId).isEmpty());
    service.cancel(runId);
  }

  @Test
  void reviewedBugPlanRunsNoContainerCheckBeforeApproval() throws Exception {
    var fixture = failingPythonWorkspace();
    stubFileWriter();
    stubBugCheck(
        fixture.workspace().id(),
        BugService.CheckKind.REPRODUCE,
        BugService.CheckStatus.FAILURES_OBSERVED,
        "固定模拟检测输出");
    stubBugCheck(
        fixture.workspace().id(),
        BugService.CheckKind.REGRESSION,
        BugService.CheckStatus.PASSED,
        "固定模拟检测输出");
    String id = createBugTask(fixture.workspace().id(), true, null, "MOCK-PY-001", true);
    assertEquals("PENDING", repo.run(id).get("STATUS"));
    assertTrue(bugs.checksForRun(id).isEmpty());
    assertTrue(plans.find(id).stream().allMatch(node -> node.status() == PlanNodeStatus.PENDING));
    assertTrue(workflowRuns.workflowChecks(id).isEmpty());
    org.mockito.Mockito.verify(bugService, org.mockito.Mockito.never())
        .runForTask(
            anyString(),
            anyString(),
            anyString(),
            any(BugService.CheckKind.class),
            any(Runnable.class));

    service.approvePlan(id, plans.workflowPlan(id).id());
    var run = await(id);
    assertEquals("BLOCKED", run.get("STATUS"), String.valueOf(run.get("REASON")));
    assertEquals(2, workflowRuns.workflowChecks(id).size());
    assertEquals(3, repo.agents(id).size());
  }

  private void stubBugCheck(
      String workspaceId, BugService.CheckKind kind, BugService.CheckStatus status, String output) {
    org.mockito.Mockito.doAnswer(
            invocation -> {
              String runId = invocation.getArgument(0);
              String id = java.util.UUID.randomUUID().toString();
              String fingerprint;
              try {
                fingerprint = workspaces.verificationFingerprint(workspaceId);
              } catch (RuntimeException e) {
                fingerprint = "a".repeat(64);
              }
              db.update(
                  "INSERT INTO"
                      + " bug_checks(id,bug_id,workspace_id,kind,status,output,duration_ms,created_at,workspace_fingerprint,run_id)"
                      + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                  id,
                  "MOCK-PY-001",
                  workspaceId,
                  kind.name(),
                  status.name(),
                  output,
                  1,
                  RunRepository.now(),
                  fingerprint,
                  runId);
              return new BugService.Check(
                  id,
                  "MOCK-PY-001",
                  workspaceId,
                  kind,
                  status,
                  output,
                  1,
                  RunRepository.now(),
                  fingerprint,
                  runId);
            })
        .when(bugService)
        .runForTask(anyString(), eq("MOCK-PY-001"), eq(workspaceId), eq(kind), any(Runnable.class));
  }

  private String createBugTask(String workspaceId, boolean write, List<String> files)
      throws Exception {
    return createBugTask(workspaceId, write, files, "MOCK-PY-001");
  }

  private String createBugTask(
      String workspaceId, boolean write, List<String> files, String bugId) {
    return createBugTask(workspaceId, write, files, bugId, false);
  }

  private String createBugTask(
      String workspaceId, boolean write, List<String> files, String bugId, boolean reviewPlan) {
    List<String> scopes = files == null ? List.of("calculator.py") : files;
    return service.create(
        new CreateTaskRequest(
            "修复模拟 Bug MOCK-PY-001",
            "normal",
            8,
            6,
            90,
            "mock",
            workspaceId,
            null,
            write,
            bugId,
            false,
            null,
            List.of("只修改授权文件"),
            List.of("回归测试通过"),
            null,
            null,
            TaskEntryKind.BUG,
            null,
            null,
            scopes,
            null,
            null,
            "bug-fix",
            reviewPlan,
            null));
  }

  private BugService.Check fakeCheck(
      String workspaceId,
      String runId,
      BugService.CheckKind kind,
      BugService.CheckStatus status,
      String output) {
    return new BugService.Check(
        java.util.UUID.randomUUID().toString(),
        "MOCK-PY-001",
        workspaceId,
        kind,
        status,
        output,
        1,
        RunRepository.now(),
        "a".repeat(64),
        runId);
  }

  private void stubFileWriter() {
    Model model = new FileWriterFixture();
    org.mockito.Mockito.doReturn(
            new AgentModelRegistry.SelectedModel(
                model,
                new AgentModelRegistry.ModelReference(ModelMode.MOCK, model.getModelName(), 1)))
        .when(workflowModelRegistry)
        .select(
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    request != null
                        && request.definitionRef() != null
                        && request.role() == AgentRole.CODER
                        && "workspace-edit".equals(request.definitionRef().id())));
  }

  /** 读取当前授权文件摘要后写入修复内容；拒绝写入未授权路径。 */
  private static final class FileWriterFixture implements Model {
    private final AtomicInteger rounds = new AtomicInteger();
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String getModelName() {
      return "bug-fix-writer";
    }

    @Override
    public Flux<ChatResponse> stream(
        List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
      int round = rounds.getAndIncrement();
      try {
        if (round > 1)
          return Flux.just(
              ChatResponse.builder()
                  .content(List.of(TextBlock.builder().text("已提交授权修改，等待回归与审查。").build()))
                  .build());
        Map<String, Object> arguments = Map.of();
        if (round == 1) {
          String receipt =
              messages.stream()
                  .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                  .map(result -> ((TextBlock) result.getOutput().getFirst()).getText())
                  .reduce((first, last) -> last)
                  .orElseThrow();
          String expected = json.readTree(receipt).path("data").path("sha256").asText();
          arguments =
              Map.of("expectedSha256", expected, "content", "def add(a,b):\n    return a+b\n");
        }
        var toolUse =
            ToolUseBlock.builder()
                .id("bug-fix-fixture-" + round)
                .name(round == 0 ? "read_authorized_file" : "write_authorized_file")
                .input(arguments)
                .content(json.writeValueAsString(arguments))
                .build();
        return Flux.just(ChatResponse.builder().content(List.of(toolUse)).build());
      } catch (Exception e) {
        return Flux.error(e);
      }
    }
  }

  private record Fixture(WorkspaceService.Workspace workspace) {}

  private Fixture failingPythonWorkspace() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo-" + java.util.UUID.randomUUID()));
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
    return new Fixture(workspaces.create(repo.toString(), "main"));
  }

  private java.util.Map<String, Object> await(String runId) throws Exception {
    java.util.Map<String, Object> run = null;
    for (int i = 0; i < 1200; i++) {
      run = repo.run(runId);
      if (!Set.of("PENDING", "RUNNING", "PAUSED").contains(run.get("STATUS"))) return run;
      Thread.sleep(100);
    }
    fail("运行未在预期时间内结束：" + run);
    return run;
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
