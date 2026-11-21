package dev.workbench;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.EventType;
import dev.workbench.domain.HandoffDirection;
import dev.workbench.domain.HandoffSource;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.ToolKind;
import dev.workbench.domain.CaseStatus;
import dev.workbench.evaluation.EvaluationRepository;
import dev.workbench.evaluation.EvaluationService;
import dev.workbench.evaluation.EvaluationStatus;
import dev.workbench.framework.agent.assembly.AgentAssemblyPlanner;
import dev.workbench.observability.EventQueryService;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.framework.workflow.check.SystemCheckRef;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.planning.PlanService;
import dev.workbench.delivery.ExecutionViewService;
import dev.workbench.domain.MemoryCategory;
import dev.workbench.memory.MemoryService;
import dev.workbench.observability.MonitoringService;
import dev.workbench.observability.RunMetricsService;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.domain.Handoff;
import dev.workbench.orchestration.RunService;
import dev.workbench.report.HtmlReportService;
import dev.workbench.store.RunRecordService;
import dev.workbench.task.WorkflowExecutionService;
import dev.workbench.workspace.ApprovalService;
import dev.workbench.workspace.GitActionStatus;
import dev.workbench.workspace.WorkspaceCommitService;
import dev.workbench.workspace.WorkspaceService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.exception.RateLimitException;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import dev.workbench.delivery.ExecutionViewService;
import dev.workbench.domain.RunLimits;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.planning.PlanService;
import dev.workbench.infrastructure.agentscope.AgentScopeModelClient;
import dev.workbench.domain.CaseStatus;
import dev.workbench.domain.Handoff;
import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.MemoryStatus;
import dev.workbench.store.RunRecordService;

@AutoConfigureMockMvc
@AutoConfigureObservability
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:workbenchtest;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-test-workspaces"
    })
class WorkbenchIntegrationTest {
  @Autowired RunService service;
  @Autowired RunRecordService repo;
  @Autowired WorkflowExecutionService workflowRuns;
  @Autowired JdbcTemplate db;
  @Autowired TransactionTemplate transactions;
  @Autowired HtmlReportService reports;
  @Autowired ApprovalService approvals;
  @Autowired WorkspaceCommitService commits;
  @Autowired PlanService plans;
  @Autowired ExecutionViewService executionViews;
  @Autowired MockMvc mvc;
  @Autowired EvaluationService evaluations;
  @Autowired EvaluationRepository evaluationRecords;
  @Autowired RunMetricsService metrics;
  @Autowired MemoryService memories;
  @Autowired EventQueryService eventQuery;
  @Autowired MeterRegistry meters;
  @Autowired MonitoringService monitoring;
  @Autowired io.agentscope.core.state.AgentStateStore agentStates;
  @MockitoSpyBean AgentModelRegistry workflowModelRegistry;
  @MockitoSpyBean AgentAssemblyPlanner assemblyPlanner;
  @MockitoSpyBean WorkspaceService workspaces;

  @Test
  void omittedRunLimitsRemainUnsetThroughExecutionAndProjection() throws Exception {
    String id = service.create(new CreateTaskRequest("整理结果", "normal", null, null, null));
    assertEquals("COMPLETED", await(id).get("STATUS"));
    var snapshot = repo.runSnapshot(id);
    assertNull(snapshot.maxRounds());
    assertNull(snapshot.maxTools());
    assertNull(snapshot.maxPerTool());
    assertNull(snapshot.maxSeconds());
    var budget = executionViews.view(id).budget();
    assertNull(budget.remainingRounds());
    assertNull(budget.remainingTools());
    assertNull(budget.remainingMs());
    assertTrue(snapshot.rounds() > 0);
  }

  @Test
  void reviewedWorkflowRunsIndependentInstancesAndJoinsBothInputs() throws Exception {
    String id =
        createDynamicWorkflow(
            true,
            3,
            "{\"requirements\":{\"maxModelCalls\":1},\"constraints\":{\"maxModelCalls\":1}}");
    var plan = plans.workflowPlan(id);
    var nodes = plans.find(id);
    assertTrue(plan.reviewRequired());
    assertNull(plan.approvedAt());
    assertEquals("PENDING", repo.run(id).get("STATUS"));
    assertEquals(3, nodes.size());
    assertEquals(nodes.get(0).definitionRef(), nodes.get(1).definitionRef());
    assertNotEquals(nodes.get(0).id(), nodes.get(1).id());
    assertEquals(1, nodes.get(0).budget().maxModelCalls());
    assertEquals(1, nodes.get(1).budget().maxModelCalls());
    assertTrue(
        nodes.stream().allMatch(n -> n.agentId() == null && plan.id().equals(n.planVersionId())));
    assertTrue(workflowRuns.workflowAttempts(id).isEmpty());
    assertTrue(repo.models(id).isEmpty());
    mvc.perform(get("/api/runs/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowPlan.approvedAt").isEmpty())
        .andExpect(jsonPath("$.workflowAttempts.length()").value(0))
        .andExpect(jsonPath("$.planNodes[2].dependencyIds.length()").value(2));
    mvc.perform(post("/api/runs/{id}/plans/{version}/approve", id, java.util.UUID.randomUUID()))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/runs/{id}/plans/{version}/approve", id, plan.id()))
        .andExpect(status().isOk());
    mvc.perform(post("/api/runs/{id}/plans/{version}/approve", id, plan.id()))
        .andExpect(status().isBadRequest());

    assertEquals("COMPLETED", await(id).get("STATUS"));
    var attempts = workflowRuns.workflowAttempts(id);
    assertEquals(3, attempts.size());
    assertEquals(3, attempts.stream().map(a -> a.agentId()).distinct().count());
    assertEquals(3, repo.models(id).size());
    var snapshots = repo.agentAssemblies(id);
    assertEquals(3, snapshots.size());
    assertEquals(
        2, snapshots.stream().filter(s -> s.snapshot().budget().nodeLimits() != null).count());
    assertEquals(
        3,
        snapshots.stream().map(s -> s.snapshot().components().input().sha256()).distinct().count());
    var mergeSnapshot =
        snapshots.stream()
            .filter(snapshot -> snapshot.agentId().equals(attempts.getLast().agentId()))
            .findFirst()
            .orElseThrow()
            .snapshot();
    assertEquals("mock", mergeSnapshot.components().model().name());
    assertEquals(3, mergeSnapshot.budget().limits().maxRounds());
    assertEquals(
        attempts.subList(0, 2).stream().map(a -> a.id().toString()).toList(),
        mergeSnapshot.components().input().sources().stream()
            .filter(
                s -> s.kind() == dev.workbench.framework.context.AgentInput.Kind.WORKFLOW_ATTEMPT)
            .map(s -> s.id())
            .toList());
    assertEquals(3, repo.run(id).get("ROUNDS"));
    assertTrue(repo.tools(id).isEmpty());
    assertEquals(2, workflowRuns.workflowHandoffs(id).size());
    assertTrue(
        workflowRuns.workflowHandoffs(id).stream()
            .allMatch(h -> h.receiverAttemptId().equals(attempts.getLast().id())));
    var first = workflowInput(attempts.get(0).agentId());
    var second = workflowInput(attempts.get(1).agentId());
    var merged = workflowInput(attempts.get(2).agentId());
    assertEquals("需求与验收分析", first.path("step").asText());
    assertEquals("约束与风险分析", second.path("step").asText());
    assertTrue(first.path("dependencies").isEmpty());
    assertTrue(second.path("dependencies").isEmpty());
    assertEquals(2, merged.path("dependencies").size());
    for (int i = 0; i < 2; i++) {
      var input = merged.path("dependencies").get(i);
      assertEquals(attempts.get(i).agentId().toString(), input.path("agentId").asText());
      assertFalse(input.path("result").asText().isBlank());
    }
    mvc.perform(get("/api/runs/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agentAssemblies.length()").value(3))
        .andExpect(jsonPath("$.workflowAttempts.length()").value(3))
        .andExpect(jsonPath("$.workflowHandoffs.length()").value(2));
  }

  @Test
  void executionViewExposesPlanFocusBlockingActionsAndStructuredEventRefs() throws Exception {
    String id = createDynamicWorkflow(true, 3);
    var plan = plans.workflowPlan(id);

    var pending = executionViews.view(id);
    assertEquals(ExecutionViewService.SCHEMA_VERSION, pending.schemaVersion());
    assertEquals(id, pending.runId());
    assertEquals("PENDING", pending.runStatus());
    assertEquals(plan.id().toString(), pending.plan().id());
    assertEquals(3, pending.nodes().size());
    assertEquals(2, pending.edges().size());
    assertTrue(pending.focus().active().isEmpty());
    assertTrue(
        pending.blocking().stream().anyMatch(item -> "PLAN_APPROVAL_REQUIRED".equals(item.code())));
    assertTrue(
        pending.availableActions().stream()
            .anyMatch(item -> "APPROVE_PLAN".equals(item.actionCode()) && item.enabled()));
    assertEquals(0, pending.counts().modelCalls());
    assertEquals(0, pending.counts().toolCalls());
    var plannedMerge = pending.nodes().getLast();
    assertFalse(plannedMerge.ready());
    var plannedDetail = executionViews.detail(id, plannedMerge.id());
    assertTrue(plannedDetail.attempts().isEmpty());
    assertTrue(plannedDetail.checks().isEmpty());
    assertEquals(2, plannedDetail.dependencies().size());

    mvc.perform(get("/api/runs/{id}/execution-view", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nodes.length()").value(3))
        .andExpect(jsonPath("$.edges.length()").value(2))
        .andExpect(jsonPath("$.blocking[0].code").value("PLAN_APPROVAL_REQUIRED"));
    mvc.perform(
            get("/api/runs/{id}/execution-view/nodes/{nodeId}", id, java.util.UUID.randomUUID()))
        .andExpect(status().isBadRequest());

    mvc.perform(post("/api/runs/{id}/plans/{version}/approve", id, plan.id()))
        .andExpect(status().isOk());
    assertEquals("COMPLETED", await(id).get("STATUS"));

    var completed = executionViews.view(id);
    assertEquals("COMPLETED", completed.runStatus());
    assertTrue(completed.nodes().stream().allMatch(node -> "COMPLETED".equals(node.status())));
    assertEquals(3, completed.counts().modelCalls());
    assertEquals(3, completed.nodes().stream().mapToInt(node -> node.attemptCount()).sum());
    assertTrue(completed.focus().active().isEmpty());
    assertTrue(completed.blocking().isEmpty());

    var mergeDetail = executionViews.detail(id, completed.nodes().getLast().id());
    assertEquals(1, mergeDetail.attempts().size());
    assertEquals(1, mergeDetail.attempts().getFirst().models().size());
    assertEquals(2, mergeDetail.inboundHandoffs().size());
    for (var handoff : mergeDetail.inboundHandoffs()) {
      var senderDetail = executionViews.detail(id, handoff.senderNodeId());
      assertEquals(id, handoff.sourceRunId());
      assertNotNull(handoff.sourceResult());
      assertEquals(
          senderDetail.attempts().stream()
              .filter(attempt -> attempt.id().equals(handoff.senderAttemptId()))
              .findFirst()
              .orElseThrow()
              .result(),
          handoff.sourceResult());
    }
    assertNotNull(mergeDetail.result());
    assertTrue(
        mergeDetail.nodeEvents().stream()
            .anyMatch(
                event -> "MODEL_STARTED".equals(event.type()) && event.modelCallId() != null));
    assertTrue(
        mergeDetail.nodeEvents().stream()
            .anyMatch(event -> "AGENT_STARTED".equals(event.type()) && event.attemptId() != null));

    var page = eventQuery.page(id, 0, 200);
    assertTrue(
        page.events().stream()
            .anyMatch(
                event ->
                    event.type() == EventType.PLAN_APPROVED
                        && plan.id().equals(event.planVersionId())));
    assertTrue(
        page.events().stream()
            .anyMatch(
                event ->
                    event.type() == EventType.AGENT_STARTED
                        && event.nodeId() != null
                        && event.attemptId() != null));
    assertTrue(page.events().stream().anyMatch(event -> event.modelCallId() != null));
  }

  @Test
  void failedAssemblySnapshotRollsBackLegacyProjectionsAndDoesNotCallModel() throws Exception {
    String id = createDynamicWorkflow(true, 3);
    var plan = plans.workflowPlan(id);
    var taskId = repo.runSnapshot(id).taskId();
    db.execute(
        "ALTER TABLE agent_assemblies ADD CONSTRAINT reject_snapshot_fixture "
            + "CHECK (POSITION('"
            + taskId
            + "' IN CAST(payload AS VARCHAR)) = 0)");
    try {
      service.approvePlan(id, plan.id());
      assertEquals("FAILED", await(id).get("STATUS"));
      assertEquals(1, repo.agents(id).size());
      assertNull(repo.agents(id).getFirst().get("DEFINITION_ID"));
      assertTrue(repo.skills(id).isEmpty());
      assertTrue(repo.agentToolGrants(id).isEmpty());
      assertTrue(repo.agentAssemblies(id).isEmpty());
      assertTrue(repo.models(id).isEmpty());
      assertTrue(repo.tools(id).isEmpty());
    } finally {
      db.execute("ALTER TABLE agent_assemblies DROP CONSTRAINT reject_snapshot_fixture");
    }
  }

  @Test
  void workflowSharesBudgetAndDoesNotCompleteAnUnfundedJoin() throws Exception {
    String id = createDynamicWorkflow(false, 2);
    assertEquals("LIMITED", await(id).get("STATUS"));
    assertEquals(2, repo.run(id).get("ROUNDS"));
    assertEquals(2, repo.models(id).size());
    assertEquals("MODEL_ROUND_LIMIT", repo.run(id).get("STOP_CAUSE"));
    assertEquals(PlanNodeStatus.LIMITED, plans.find(id).getLast().status());
    assertEquals(InvocationStatus.LIMITED, workflowRuns.workflowAttempts(id).getLast().status());
    assertNull(repo.run(id).get("RESULT"));
  }

  @Test
  void cancellingAPlanBeforeApprovalLeavesNoAttempts() throws Exception {
    String id = createDynamicWorkflow(true, 3);
    service.cancel(id);
    assertEquals("CANCELLED", await(id).get("STATUS"));
    assertTrue(workflowRuns.workflowAttempts(id).isEmpty());
    assertTrue(plans.find(id).stream().allMatch(node -> node.status() == PlanNodeStatus.SKIPPED));
    assertThrows(
        IllegalStateException.class, () -> service.approvePlan(id, plans.workflowPlan(id).id()));
  }

  @Test
  void assemblyRejectionSurfacesReasonBeforeAnyModelOrToolCall() throws Exception {
    org.mockito.Mockito.doThrow(new IllegalStateException("Agent 定义或版本不存在：text-analysis@99"))
        .when(assemblyPlanner)
        .plan(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyBoolean());
    String id = createDynamicWorkflow(false, 3);
    var run = await(id);
    assertEquals("FAILED", run.get("STATUS"));
    assertTrue(run.get("REASON").toString().contains("定义或版本不存在"));
    assertTrue(repo.models(id).isEmpty());
    assertTrue(repo.tools(id).isEmpty());
    assertEquals(1, repo.agents(id).size());
    assertEquals("FAILED", repo.agents(id).getFirst().get("STATUS"));
  }

  @Test
  void cancelledDynamicRunStaysTerminalWhenModelRespondsLater() throws Exception {
    Model delayed =
        new Model() {
          @Override
          public String getModelName() {
            return "delayed-fixture";
          }

          @Override
          public Flux<ChatResponse> stream(
              List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("晚到的模型结果").build()))
                        .build())
                .delayElements(java.time.Duration.ofMillis(700));
          }
        };
    org.mockito.Mockito.doReturn(
            new AgentModelRegistry.SelectedModel(
                new AgentScopeModelClient(delayed),
                new AgentModelRegistry.ModelReference(
                    dev.workbench.domain.ModelMode.MOCK, delayed.getModelName(), 1)))
        .when(workflowModelRegistry)
        .select(
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    request != null
                        && request.definitionRef() != null
                        && "text-analysis".equals(request.definitionRef().id())));
    String id = createDynamicWorkflow(false, 4);
    for (int i = 0; i < 100 && repo.models(id).isEmpty(); i++) Thread.sleep(50);
    assertFalse(repo.models(id).isEmpty());
    service.cancel(id);
    assertEquals("CANCELLED", await(id).get("STATUS"));
    Thread.sleep(900);
    assertEquals("CANCELLED", repo.run(id).get("STATUS"));
    assertTrue(repo.models(id).stream().noneMatch(call -> "COMPLETED".equals(call.get("STATUS"))));
    assertTrue(
        repo.events(id).stream()
            .anyMatch(event -> EventType.CANCELLED.name().equals(event.get("EVENT_TYPE"))));
  }

  @Test
  void modifiedDependencyCannotBeApprovedOrUsedForEarlyExecution() throws Exception {
    String id = createDynamicWorkflow(true, 3);
    var plan = plans.workflowPlan(id);
    var target = plans.find(id).getLast();
    db.update(
        "DELETE FROM plan_node_dependencies WHERE run_id=? AND node_id=? AND prerequisite_id=?",
        id,
        target.id().toString(),
        target.dependencyIds().getLast().toString());
    mvc.perform(post("/api/runs/{id}/plans/{version}/approve", id, plan.id()))
        .andExpect(status().isBadRequest());
    assertNull(plans.workflowPlan(id).approvedAt());
    assertTrue(repo.models(id).isEmpty());
    service.cancel(id);
  }

  @Test
  void readOnlyWorkspaceWorkflowPassesOnlyConfirmedToolEvidenceToSummary(@TempDir Path temp)
      throws Exception {
    var workspace = createWorkspaceWorkflowFixture(temp);
    String id = createWorkspaceWorkflow(workspace.id(), "[\"authorized.txt\",\"second.txt\"]");
    var plan = plans.workflowPlan(id);
    assertEquals(
        dev.workbench.framework.workflow.plan.WorkflowTemplate.Scope.WORKSPACE_READ_ONLY,
        plan.scope());
    assertTrue(plans.find(id).getFirst().workspaceRead());
    assertFalse(plans.find(id).getLast().workspaceRead());
    assertTrue(repo.models(id).isEmpty());
    assertTrue(repo.tools(id).isEmpty());

    mvc.perform(post("/api/runs/{id}/plans/{version}/approve", id, plan.id()))
        .andExpect(status().isOk());
    assertEquals("COMPLETED", await(id).get("STATUS"));
    var attempts = workflowRuns.workflowAttempts(id);
    var invocation = repo.tools(id).getFirst();
    assertEquals(1, repo.tools(id).size());
    assertEquals(attempts.getFirst().agentId().toString(), invocation.get("AGENT_ID"));
    assertEquals("COMPLETED", invocation.get("STATUS"));
    assertEquals(2, invocation.get("TOOL_VERSION"));
    var receipt = workflowRuns.workflowHandoffs(id).getFirst();
    var evidence = receipt.toolEvidence().getFirst();
    assertEquals(attempts.getLast().agentId(), receipt.receiverAgentId());
    assertEquals(invocation.get("ID"), evidence.toolCallId().toString());
    assertEquals(ToolKind.WORKSPACE_READ, evidence.tool());
    assertEquals(2, evidence.version());
    var analystInput = workflowInput(attempts.getFirst().agentId());
    assertEquals("authorized.txt", analystInput.path("authorizedFiles").get(0).asText());
    assertEquals(2, analystInput.path("authorizedFiles").size());
    var summaryInput = workflowInput(attempts.getLast().agentId());
    assertTrue(summaryInput.path("authorizedFiles").isEmpty());
    assertEquals(
        evidence.toolCallId().toString(),
        summaryInput
            .path("dependencies")
            .get(0)
            .path("toolEvidence")
            .get(0)
            .path("toolCallId")
            .asText());
    var summaryAssembly =
        repo.agentAssemblies(id).stream()
            .filter(item -> item.agentId().equals(receipt.receiverAgentId()))
            .findFirst()
            .orElseThrow()
            .snapshot()
            .components();
    assertTrue(summaryAssembly.tools().isEmpty());
    assertNull(summaryAssembly.authorization().workspaceId());
    assertEquals(
        1,
        summaryAssembly.input().sources().stream()
            .filter(
                source ->
                    source.kind() == dev.workbench.framework.context.AgentInput.Kind.TOOL_CALL)
            .count());
    var completedCheckpoints = workflowCheckpointViews(id);
    assertTrue(completedCheckpoints.size() > 0);
    assertTrue(
        completedCheckpoints.findValues("available").stream().noneMatch(node -> node.asBoolean()));
    mvc.perform(get("/api/runs/{id}", id))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.workflowHandoffs[0].toolEvidence[0].toolCallId")
                .value(evidence.toolCallId().toString()));
  }

  @Test
  void workspaceWorkflowRejectsGrantChangesAfterPlanning(@TempDir Path temp) throws Exception {
    var workspace = createWorkspaceWorkflowFixture(temp);
    String id = createWorkspaceWorkflow(workspace.id(), "[\"authorized.txt\"]");
    var plan = plans.workflowPlan(id);
    db.update(
        "UPDATE tasks SET file_scope=?,file_scopes_json=? WHERE id=?",
        "second.txt",
        "[\"second.txt\"]",
        repo.runSnapshot(id).taskId().toString());
    mvc.perform(post("/api/runs/{id}/plans/{version}/approve", id, plan.id()))
        .andExpect(status().isBadRequest());
    assertNull(plans.workflowPlan(id).approvedAt());
    assertTrue(repo.models(id).isEmpty());
    assertTrue(repo.tools(id).isEmpty());
    service.cancel(id);
  }

  @Test
  void systemCheckGatesReadOnlyWorkflowAndIsTraceable(@TempDir Path temp) throws Exception {
    var workspace = createWorkspaceWorkflowFixture(temp);
    String id = createWorkspaceWorkflow(workspace.id(), "[\"authorized.txt\"]");
    var plan = plans.workflowPlan(id);
    service.approvePlan(id, plan.id());
    assertEquals("COMPLETED", await(id).get("STATUS"));

    var nodes = plans.find(id);
    assertEquals(3, nodes.size());
    assertEquals(PlanNodeKind.SYSTEM_CHECK, nodes.get(1).kind());
    assertEquals(new SystemCheckRef("authorized-read-evidence", 1), nodes.get(1).checkRef());
    assertNull(nodes.get(1).agentId());
    assertNull(nodes.get(1).role());
    assertEquals(PlanNodeStatus.COMPLETED, nodes.get(1).status());

    var attempts = workflowRuns.workflowAttempts(id);
    assertEquals(2, attempts.size());
    assertEquals(2, repo.agents(id).size());
    // 读取节点产生一次工具轮次，另有一次结论调用；检查节点不增加任何模型调用。
    assertEquals(3, repo.models(id).size());
    var agentIds =
        attempts.stream()
            .map(attempt -> attempt.agentId().toString())
            .collect(java.util.stream.Collectors.toSet());
    assertTrue(
        repo.models(id).stream()
            .allMatch(call -> agentIds.contains(call.get("AGENT_ID").toString())));
    assertEquals(1, repo.tools(id).size());

    var checks = workflowRuns.workflowChecks(id);
    assertEquals(1, checks.size());
    var check = checks.getFirst();
    assertEquals(SystemCheckStatus.PASSED, check.status());
    assertEquals(nodes.get(1).id(), check.nodeId());
    assertEquals(1, check.attemptNo());
    assertEquals(64, check.inputsSha256().length());
    assertNotNull(check.finishedAt());
    assertEquals(1, check.inputs().size());
    assertEquals(PlanNodeKind.AGENT, check.inputs().getFirst().sourceKind());
    assertEquals(attempts.getFirst().id(), check.inputs().getFirst().sourceAttemptId());

    // 检查结论进入下游输入，并以独立收据记录接收，不伪造 Agent 尝试。
    var summaryInput = workflowInput(attempts.getLast().agentId());
    boolean checkDependency = false;
    for (var dependency : summaryInput.path("dependencies")) {
      if ("SYSTEM_CHECK".equals(dependency.path("kind").asText())) {
        checkDependency = true;
        assertEquals(check.id().toString(), dependency.path("checkId").asText());
        assertFalse(dependency.path("result").asText().isBlank());
      }
    }
    assertTrue(checkDependency);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM workflow_handoff_checks WHERE check_id=?",
            Integer.class,
            check.id().toString()));
    assertTrue(
        repo.events(id).stream()
            .anyMatch(event -> EventType.CHECK_STARTED.name().equals(event.get("EVENT_TYPE"))));
    assertTrue(
        repo.events(id).stream()
            .anyMatch(event -> EventType.CHECK_FINISHED.name().equals(event.get("EVENT_TYPE"))));
    mvc.perform(get("/api/runs/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowChecks.length()").value(1))
        .andExpect(jsonPath("$.workflowChecks[0].status").value("PASSED"))
        .andExpect(jsonPath("$.planNodes[1].kind").value("SYSTEM_CHECK"))
        .andExpect(jsonPath("$.workflowMetrics.checks").value(1))
        .andExpect(jsonPath("$.workflowMetrics.checkStatusCounts.PASSED").value(1))
        .andExpect(jsonPath("$.workflowMetrics.modelCalls").value(3));
    var report = reports.render(id);
    assertTrue(report.contains("计划、检查与调用统计"));
    assertTrue(report.contains("authorized-read-evidence@1"));
  }

  @Test
  void failedSystemCheckBlocksDownstreamWithoutAgentOrModelCalls(@TempDir Path temp)
      throws Exception {
    var workspace = createWorkspaceWorkflowFixture(temp);
    Model model =
        new Model() {
          @Override
          public String getModelName() {
            return "check-block-fixture";
          }

          @Override
          public Flux<ChatResponse> stream(
              List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("未读取文件的分析结论").build()))
                    .build());
          }
        };
    org.mockito.Mockito.doReturn(
            new AgentModelRegistry.SelectedModel(
                new AgentScopeModelClient(model),
                new AgentModelRegistry.ModelReference(
                    dev.workbench.domain.ModelMode.MOCK, model.getModelName(), 1)))
        .when(workflowModelRegistry)
        .select(
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    request != null
                        && request.definitionRef() != null
                        && "workspace-analysis".equals(request.definitionRef().id())));

    String id = createWorkspaceWorkflow(workspace.id(), "[\"authorized.txt\"]");
    var plan = plans.workflowPlan(id);
    service.approvePlan(id, plan.id());
    var run = await(id);
    assertEquals("BLOCKED", run.get("STATUS"));
    assertEquals("CHECK_FAILED", run.get("STOP_CAUSE"));
    assertTrue(run.get("REASON").toString().contains("未发现"));

    var nodes = plans.find(id);
    assertEquals(PlanNodeStatus.COMPLETED, nodes.get(0).status());
    assertEquals(PlanNodeStatus.FAILED, nodes.get(1).status());
    assertEquals(PlanNodeStatus.SKIPPED, nodes.get(2).status());
    assertEquals(1, workflowRuns.workflowAttempts(id).size());
    assertEquals(1, repo.models(id).size());
    assertTrue(repo.tools(id).isEmpty());
    var checks = workflowRuns.workflowChecks(id);
    assertEquals(1, checks.size());
    assertEquals(SystemCheckStatus.FAILED, checks.getFirst().status());
    assertTrue(checks.getFirst().summary().contains("未发现"));
    mvc.perform(get("/api/runs/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowChecks[0].status").value("FAILED"))
        .andExpect(jsonPath("$.planNodes[2].status").value("SKIPPED"));
  }

  @Test
  void systemCheckClaimRejectsDuplicateAndKeepsAgentCount(@TempDir Path temp) throws Exception {
    var workspace = createWorkspaceWorkflowFixture(temp);
    String id = createWorkspaceWorkflow(workspace.id(), "[\"authorized.txt\"]");
    var plan = plans.workflowPlan(id);
    var nodes = plans.find(id);
    workflowRuns.approveWorkflow(id, plan.id());
    repo.update(id, RunStatus.RUNNING, null, null, 0, 0, 0);
    var claim = workflowRuns.claimWorkflowNode(id, plan.id(), nodes.get(0).id(), "工作流测试");
    repo.recordAgentDefinition(
        id, claim.agentId(), claim.node().definitionRef(), claim.node().definitionSha256());
    repo.finishAgent(claim.agentId(), InvocationStatus.COMPLETED, "固定分析结果");
    var check = workflowRuns.claimWorkflowCheck(id, plan.id(), nodes.get(1).id());
    assertEquals(1, check.attemptNo());
    assertEquals(1, workflowRuns.workflowChecks(id).size());
    assertThrows(
        IllegalStateException.class,
        () -> workflowRuns.claimWorkflowCheck(id, plan.id(), nodes.get(1).id()));
    assertEquals(1, workflowRuns.workflowChecks(id).size());
    assertEquals(1, workflowRuns.workflowAttempts(id).size());
    assertEquals(1, repo.agents(id).size());
    assertTrue(repo.models(id).isEmpty());
  }

  @Test
  void systemCheckClaimRollsBackWithoutLeavingPartialCheck(@TempDir Path temp) throws Exception {
    var workspace = createWorkspaceWorkflowFixture(temp);
    String id = createWorkspaceWorkflow(workspace.id(), "[\"authorized.txt\"]");
    var plan = plans.workflowPlan(id);
    var nodes = plans.find(id);
    workflowRuns.approveWorkflow(id, plan.id());
    repo.update(id, RunStatus.RUNNING, null, null, 0, 0, 0);
    var claim = workflowRuns.claimWorkflowNode(id, plan.id(), nodes.get(0).id(), "工作流测试");
    repo.recordAgentDefinition(
        id, claim.agentId(), claim.node().definitionRef(), claim.node().definitionSha256());
    repo.finishAgent(claim.agentId(), InvocationStatus.COMPLETED, "固定分析结果");
    db.execute(
        "ALTER TABLE workflow_checks ADD CONSTRAINT reject_check_row"
            + " CHECK(node_id<>'"
            + nodes.get(1).id()
            + "')");
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> workflowRuns.claimWorkflowCheck(id, plan.id(), nodes.get(1).id()));
      assertEquals(PlanNodeStatus.PENDING, plans.find(id).get(1).status());
      assertTrue(workflowRuns.workflowChecks(id).isEmpty());
      assertEquals(1, workflowRuns.workflowAttempts(id).size());
    } finally {
      db.execute("ALTER TABLE workflow_checks DROP CONSTRAINT reject_check_row");
    }
  }

  @Test
  void unsupportedNodeKindCannotBePersistedWithoutFakingInstances(@TempDir Path temp)
      throws Exception {
    var workspace = createWorkspaceWorkflowFixture(temp);
    String id = createWorkspaceWorkflow(workspace.id(), "[\"authorized.txt\"]");
    // 数据库约束是节点类型的第一道拒绝边界；仓储读取另对未知类型显式报错。
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            db.update(
                "UPDATE plan_nodes SET node_kind='SYSTEM_ACTION' WHERE run_id=? AND sequence_no=2",
                id));
    assertTrue(workflowRuns.workflowAttempts(id).isEmpty());
    assertTrue(workflowRuns.workflowChecks(id).isEmpty());
  }

  @Test
  void dynamicWriteIsScopedAndGoesToIndependentReview(@TempDir Path temp) throws Exception {
    var workspace = createWorkspaceWorkflowFixture(temp);
    var model = workflowWriterFixture("文件由受控写入用例更新");
    org.mockito.Mockito.doReturn(
            new AgentModelRegistry.SelectedModel(
                new AgentScopeModelClient(model),
                new AgentModelRegistry.ModelReference(
                    dev.workbench.domain.ModelMode.MOCK, "workflow-write-fixture", 1)))
        .when(workflowModelRegistry)
        .select(
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    request != null
                        && request.definitionRef() != null
                        && request.role() == AgentRole.CODER
                        && request.definitionRef().id().equals("workspace-edit")));

    String id = createWriteWorkflow(workspace.id(), true);
    var plan = plans.workflowPlan(id);
    var nodes = plans.find(id);
    assertEquals(
        java.util.List.of(AgentRole.ANALYST, AgentRole.CODER, AgentRole.REVIEWER),
        nodes.stream().map(node -> node.role()).toList());
    assertFalse(nodes.getFirst().workspaceWrite());
    assertTrue(nodes.get(1).workspaceWrite());
    assertFalse(nodes.getLast().workspaceRead());
    assertTrue(repo.tools(id).isEmpty());

    service.approvePlan(id, plan.id());
    var completed = await(id);
    assertEquals("BLOCKED", completed.get("STATUS"));
    assertEquals("USER_REVIEW", completed.get("STOP_CAUSE"));
    assertEquals("USER_REVIEW", completed.get("BLOCK_CATEGORY"));
    assertTrue(completed.get("REASON").toString().contains("等待核对"));
    Path written = Path.of(workspace.workspacePath()).resolve("authorized.txt");
    assertEquals("文件由受控写入用例更新", Files.readString(written));
    assertEquals(
        "无授权", Files.readString(Path.of(workspace.workspacePath()).resolve("unlisted.txt")));
    assertEquals(
        "受控样例", Files.readString(Path.of(workspace.repositoryPath()).resolve("authorized.txt")));

    var attempts = workflowRuns.workflowAttempts(id);
    var write =
        repo.tools(id).stream()
            .filter(tool -> ToolKind.WORKSPACE_WRITE.recordName().equals(tool.get("TOOL_NAME")))
            .findFirst()
            .orElseThrow();
    assertEquals(attempts.get(1).agentId().toString(), write.get("AGENT_ID"));
    assertEquals("COMPLETED", write.get("STATUS"));
    assertTrue(
        workflowRuns.workflowHandoffs(id).stream()
            .anyMatch(
                receipt ->
                    receipt.receiverAgentId().equals(attempts.getLast().agentId())
                        && receipt.toolEvidence().stream()
                            .anyMatch(
                                evidence ->
                                    evidence.tool() == ToolKind.WORKSPACE_WRITE
                                        && evidence
                                            .toolCallId()
                                            .toString()
                                            .equals(write.get("ID")))));
    var review =
        repo.agentAssemblies(id).stream()
            .filter(recorded -> recorded.agentId().equals(attempts.getLast().agentId()))
            .findFirst()
            .orElseThrow();
    assertEquals(AgentRole.REVIEWER, review.snapshot().components().authorization().profile());
    assertTrue(review.snapshot().components().tools().isEmpty());
    assertTrue(workflowCheckpointViews(id).isEmpty());
  }

  @Test
  void uncertainDynamicWriteStopsWithoutReplayOrReview(@TempDir Path temp) throws Exception {
    var workspace = createWorkspaceWorkflowFixture(temp);
    var model = workflowWriterFixture("写入结果未知的内容");
    org.mockito.Mockito.doReturn(
            new AgentModelRegistry.SelectedModel(
                new AgentScopeModelClient(model),
                new AgentModelRegistry.ModelReference(
                    dev.workbench.domain.ModelMode.MOCK, "workflow-write-fixture", 1)))
        .when(workflowModelRegistry)
        .select(
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    request != null
                        && request.definitionRef() != null
                        && request.role() == AgentRole.CODER
                        && request.definitionRef().id().equals("workspace-edit")));
    org.mockito.Mockito.doThrow(new IllegalStateException("模拟写入已发生但结果未知"))
        .when(workspaces)
        .write(
            org.mockito.ArgumentMatchers.eq(workspace.id()),
            org.mockito.ArgumentMatchers.eq("authorized.txt"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());

    String id = createWriteWorkflow(workspace.id(), true);
    service.approvePlan(id, plans.workflowPlan(id).id());
    var run = await(id);
    assertEquals("FAILED", run.get("STATUS"));
    assertEquals("TOOL_EFFECT_UNKNOWN", run.get("STOP_CAUSE"));
    var nodes = plans.find(id);
    assertEquals(PlanNodeStatus.COMPLETED, nodes.get(0).status());
    assertEquals(PlanNodeStatus.FAILED, nodes.get(1).status());
    assertEquals(PlanNodeStatus.SKIPPED, nodes.get(2).status());
    assertEquals(2, workflowRuns.workflowAttempts(id).size());
    assertTrue(
        repo.tools(id).stream()
            .anyMatch(
                tool ->
                    ToolKind.WORKSPACE_WRITE.recordName().equals(tool.get("TOOL_NAME"))
                        && "FAILED".equals(tool.get("STATUS"))));
  }

  @Test
  void dynamicWriteRequiresExplicitGrantAndMockModeLeavesFilesIntact(@TempDir Path temp)
      throws Exception {
    var workspace = createWorkspaceWorkflowFixture(temp);
    mvc.perform(
            post("/api/tasks")
                .contentType("application/json")
                .content(workspaceWriteTaskJson(workspace.id(), false)))
        .andExpect(status().isBadRequest());
    String id = createWriteWorkflow(workspace.id(), true);
    service.approvePlan(id, plans.workflowPlan(id).id());
    var completed = await(id);
    assertEquals("BLOCKED", completed.get("STATUS"));
    assertEquals("OUTPUT_MISSING", completed.get("STOP_CAUSE"));
    assertEquals("EXECUTION_BLOCKED", completed.get("BLOCK_CATEGORY"));
    assertTrue(completed.get("REASON").toString().contains("尚无成功写入记录"));
    assertTrue(
        repo.tools(id).stream()
            .noneMatch(
                tool -> ToolKind.WORKSPACE_WRITE.recordName().equals(tool.get("TOOL_NAME"))));
    assertEquals(
        "受控样例", Files.readString(Path.of(workspace.workspacePath()).resolve("authorized.txt")));
  }

  @Test
  void modelRateLimitRetriesWithNewInstanceAndRetainsTheFailedCall() throws Exception {
    selectRetryFixture(
        "text-synthesis",
        attempt ->
            attempt == 0
                ? rateLimitResponse()
                : Flux.just(
                    ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("重试后完成汇总").build()))
                        .build()));
    String id = createDynamicWorkflow(false, 4);
    assertEquals("COMPLETED", await(id).get("STATUS"));
    assertEquals(4, repo.run(id).get("ROUNDS"));
    assertEquals(4, repo.models(id).size());
    assertEquals(2, plans.workflowPlan(id).maxAttempts());
    var attempts = workflowRuns.workflowAttempts(id);
    var first = attempts.get(2);
    var second = attempts.get(3);
    assertEquals(first.nodeId(), second.nodeId());
    assertNotEquals(first.id(), second.id());
    assertNotEquals(first.agentId(), second.agentId());
    assertEquals(1, first.number());
    assertEquals(2, second.number());
    assertEquals(InvocationStatus.FAILED, first.status());
    assertEquals(StopCause.MODEL_RATE_LIMIT, first.stopCause());
    assertEquals(InvocationStatus.COMPLETED, second.status());
    var rejected =
        repo.models(id).stream()
            .filter(call -> first.agentId().toString().equals(call.get("AGENT_ID")))
            .findFirst()
            .orElseThrow();
    assertEquals("FAILED", rejected.get("STATUS"));
    assertNull(rejected.get("INPUT_TOKENS"));
    assertNull(rejected.get("OUTPUT_TOKENS"));
    assertEquals(2, workflowInput(second.agentId()).path("dependencies").size());
    assertEquals(4, workflowRuns.workflowHandoffs(id).size());
    assertTrue(
        repo.events(id).stream()
            .anyMatch(
                event -> EventType.NODE_RETRY_SCHEDULED.name().equals(event.get("EVENT_TYPE"))));

    var retryView = executionViews.view(id);
    var retryNode =
        retryView.nodes().stream()
            .filter(node -> node.id().equals(first.nodeId().toString()))
            .findFirst()
            .orElseThrow();
    assertEquals(2, retryNode.attemptCount());
    assertEquals("COMPLETED", retryNode.status());
    var retryDetail = executionViews.detail(id, retryNode.id());
    assertEquals(2, retryDetail.attempts().size());
    assertEquals("MODEL_RATE_LIMIT", retryDetail.attempts().get(0).stopCause());
    assertEquals(1, retryDetail.attempts().get(0).models().size());
    assertEquals("FAILED", retryDetail.attempts().get(0).models().get(0).status());
  }

  @Test
  void rateLimitDoesNotRetryAnExhaustedNodeOrExceedTheAttemptCap() throws Exception {
    selectRetryFixture("text-synthesis", attempt -> rateLimitResponse());
    for (String budgets : List.of("{\"synthesis\":{\"maxModelCalls\":1}}", "{}")) {
      String id = createDynamicWorkflow(false, 8, budgets);
      var run = await(id);
      assertEquals("FAILED", run.get("STATUS"));
      assertEquals("MODEL_RATE_LIMIT", run.get("STOP_CAUSE"));
      int expected = budgets.equals("{}") ? 4 : 3;
      assertEquals(expected, repo.models(id).size());
      assertEquals(expected, workflowRuns.workflowAttempts(id).size());
    }
  }

  @Test
  void rateLimitAfterAnActualToolCallDoesNotReplayTheNode(@TempDir Path temp) throws Exception {
    var workspace = createWorkspaceWorkflowFixture(temp);
    selectRetryFixture(
        "workspace-analysis",
        attempt ->
            attempt == 0
                ? Flux.just(
                    ChatResponse.builder()
                        .content(
                            List.of(
                                ToolUseBlock.builder()
                                    .id("retry-fixture-read")
                                    .name("read_authorized_file")
                                    .input(Map.of())
                                    .content("{}")
                                    .build()))
                        .build())
                : rateLimitResponse());
    String id = createWorkspaceWorkflow(workspace.id(), "[\"authorized.txt\"]");
    service.approvePlan(id, plans.workflowPlan(id).id());
    var run = await(id);
    assertEquals("FAILED", run.get("STATUS"));
    assertEquals("MODEL_RATE_LIMIT", run.get("STOP_CAUSE"));
    assertEquals(2, repo.models(id).size());
    assertEquals(1, repo.tools(id).size());
    assertEquals(1, workflowRuns.workflowAttempts(id).size());
    assertTrue(workflowRuns.workflowHandoffs(id).isEmpty());
  }

  private void selectRetryFixture(
      String definition, java.util.function.IntFunction<Flux<ChatResponse>> response) {
    Model model =
        new Model() {
          private final AtomicInteger calls = new AtomicInteger();

          public String getModelName() {
            return "fixture-rate-limit";
          }

          public Flux<ChatResponse> stream(
              List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return response.apply(calls.getAndIncrement());
          }
        };
    org.mockito.Mockito.doReturn(
            new AgentModelRegistry.SelectedModel(
                new AgentScopeModelClient(model),
                new AgentModelRegistry.ModelReference(
                    dev.workbench.domain.ModelMode.MOCK, model.getModelName(), 1),
                RateLimitException.class::isInstance))
        .when(workflowModelRegistry)
        .select(
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    request != null
                        && request.definitionRef() != null
                        && definition.equals(request.definitionRef().id())));
  }

  private static Flux<ChatResponse> rateLimitResponse() {
    return Flux.error(new RateLimitException("模拟 HTTP 429", null, "fixture-body"));
  }

  @Test
  void nodeCallLimitsStopExecutionWhileTheRunBudgetStillHasRoom(@TempDir Path temp)
      throws Exception {
    var workspace = createWorkspaceWorkflowFixture(temp);
    for (String expected : List.of("NODE_TOOL_LIMIT", "NODE_MODEL_LIMIT")) {
      String nodeBudget =
          expected.equals("NODE_TOOL_LIMIT") ? "{\"maxToolCalls\":0}" : "{\"maxModelCalls\":1}";
      String id =
          createWorkspaceWorkflow(
              workspace.id(), "[\"authorized.txt\"]", "{\"inspect\":" + nodeBudget + "}", 5, 3);
      var plan = plans.workflowPlan(id);
      service.approvePlan(id, plan.id());
      var result = await(id);
      assertEquals("LIMITED", result.get("STATUS"));
      assertEquals(expected, result.get("STOP_CAUSE"));
      assertEquals(1, result.get("ROUNDS"));
      assertEquals(expected.equals("NODE_TOOL_LIMIT") ? 0 : 1, repo.tools(id).size());
      assertEquals(PlanNodeStatus.SKIPPED, plans.find(id).getLast().status());
    }
  }

  private static String workspaceWriteTaskJson(String workspaceId, boolean writeEnabled) {
    return """
    {"goal":"修改授权字段","workflowTemplate":"workspace-change","reviewPlan":true,"modelMode":"mock",
     "workspaceId":"%s","fileScopes":["authorized.txt"],"writeEnabled":%s,"maxRounds":8,"maxTools":5}
    """
        .formatted(workspaceId, writeEnabled);
  }

  private String createWriteWorkflow(String workspaceId, boolean writeEnabled) throws Exception {
    var response =
        mvc.perform(
                post("/api/tasks")
                    .contentType("application/json")
                    .content(workspaceWriteTaskJson(workspaceId, writeEnabled)))
            .andExpect(status().isOk())
            .andReturn();
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(response.getResponse().getContentAsString())
        .path("runId")
        .asText();
  }

  private static Model workflowWriterFixture(String replacement) {
    return new Model() {
      private final AtomicInteger rounds = new AtomicInteger();
      private final ObjectMapper json = new ObjectMapper();

      @Override
      public String getModelName() {
        return "workflow-write-fixture";
      }

      @Override
      public Flux<ChatResponse> stream(
          List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        int round = rounds.getAndIncrement();
        try {
          if (round > 1)
            return Flux.just(
                ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("已请求修改，等待审查。").build()))
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
            arguments = Map.of("expectedSha256", expected, "content", replacement);
          }
          var toolUse =
              ToolUseBlock.builder()
                  .id("workflow-fixture-" + round)
                  .name(round == 0 ? "read_authorized_file" : "write_authorized_file")
                  .input(arguments)
                  .content(json.writeValueAsString(arguments))
                  .build();
          return Flux.just(ChatResponse.builder().content(List.of(toolUse)).build());
        } catch (JsonProcessingException e) {
          return Flux.error(e);
        }
      }
    };
  }

  private String createWorkspaceWorkflow(String workspaceId, String files) throws Exception {
    return createWorkspaceWorkflow(workspaceId, files, "{}", 3, 1);
  }

  private String createWorkspaceWorkflow(
      String workspaceId, String files, String budgets, int rounds, int tools) throws Exception {
    var response =
        mvc.perform(
                post("/api/tasks")
                    .contentType("application/json")
                    .content(
                        """
                        {"goal":"分析受控文件","workflowTemplate":"workspace-analysis","reviewPlan":true,
                         "modelMode":"mock","workspaceId":"%s","fileScopes":%s,"nodeBudgets":%s,
                         "maxRounds":%d,"maxTools":%d}
                        """
                            .formatted(workspaceId, files, budgets, rounds, tools)))
            .andExpect(status().isOk())
            .andReturn();
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(response.getResponse().getContentAsString())
        .path("runId")
        .asText();
  }

  private dev.workbench.workspace.WorkspaceService.Workspace createWorkspaceWorkflowFixture(
      Path temp) throws Exception {
    Path source = Files.createDirectory(temp.resolve("repo"));
    runGit(source, "init", "-b", "main");
    runGit(source, "config", "user.email", "test@example.invalid");
    runGit(source, "config", "user.name", "Test");
    Files.writeString(source.resolve("authorized.txt"), "受控样例", StandardCharsets.UTF_8);
    Files.writeString(source.resolve("second.txt"), "第二个文件", StandardCharsets.UTF_8);
    Files.writeString(source.resolve("unlisted.txt"), "无授权", StandardCharsets.UTF_8);
    runGit(source, "add", ".");
    runGit(source, "commit", "-m", "fixture");
    return workspaces.create(source.toString(), "main");
  }

  @Test
  void workflowClaimRollsBackWhenASecondHandoffCannotBeSaved() throws Exception {
    String id = createDynamicWorkflow(true, 3);
    var plan = plans.workflowPlan(id);
    var nodes = plans.find(id);
    workflowRuns.approveWorkflow(id, plan.id());
    repo.update(id, RunStatus.RUNNING, null, null, 0, 0, 0);
    for (int i = 0; i < 2; i++) {
      var claim = workflowRuns.claimWorkflowNode(id, plan.id(), nodes.get(i).id(), "工作流测试");
      repo.recordAgentDefinition(
          id, claim.agentId(), claim.node().definitionRef(), claim.node().definitionSha256());
      repo.finishAgent(claim.agentId(), InvocationStatus.COMPLETED, "固定测试结果 " + i);
    }
    var denied = workflowRuns.workflowAttempts(id).getLast().id();
    db.execute(
        "ALTER TABLE workflow_handoffs ADD CONSTRAINT reject_test_handoff "
            + "CHECK(sender_attempt_id<>'"
            + denied
            + "')");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> workflowRuns.claimWorkflowNode(id, plan.id(), nodes.getLast().id(), "工作流测试"));
      assertEquals(PlanNodeStatus.PENDING, plans.find(id).getLast().status());
      assertEquals(2, repo.agents(id).size());
      assertEquals(2, workflowRuns.workflowAttempts(id).size());
      assertTrue(workflowRuns.workflowHandoffs(id).isEmpty());
    } finally {
      db.execute("ALTER TABLE workflow_handoffs DROP CONSTRAINT reject_test_handoff");
      repo.update(id, RunStatus.INTERRUPTED, "故障注入结束", null, 0, 0, 0);
    }
  }

  @Test
  void workflowRecoveryReusesSettledSourceAndContinuesSharedBudget() throws Exception {
    String original = startWorkflowAtSafeBoundary();
    var plan = plans.workflowPlan(original);
    var nodes = plans.find(original);
    var first = workflowRuns.claimWorkflowNode(original, plan.id(), nodes.getFirst().id(), "工作流测试");
    String modelId = dev.workbench.domain.WorkbenchIds.newId();
    repo.recordAgentDefinition(
        original, first.agentId(), first.node().definitionRef(), first.node().definitionSha256());
    repo.startModel(modelId, original, first.agentId(), ModelKind.MOCK);
    repo.finishModel(modelId, InvocationStatus.COMPLETED, 3, 2, null, 5);
    repo.update(original, RunStatus.RUNNING, null, null, 1, 0, 20);
    workflowRuns.finishWorkflowNode(original, plan.id(), first.agentId(), "已保存的分析", 1, 0, 20);
    repo.finishStoppedRun(
        new dev.workbench.store.StopCompletion(
            java.util.UUID.fromString(original),
            null,
            dev.workbench.domain.StopCause.INTERRUPTED,
            "模拟节点边界中断",
            1,
            0,
            25,
            null));
    var checkpoints = workflowCheckpointViews(original);
    assertEquals(2, checkpoints.size());
    assertFalse(checkpoints.get(0).path("available").asBoolean());
    var saved = checkpoints.get(1);
    assertTrue(saved.path("available").asBoolean());
    String checkpointId = saved.path("id").asText();
    var interruptedView = executionViews.view(original);
    assertTrue(
        interruptedView.blocking().stream()
            .anyMatch(item -> "RUN_INTERRUPTED".equals(item.code())));
    assertTrue(
        interruptedView.availableActions().stream()
            .anyMatch(
                item ->
                    "RECOVER".equals(item.actionCode())
                        && item.enabled()
                        && checkpointId.equals(item.checkpointId())));

    db.update("UPDATE agent_invocations SET result=? WHERE id=?", "已篡改", first.agentId());
    assertThrows(IllegalStateException.class, () -> service.recover(original, checkpointId));
    db.update("UPDATE agent_invocations SET result=? WHERE id=?", "已保存的分析", first.agentId());

    String recovered = service.recover(original, checkpointId);
    assertEquals("COMPLETED", await(recovered).get("STATUS"));
    assertEquals("INTERRUPTED", repo.run(original).get("STATUS"));
    assertEquals(1, repo.models(original).size());
    assertEquals(2, repo.models(recovered).size());
    assertEquals(3, repo.run(recovered).get("ROUNDS"));
    assertEquals(5, repo.reportedTokens(recovered).knownTokens());
    assertEquals(PlanNodeStatus.REUSED, plans.find(recovered).getFirst().status());
    assertEquals(1, plans.find(recovered).get(1).budget().maxModelCalls());
    assertEquals(first.agentId(), plans.find(recovered).getFirst().sourceAgentId().toString());
    assertEquals(2, workflowRuns.workflowAttempts(recovered).size());
    assertEquals(2, workflowRuns.workflowHandoffs(recovered).size());
    var merge = workflowInput(workflowRuns.workflowAttempts(recovered).getLast().agentId());
    assertEquals(original, merge.path("dependencies").get(0).path("sourceRunId").asText());
    assertEquals("已保存的分析", merge.path("dependencies").get(0).path("result").asText());
    assertThrows(IllegalStateException.class, () -> service.recover(original, checkpointId));

    var recoveredView = executionViews.view(recovered);
    assertNotNull(recoveredView.recovery());
    assertEquals(original, recoveredView.recovery().parentRunId());
    assertEquals(checkpointId, recoveredView.recovery().sourceCheckpointId());
    assertEquals(1, recoveredView.recovery().reusedNodes().size());
    assertEquals(
        first.agentId().toString(),
        recoveredView.recovery().reusedNodes().getFirst().sourceAgentId());
    assertNotNull(recoveredView.recovery().inherited());
    assertEquals(2, recoveredView.recovery().newModelCalls());
    assertEquals(
        1, recoveredView.nodes().stream().filter(node -> "REUSED".equals(node.status())).count());
  }

  @Test
  void workflowRecoveryRefusesABoundaryFollowedByAnInterruptedAttempt() throws Exception {
    String original = startWorkflowAtSafeBoundary();
    var plan = plans.workflowPlan(original);
    var claim =
        workflowRuns.claimWorkflowNode(
            original, plan.id(), plans.find(original).getFirst().id(), "工作流测试");
    workflowRuns.finishWorkflowStoppedRun(
        original,
        java.util.UUID.fromString(claim.agentId()),
        dev.workbench.domain.StopCause.INTERRUPTED,
        "模拟在途尝试中断",
        0,
        0,
        15);
    var saved = workflowCheckpointViews(original).get(0);
    assertFalse(saved.path("available").asBoolean());
    assertThrows(
        IllegalStateException.class, () -> service.recover(original, saved.path("id").asText()));
  }

  private String startWorkflowAtSafeBoundary() throws Exception {
    String id = createDynamicWorkflow(true, 3, "{\"constraints\":{\"maxModelCalls\":1}}");
    var plan = plans.workflowPlan(id);
    workflowRuns.approveWorkflow(id, plan.id());
    repo.update(id, RunStatus.RUNNING, null, null, 0, 0, 0);
    workflowRuns.checkpointBeforeFirstNode(id, plan.id(), 0);
    return id;
  }

  private com.fasterxml.jackson.databind.JsonNode workflowCheckpointViews(String id)
      throws Exception {
    var response =
        mvc.perform(get("/api/runs/{id}", id))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(response)
        .path("workflowCheckpoints");
  }

  private String createDynamicWorkflow(boolean review, int rounds) throws Exception {
    return createDynamicWorkflow(review, rounds, "{}");
  }

  private String createDynamicWorkflow(boolean review, int rounds, String budgets)
      throws Exception {
    var response =
        mvc.perform(
                post("/api/tasks")
                    .contentType("application/json")
                    .content(
                        """
                        {"goal":"工作流测试","modelMode":"mock","workflowTemplate":"dual-analysis",
                         "reviewPlan":%s,"maxRounds":%d,"maxTools":0,"nodeBudgets":%s}
                        """
                            .formatted(review, rounds, budgets)))
            .andExpect(status().isOk())
            .andReturn();
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(response.getResponse().getContentAsString())
        .path("runId")
        .asText();
  }

  private com.fasterxml.jackson.databind.JsonNode workflowInput(java.util.UUID instance)
      throws Exception {
    var state =
        agentStates
            .get(
                null, instance.toString(), "agent_state", io.agentscope.core.state.AgentState.class)
            .orElseThrow();
    assertEquals(instance.toString(), state.getSessionId());
    String content =
        state.getContext().stream()
            .map(io.agentscope.core.message.Msg::getTextContent)
            .filter(text -> text != null && text.startsWith("{\"goal\""))
            .findFirst()
            .orElseThrow();
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
  }

  @Test
  void workspaceCandidatesAreReadOnlyAndDoNotGrantTaskPermissions(@TempDir Path temp)
      throws Exception {
    Path repoDir = Files.createDirectory(temp.resolve("repo"));
    runGit(repoDir, "init", "-b", "main");
    runGit(repoDir, "config", "user.email", "test@example.invalid");
    runGit(repoDir, "config", "user.name", "Test");
    Files.writeString(repoDir.resolve("中文 name.txt"), "内容", StandardCharsets.UTF_8);
    runGit(repoDir, "add", ".");
    runGit(repoDir, "commit", "-m", "fixture");
    var workspace = workspaces.create(repoDir.toString(), "main");
    int before = repo.tasks().size();
    mvc.perform(get("/api/workspaces/{id}/files", workspace.id()).param("keyword", "中文"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.paths[0]").value("中文 name.txt"))
        .andExpect(jsonPath("$.truncated").value(false));
    mvc.perform(get("/api/workspaces/{id}/files", workspace.id()).param("keyword", "x\ny"))
        .andExpect(status().isBadRequest());
    assertEquals(before, repo.tasks().size());
    assertTrue(workspaces.status(workspace.id()).changes().isBlank());
  }

  @Test
  void agentCatalogExposesRolesWithoutGrantingTaskPermissions() throws Exception {
    mvc.perform(get("/api/agents/catalog"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].role").value("ANALYST"))
        .andExpect(jsonPath("$[1].role").value("CODER"))
        .andExpect(jsonPath("$[2].role").value("REVIEWER"))
        .andExpect(jsonPath("$[2].declaredTools.length()").value(0))
        .andExpect(jsonPath("$[0].skills[0].contentSha256").isString());
  }

  @Test
  void agentReadsOnlyTheExplicitlyAuthorizedWorkspaceFile(@TempDir Path temp) throws Exception {
    Path repoDir = Files.createDirectory(temp.resolve("repo"));
    runGit(repoDir, "init", "-b", "main");
    runGit(repoDir, "config", "user.email", "test@example.invalid");
    runGit(repoDir, "config", "user.name", "Test");
    Files.writeString(repoDir.resolve("sample.txt"), "授权内容", StandardCharsets.UTF_8);
    runGit(repoDir, "add", "sample.txt");
    runGit(repoDir, "commit", "-m", "fixture");
    var workspace = workspaces.create(repoDir.toString(), "main");
    var savedMemory =
        memories.create(workspace.id(), MemoryCategory.PROJECT_CONVENTION, "用户确认的测试背景");
    String id =
        service.create(
            new CreateTaskRequest(
                "分析文件",
                "normal",
                4,
                2,
                30,
                "mock",
                workspace.id(),
                "sample.txt",
                false,
                null,
                true));
    assertEquals("COMPLETED", await(id).get("STATUS"));
    var readerAssembly =
        repo.agentAssemblies(id).stream()
            .map(record -> record.snapshot().components())
            .filter(assembly -> assembly.authorization().profile() == AgentRole.ANALYST)
            .findFirst()
            .orElseThrow();
    assertEquals(1, readerAssembly.authorization().fileCount());
    assertFalse(readerAssembly.authorization().writeAuthorized());
    assertEquals(1, readerAssembly.tools().get(ToolKind.WORKSPACE_READ));
    assertTrue(
        readerAssembly.input().sources().stream()
            .anyMatch(
                source ->
                    source.kind() == dev.workbench.framework.context.AgentInput.Kind.MEMORY
                        && source.id().equals(savedMemory.id().toString())
                        && source.version() == savedMemory.version()));
    assertTrue(
        repo.events(id).stream()
            .anyMatch(
                e ->
                    "CHECKPOINT_SKIPPED".equals(e.get("EVENT_TYPE"))
                        && e.get("MESSAGE").toString().contains("记忆")));
    assertTrue(
        repo.events(id).stream()
            .anyMatch(
                e ->
                    "CHECKPOINT_SKIPPED".equals(e.get("EVENT_TYPE"))
                        && "MEMORY_CONTEXT".equals(e.get("REASON_CODE"))));
    assertTrue(
        meters.get("workbench.checkpoint.skipped").tag("reason", "MEMORY_CONTEXT").gauge().value()
            >= 1);
    assertEquals("读取授权文件", repo.tools(id).getFirst().get("TOOL_NAME"));
    assertEquals("授权内容", Files.readString(repoDir.resolve("sample.txt")));
    assertTrue(repo.taskDefinition(repo.runSnapshot(id).taskId().toString()).memoryEnabled());
    var recorded = memories.loadForRun(id);
    assertTrue(recorded.recorded());
    assertEquals(workspace.id(), recorded.workspaceId().toString());
    assertEquals(savedMemory.id(), recorded.references().getFirst().memoryId());
    assertEquals(1, recorded.references().getFirst().version());
    assertEquals(1, recorded.declaredCount());
    assertEquals(dev.workbench.memory.MemoryLoadIntegrity.CONSISTENT, recorded.integrity());
    String memoryReport = reports.render(id);
    assertTrue(memoryReport.contains("项目背景引用"));
    assertTrue(memoryReport.contains(savedMemory.id().toString()));
    assertTrue(memoryReport.contains("不证明模型已接收或遵循"));
    mvc.perform(get("/api/runs/{id}/memory-load", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recorded").value(true))
        .andExpect(jsonPath("$.references[0].memoryId").value(savedMemory.id().toString()))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("用户确认的测试背景"))));
    memories.update(
        workspace.id(),
        savedMemory.id().toString(),
        savedMemory.version(),
        MemoryCategory.PRECAUTION,
        "新的版本");
    assertEquals(1, memories.loadForRun(id).references().getFirst().version());
    assertTrue(
        repo.events(id).stream()
            .anyMatch(
                e ->
                    e.get("EVENT_TYPE").equals("MEMORY_LOADED")
                        && e.get("MESSAGE").toString().contains("1 条")));
    assertTrue(
        repo.events(id).stream().noneMatch(e -> e.get("MESSAGE").toString().contains("用户确认的测试背景")));
    String withoutMemory =
        service.create(
            new CreateTaskRequest(
                "不启用背景", "normal", 4, 2, 30, "mock", workspace.id(), "sample.txt", false, null));
    assertEquals("COMPLETED", await(withoutMemory).get("STATUS"));
    assertFalse(memories.loadForRun(withoutMemory).recorded());
    String protectedBody =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                java.util.Map.of(
                    "goal",
                    "限制文件读取",
                    "scenario",
                    "normal",
                    "modelMode",
                    "mock",
                    "maxRounds",
                    8,
                    "maxTools",
                    4,
                    "maxPerTool",
                    4,
                    "maxSeconds",
                    30,
                    "workspaceId",
                    workspace.id(),
                    "fileScope",
                    "sample.txt",
                    "toolBudgets",
                    java.util.Map.of("WORKSPACE_READ", 0, "SIMULATED_INSPECTION", 2)));
    String protectedResponse =
        mvc.perform(post("/api/tasks").contentType("application/json").content(protectedBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String protectedRun =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(protectedResponse)
            .get("runId")
            .asText();
    assertEquals("LIMITED", await(protectedRun).get("STATUS"));
    assertEquals("SPECIFIC_TOOL_LIMIT", repo.run(protectedRun).get("STOP_CAUSE"));
    assertTrue(repo.run(protectedRun).get("REASON").toString().contains("读取授权文件"));
    assertTrue(repo.tools(protectedRun).isEmpty());
    mvc.perform(get("/api/runs/{id}/memory-load", java.util.UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
    assertTrue(
        repo.events(withoutMemory).stream()
            .noneMatch(e -> e.get("EVENT_TYPE").equals("MEMORY_LOADED")));
    String checkpoint = repo.checkpoints(id).getFirst().get("ID").toString();
    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", id);
    assertThrows(IllegalStateException.class, () -> service.recover(id, checkpoint));
    db.update("DELETE FROM run_memory_references WHERE run_id=?", id);
    var missing = memories.loadForRun(id);
    assertEquals(1, missing.declaredCount());
    assertTrue(missing.references().isEmpty());
    assertEquals(dev.workbench.memory.MemoryLoadIntegrity.INCONSISTENT, missing.integrity());
    String damagedReport = reports.render(id);
    assertTrue(damagedReport.contains("引用数量或顺序不一致"));
    assertFalse(damagedReport.contains("装配记录显示没有选中项目背景"));
    mvc.perform(get("/api/runs/{id}/memory-load", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.declaredCount").value(1))
        .andExpect(jsonPath("$.integrity").value("INCONSISTENT"));
    db.update(
        "INSERT INTO run_memory_references(run_id,sequence_no,memory_id,memory_version,category)"
            + " VALUES(?,?,?,?,?)",
        id,
        2,
        savedMemory.id().toString(),
        1,
        MemoryCategory.PROJECT_CONVENTION.name());
    assertEquals(
        dev.workbench.memory.MemoryLoadIntegrity.INCONSISTENT, memories.loadForRun(id).integrity());
    db.update("UPDATE run_memory_references SET sequence_no=1 WHERE run_id=?", id);
    assertEquals(
        dev.workbench.memory.MemoryLoadIntegrity.CONSISTENT, memories.loadForRun(id).integrity());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.create(
                new CreateTaskRequest(
                    "越权", "normal", 4, 2, 30, "mock", workspace.id(), "../secret", false, null)));
  }

  @Test
  void writeAuthorizedTaskUsesIndependentAnalysisCodingAndReviewAgents(@TempDir Path temp)
      throws Exception {
    Path repoDir = Files.createDirectory(temp.resolve("repo"));
    runGit(repoDir, "init", "-b", "main");
    runGit(repoDir, "config", "user.email", "test@example.invalid");
    runGit(repoDir, "config", "user.name", "Test");
    Files.writeString(repoDir.resolve("sample.txt"), "原文", StandardCharsets.UTF_8);
    runGit(repoDir, "add", "sample.txt");
    runGit(repoDir, "commit", "-m", "fixture");
    var workspace = workspaces.create(repoDir.toString(), "main");
    var request =
        new CreateTaskRequest(
            "开发任务", "normal", 6, 3, 30, "mock", workspace.id(), "sample.txt", true, null);
    String id = service.create(request);
    var reviewed = await(id);
    assertEquals("BLOCKED", reviewed.get("STATUS"));
    assertEquals("USER_REVIEW", reviewed.get("STOP_CAUSE"));
    assertEquals("USER_REVIEW", reviewed.get("BLOCK_CATEGORY"));
    assertTrue(
        repo.events(id).stream()
            .anyMatch(
                e ->
                    "CHECKPOINT_SKIPPED".equals(e.get("EVENT_TYPE"))
                        && e.get("MESSAGE").toString().contains("写入任务")));
    var nodes = plans.find(id);
    assertEquals(
        java.util.List.of(AgentRole.ANALYST, AgentRole.CODER, AgentRole.REVIEWER),
        nodes.stream().map(n -> n.role()).toList());
    assertTrue(nodes.stream().allMatch(n -> n.status() == PlanNodeStatus.COMPLETED));
    assertEquals(3, repo.agents(id).size());
    assertTrue(agentStates.supportsVersioning());
    for (var instance : repo.agents(id)) {
      var state =
          agentStates
              .get(
                  null,
                  instance.get("ID").toString(),
                  "agent_state",
                  io.agentscope.core.state.AgentState.class)
              .orElseThrow();
      assertEquals(instance.get("ID").toString(), state.getSessionId());
      assertFalse(state.getContext().isEmpty());
    }
    String sessionsResponse =
        mvc.perform(get("/api/runs/{id}/agent-sessions", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].status").value("SAVED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(sessionsResponse.contains("session_id"));
    assertFalse(sessionsResponse.contains("summary"));
    assertEquals(
        java.util.List.of("ANALYST", "CODER", "REVIEWER"),
        repo.agents(id).stream().map(a -> a.get("ROLE_CODE").toString()).toList());
    assertEquals(
        java.util.List.of(4, 4, 2),
        repo.agents(id).stream().map(a -> ((Number) a.get("ROLE_VERSION")).intValue()).toList());
    var assignedSkills = repo.skills(id);
    assertEquals(3, assignedSkills.size());
    assertEquals(
        java.util.Set.of(
            dev.workbench.framework.skill.SkillId.ANALYZE_EVIDENCE,
            dev.workbench.framework.skill.SkillId.AUTHORIZED_EDIT,
            dev.workbench.framework.skill.SkillId.REVIEW_EVIDENCE),
        assignedSkills.stream().map(s -> s.skillId()).collect(java.util.stream.Collectors.toSet()));
    assertTrue(
        assignedSkills.stream()
            .allMatch(s -> s.version() == 1 && s.contentSha256().matches("[0-9a-f]{64}")));
    assertTrue(
        assignedSkills.stream()
            .allMatch(
                s ->
                    repo.agents(id).stream()
                        .anyMatch(a -> a.get("ID").equals(s.agentId().toString()))));
    mvc.perform(get("/api/runs/" + id + "/skills"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
    var grants = repo.agentToolGrants(id);
    assertEquals(3, grants.size());
    var analystTools =
        grants.stream()
            .filter(g -> g.agentId().equals(nodes.get(0).agentId()))
            .findFirst()
            .orElseThrow()
            .tools();
    var coderTools =
        grants.stream()
            .filter(g -> g.agentId().equals(nodes.get(1).agentId()))
            .findFirst()
            .orElseThrow()
            .tools();
    var reviewerTools =
        grants.stream()
            .filter(g -> g.agentId().equals(nodes.get(2).agentId()))
            .findFirst()
            .orElseThrow()
            .tools();
    assertEquals(
        java.util.Set.of(
            ToolKind.SIMULATED_INSPECTION, ToolKind.WORKSPACE_READ, ToolKind.WORKSPACE_SEARCH),
        java.util.Set.copyOf(analystTools));
    assertEquals(
        java.util.Set.of(
            ToolKind.WORKSPACE_READ, ToolKind.WORKSPACE_SEARCH, ToolKind.WORKSPACE_WRITE),
        java.util.Set.copyOf(coderTools));
    assertTrue(reviewerTools.isEmpty());
    mvc.perform(get("/api/runs/{id}/tool-grants", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
    assertTrue(reports.render(id).contains("装配时注册工具"));
    assertEquals(2, repo.tools(id).size());
    assertTrue(
        repo.tools(id).stream().allMatch(t -> Integer.valueOf(1).equals(t.get("TOOL_VERSION"))));
    assertTrue(
        grants.stream()
            .flatMap(g -> g.toolVersions().stream())
            .allMatch(t -> Integer.valueOf(1).equals(t.version())));
    assertEquals(nodes.getFirst().agentId().toString(), repo.tools(id).getFirst().get("AGENT_ID"));
    assertEquals(nodes.get(1).agentId().toString(), repo.tools(id).getLast().get("AGENT_ID"));
    var transfers =
        repo.handoffs(id).stream()
            .filter(h -> "AGENT_TRANSFER".equals(h.get("DIRECTION")))
            .map(h -> (Handoff) h.get("PAYLOAD"))
            .toList();
    assertEquals(2, transfers.size());
    assertEquals(
        2,
        repo.handoffs(id).stream()
            .filter(
                row ->
                    "AGENT_TRANSFER".equals(row.get("DIRECTION"))
                        && row.get("RECEIVER_AGENT_ID") != null)
            .count());
    assertTrue(reports.render(id).contains("接收实例已启动；不证明模型收到输入"));
    assertEquals(dev.workbench.domain.HandoffParty.CODING_AGENT, transfers.getFirst().receiver());
    assertEquals(dev.workbench.domain.HandoffParty.CODING_AGENT, transfers.getLast().sender());
    assertEquals("原文", workspaces.read(workspace.id(), "sample.txt").content());
    assertTrue(workspaces.status(workspace.id()).changes().isBlank());

    String limited =
        service.create(
            new CreateTaskRequest(
                "预算不足", "normal", 2, 3, 30, "mock", workspace.id(), "sample.txt", true, null));
    assertEquals("LIMITED", await(limited).get("STATUS"));
    assertEquals(PlanNodeStatus.COMPLETED, plans.find(limited).getFirst().status());
    assertEquals(PlanNodeStatus.LIMITED, plans.find(limited).get(1).status());
    assertEquals(PlanNodeStatus.SKIPPED, plans.find(limited).getLast().status());
    assertEquals(
        dev.workbench.domain.HandoffParty.CODING_AGENT,
        awaitSystemHandoff(limited, dev.workbench.domain.HandoffStatus.LIMITED).sender());
  }

  @Test
  void multiFileTaskUsesAuthorizedAgentScopeToolAndRejectsInvalidScopes(@TempDir Path temp)
      throws Exception {
    Path repoDir = Files.createDirectory(temp.resolve("repo"));
    runGit(repoDir, "init", "-b", "main");
    runGit(repoDir, "config", "user.email", "test@example.invalid");
    runGit(repoDir, "config", "user.name", "Test");
    Files.writeString(repoDir.resolve("a.txt"), "A", StandardCharsets.UTF_8);
    Files.writeString(repoDir.resolve("b.txt"), "B", StandardCharsets.UTF_8);
    runGit(repoDir, "add", ".");
    runGit(repoDir, "commit", "-m", "fixture");
    var workspace = workspaces.create(repoDir.toString(), "main");
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("goal", "分析两个授权文件");
    payload.put("scenario", "normal");
    payload.put("modelMode", "mock");
    payload.put("workspaceId", workspace.id());
    payload.put("fileScopes", java.util.List.of("a.txt", "b.txt"));
    payload.put("writeEnabled", true);
    payload.put("maxRounds", 6);
    payload.put("maxTools", 3);
    String response =
        mvc.perform(
                post("/api/tasks")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String runId = json.readTree(response).get("runId").asText();
    assertEquals("BLOCKED", await(runId).get("STATUS"));
    var task = repo.taskDefinition(repo.runSnapshot(runId).taskId().toString());
    assertEquals(java.util.List.of("a.txt", "b.txt"), task.fileScopes());
    assertEquals("a.txt", task.fileScope());
    assertEquals(2, repo.tools(runId).size());
    assertTrue(repo.tools(runId).stream().allMatch(t -> "COMPLETED".equals(t.get("STATUS"))));
    assertTrue(
        repo.tools(runId).stream().allMatch(t -> Integer.valueOf(2).equals(t.get("TOOL_VERSION"))));
    assertTrue(
        repo.agentToolGrants(runId).stream()
            .flatMap(g -> g.toolVersions().stream())
            .allMatch(
                t ->
                    t.version()
                        == (t.tool() == ToolKind.SIMULATED_INSPECTION
                                || t.tool() == ToolKind.WORKSPACE_SEARCH
                            ? 1
                            : 2)));
    mvc.perform(get("/api/runs/{id}/tool-grants", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].toolVersions[?(@.tool == 'WORKSPACE_READ')].version").value(2));
    assertTrue(reports.render(runId).contains("读取授权文件 v2"));
    assertTrue(
        repo.handoffs(runId).stream()
            .map(h -> (Handoff) h.get("PAYLOAD"))
            .anyMatch(
                h ->
                    h.constraints().stream()
                        .anyMatch(c -> c.contains("a.txt") && c.contains("b.txt"))));
    assertTrue(reports.render(runId).contains("授权文件：b.txt"));
    payload.put("writeEnabled", false);
    String readOnlyResponse =
        mvc.perform(
                post("/api/tasks")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String readOnlyRun = json.readTree(readOnlyResponse).get("runId").asText();
    assertEquals("COMPLETED", await(readOnlyRun).get("STATUS"));
    assertTrue(
        repo.agentToolGrants(readOnlyRun).stream()
            .flatMap(g -> g.tools().stream())
            .noneMatch(t -> t == ToolKind.WORKSPACE_WRITE));
    int before = repo.tasks().size();
    payload.put("fileScopes", java.util.List.of("a.txt", "./a.txt"));
    mvc.perform(
            post("/api/tasks")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload)))
        .andExpect(status().isBadRequest());
    payload.put("fileScopes", java.util.List.of("a.txt", "../secret.txt"));
    mvc.perform(
            post("/api/tasks")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload)))
        .andExpect(status().isBadRequest());
    payload.put("fileScopes", java.util.List.of("a.txt", "b.txt"));
    payload.put("fileScope", "a.txt");
    mvc.perform(
            post("/api/tasks")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload)))
        .andExpect(status().isBadRequest());
    assertEquals(before, repo.tasks().size());
    assertEquals("A", Files.readString(repoDir.resolve("a.txt"), StandardCharsets.UTF_8));
    assertEquals("B", Files.readString(repoDir.resolve("b.txt"), StandardCharsets.UTF_8));
  }

  @Test
  void toolGrantRecordingDistinguishesUnknownEmptyAndRollsBack() {
    String runId = java.util.UUID.randomUUID().toString();
    String agentId = java.util.UUID.randomUUID().toString();
    repo.createRun(runId, java.util.UUID.randomUUID().toString(), null, 0, 0, 4, 2, 30, 0);
    repo.startAgent(agentId, runId, "测试", AgentRole.ANALYST);
    assertTrue(repo.agentToolGrants(runId).isEmpty());
    db.execute(
        "ALTER TABLE agent_tool_grants ADD CONSTRAINT tool_grant_test_failure CHECK (agent_id <> '"
            + agentId
            + "')");
    try {
      assertThrows(
          RuntimeException.class,
          () ->
              repo.recordAgentToolGrants(agentId, java.util.Set.of(ToolKind.SIMULATED_INSPECTION)));
      assertTrue(repo.agentToolGrants(runId).isEmpty());
    } finally {
      db.execute("ALTER TABLE agent_tool_grants DROP CONSTRAINT tool_grant_test_failure");
    }
    repo.recordAgentToolGrants(agentId, java.util.Set.of());
    assertTrue(repo.agentToolGrants(runId).getFirst().tools().isEmpty());
    assertThrows(
        RuntimeException.class, () -> repo.recordAgentToolGrants(agentId, java.util.Set.of()));
    repo.finishAgent(agentId, InvocationStatus.CANCELLED, null);
    repo.update(runId, RunStatus.CANCELLED, "测试结束", null, 0, 0, 0);
  }

  @Test
  void toolStatisticsGroupByAgentAndPreserveUnknownValues() {
    String runId = java.util.UUID.randomUUID().toString();
    String otherRun = java.util.UUID.randomUUID().toString();
    String agentId = java.util.UUID.randomUUID().toString();
    String otherAgent = java.util.UUID.randomUUID().toString();
    for (String run : java.util.List.of(runId, otherRun))
      repo.createRun(run, java.util.UUID.randomUUID().toString(), null, 0, 0, 4, 2, 30, 0);
    repo.startAgent(agentId, runId, "统计", AgentRole.ANALYST);
    repo.startAgent(otherAgent, otherRun, "其他", AgentRole.ANALYST);
    String now = java.time.Instant.now().toString();
    for (Object[] row :
        java.util.List.of(
            new Object[] {runId, agentId, "工具 A", "COMPLETED", 11L},
            new Object[] {runId, agentId, "工具 A", "FAILED", 4L},
            new Object[] {runId, agentId, "工具 A", "FUTURE_STATUS", null},
            new Object[] {runId, agentId, "工具 B", "RUNNING", null},
            new Object[] {otherRun, otherAgent, "工具 A", "COMPLETED", 1000L})) {
      db.update(
          "INSERT INTO"
              + " tool_invocations(id,run_id,agent_id,tool_name,status,duration_ms,created_at,updated_at)"
              + " VALUES(?,?,?,?,?,?,?,?)",
          java.util.UUID.randomUUID().toString(),
          row[0],
          row[1],
          row[2],
          row[3],
          row[4],
          now,
          now);
    }
    var stats = metrics.agents(runId).getFirst().toolBreakdown();
    var a = stats.stream().filter(t -> t.name().equals("工具 A")).findFirst().orElseThrow();
    var b = stats.stream().filter(t -> t.name().equals("工具 B")).findFirst().orElseThrow();
    assertEquals(3, a.calls());
    assertEquals(1L, a.statusCounts().get(InvocationStatus.COMPLETED));
    assertEquals(1L, a.statusCounts().get(InvocationStatus.FAILED));
    assertEquals(1, a.unknownStatusCount());
    assertEquals(15L, a.knownDurationMs());
    assertEquals(1, a.unknownDurationCount());
    assertNull(b.knownDurationMs());
    assertEquals(1, b.unknownDurationCount());
    assertTrue(
        monitoring.overview().tools().stream()
            .anyMatch(c -> c.status() == null && "FUTURE_STATUS".equals(c.unrecognizedStatus())));
    assertTrue(meters.get("workbench.status.unknown").tag("kind", "tool").gauge().value() >= 1);
    repo.finishAgent(agentId, InvocationStatus.CANCELLED, null);
    repo.finishAgent(otherAgent, InvocationStatus.CANCELLED, null);
    repo.update(runId, RunStatus.CANCELLED, "测试结束", null, 0, 0, 0);
    repo.update(otherRun, RunStatus.CANCELLED, "测试结束", null, 0, 0, 0);
  }

  @Test
  void agentWallDurationDoesNotTreatInterruptedOrInvalidTimesAsKnown() {
    String runId = java.util.UUID.randomUUID().toString();
    String agentId = java.util.UUID.randomUUID().toString();
    repo.createRun(runId, java.util.UUID.randomUUID().toString(), null, 0, 0, 4, 2, 30, 0);
    repo.startAgent(agentId, runId, "耗时", AgentRole.ANALYST);
    String start = "2026-01-01T00:00:00Z";
    String end = "2026-01-01T00:00:02.500Z";
    db.update(
        "UPDATE agent_invocations SET status=?,created_at=?,updated_at=? WHERE id=?",
        "COMPLETED",
        start,
        end,
        agentId);
    assertEquals(2500L, metrics.agents(runId).getFirst().durationMs());
    for (String status : java.util.List.of("RUNNING", "INTERRUPTED", "FUTURE_STATUS")) {
      db.update("UPDATE agent_invocations SET status=? WHERE id=?", status, agentId);
      assertNull(metrics.agents(runId).getFirst().durationMs());
    }
    db.update(
        "UPDATE agent_invocations SET status=?,created_at=?,updated_at=? WHERE id=?",
        "COMPLETED",
        "invalid",
        end,
        agentId);
    assertNull(metrics.agents(runId).getFirst().durationMs());
    db.update(
        "UPDATE agent_invocations SET created_at=?,updated_at=? WHERE id=?", end, start, agentId);
    assertNull(metrics.agents(runId).getFirst().durationMs());
    db.update(
        "UPDATE agent_invocations SET status=?,created_at=?,updated_at=? WHERE id=?",
        "RUNNING",
        start,
        end,
        agentId);
    repo.finishAgent(agentId, InvocationStatus.CANCELLED, null);
    repo.update(runId, RunStatus.CANCELLED, "测试结束", null, 0, 0, 0);
  }

  @Test
  void projectMemoryIsScopedVersionedAndDeletedFromNewContext() throws Exception {
    String first = java.util.UUID.randomUUID().toString();
    String second = java.util.UUID.randomUUID().toString();
    for (String workspace : java.util.List.of(first, second)) {
      db.update(
          "INSERT INTO workspaces VALUES(?,?,?,?,?,?,?)",
          workspace,
          "repo",
          "path",
          "main",
          "commit",
          "branch",
          "time");
    }
    var entry = memories.create(first, MemoryCategory.PROJECT_CONVENTION, "使用 JDK 21");
    assertEquals(1, memories.context(first).size());
    assertTrue(memories.context(second).isEmpty());
    // 人为拒绝引用写入，验证已经写入的装配标记会随事务回滚。
    String failingRun = java.util.UUID.randomUUID().toString();
    db.execute(
        "ALTER TABLE run_memory_references ADD CONSTRAINT memory_reference_test_failure CHECK"
            + " (run_id <> '"
            + failingRun
            + "')");
    try {
      assertThrows(RuntimeException.class, () -> memories.loadContextAndRecord(failingRun, first));
      assertFalse(memories.loadForRun(failingRun).recorded());
    } finally {
      db.execute("ALTER TABLE run_memory_references DROP CONSTRAINT memory_reference_test_failure");
    }
    assertThrows(
        IllegalStateException.class,
        () -> memories.delete(second, entry.id().toString(), entry.version()));
    var revised =
        memories.update(
            first, entry.id().toString(), entry.version(), MemoryCategory.PRECAUTION, "检查版本兼容");
    assertEquals(2, revised.version());
    assertThrows(
        IllegalStateException.class,
        () ->
            memories.update(
                first, entry.id().toString(), entry.version(), entry.category(), "旧版本"));
    memories.delete(first, revised.id().toString(), revised.version());
    assertTrue(memories.context(first).isEmpty());
    String emptyRun = java.util.UUID.randomUUID().toString();
    assertTrue(memories.loadContextAndRecord(emptyRun, first).isEmpty());
    assertTrue(memories.loadForRun(emptyRun).recorded());
    assertTrue(memories.loadForRun(emptyRun).references().isEmpty());
    assertThrows(RuntimeException.class, () -> memories.loadContextAndRecord(emptyRun, first));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM run_memory_loads WHERE run_id=?", Integer.class, emptyRun));
    assertEquals(
        3,
        db.queryForObject(
            "SELECT COUNT(*) FROM memory_history WHERE memory_id=?",
            Integer.class,
            entry.id().toString()));
    var newest = memories.history(first, entry.id().toString(), null, 2);
    assertEquals(
        java.util.List.of(3, 2), newest.items().stream().map(item -> item.version()).toList());
    assertEquals(dev.workbench.domain.MemoryStatus.DELETED, newest.items().getFirst().status());
    assertEquals(2, newest.nextBeforeVersion());
    var oldest = memories.history(first, entry.id().toString(), newest.nextBeforeVersion(), 2);
    assertEquals(
        java.util.List.of(1), oldest.items().stream().map(item -> item.version()).toList());
    assertNull(oldest.nextBeforeVersion());
    assertThrows(
        org.springframework.dao.EmptyResultDataAccessException.class,
        () -> memories.history(second, entry.id().toString(), null, 20));
    assertThrows(
        IllegalArgumentException.class,
        () -> memories.history(first, entry.id().toString(), 0, 20));
    assertThrows(
        IllegalArgumentException.class,
        () -> memories.history(first, entry.id().toString(), null, 51));
    mvc.perform(
            get("/api/workspaces/{workspace}/memories/{id}/history", first, entry.id())
                .param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(jsonPath("$.items[0].version").value(3))
        .andExpect(jsonPath("$.items[0].status").value("DELETED"))
        .andExpect(jsonPath("$.nextBeforeVersion").value(2));
    mvc.perform(get("/api/workspaces/{workspace}/memories/{id}/history", second, entry.id()))
        .andExpect(status().isNotFound());
    assertThrows(
        IllegalArgumentException.class,
        () -> memories.create(first, MemoryCategory.PRECAUTION, " "));
    assertThrows(
        IllegalArgumentException.class,
        () -> memories.create(first, MemoryCategory.PRECAUTION, "a".repeat(301)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.create(
                new CreateTaskRequest(
                    "目标", "normal", 4, 2, 30, "mock", null, null, false, null, true)));
  }

  @Test
  void memorySearchRespectsScopeVersionsAndLiteralMatching() throws Exception {
    String first = java.util.UUID.randomUUID().toString();
    String second = java.util.UUID.randomUUID().toString();
    for (String workspace : java.util.List.of(first, second)) {
      db.update(
          "INSERT INTO workspaces VALUES(?,?,?,?,?,?,?)",
          workspace,
          "repo",
          "path",
          "main",
          "commit",
          "branch",
          "time");
    }
    var entry = memories.create(first, MemoryCategory.PROJECT_CONVENTION, "使用 ＪＤＫ 21，覆盖率 100%");
    memories.create(first, MemoryCategory.PRECAUTION, "升级 JDK 前检查兼容性");
    memories.create(second, MemoryCategory.PROJECT_CONVENTION, "其他工作区的 JDK");

    assertEquals(2, memories.search(first, "jdk", null, 20).size());
    assertEquals(1, memories.search(first, "JDK", MemoryCategory.PROJECT_CONVENTION, 20).size());
    assertEquals(entry.id(), memories.search(first, "%", null, 20).getFirst().id());
    assertTrue(memories.search(first, "_", null, 20).isEmpty());
    assertTrue(memories.search(first, "其他工作区", null, 20).isEmpty());
    assertEquals(1, memories.search(first, "", null, 1).size());
    assertThrows(
        IllegalArgumentException.class, () -> memories.search(first, "a".repeat(101), null, 20));
    assertThrows(IllegalArgumentException.class, () -> memories.search(first, null, null, 0));
    mvc.perform(
            get("/api/workspaces/{id}/memories/search", first)
                .param("q", "jdk")
                .param("category", "PROJECT_CONVENTION"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(entry.id().toString()));
    mvc.perform(get("/api/workspaces/{id}/memories/search", first).param("limit", "21"))
        .andExpect(status().isBadRequest());
    var updated =
        memories.update(
            first,
            entry.id().toString(),
            entry.version(),
            MemoryCategory.PROJECT_BACKGROUND,
            "已迁移至 Java");
    assertTrue(memories.search(first, "%", null, 20).isEmpty());
    assertEquals(1, memories.search(first, "java", null, 20).size());
    memories.delete(first, updated.id().toString(), updated.version());
    assertTrue(memories.search(first, "java", null, 20).isEmpty());
  }

  @Test
  void eventCursorPagesWithoutDuplicatesAndDoesNotMixRuns() throws Exception {
    String first = service.create(new CreateTaskRequest("事件分页", "normal", 4, 2, 30));
    String other = service.create(new CreateTaskRequest("其他运行", "normal", 4, 2, 30));
    await(first);
    await(other);
    long cursor = 0;
    var ids = new java.util.ArrayList<Long>();
    while (true) {
      var page = eventQuery.page(first, cursor, 2);
      if (page.events().isEmpty()) {
        assertEquals(cursor, page.nextCursor());
        break;
      }
      assertTrue(page.events().stream().allMatch(e -> e.runId().toString().equals(first)));
      ids.addAll(page.events().stream().map(e -> e.id()).toList());
      assertTrue(page.nextCursor() > cursor);
      cursor = page.nextCursor();
    }
    assertEquals(repo.events(first).size(), ids.size());
    assertEquals(ids.size(), new java.util.HashSet<>(ids).size());
    assertThrows(IllegalArgumentException.class, () -> eventQuery.page(first, -1, 10));
    assertThrows(IllegalArgumentException.class, () -> eventQuery.page(first, 0, 201));
    mvc.perform(get("/api/runs/" + first + "/events").param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events.length()").value(2));
    mvc.perform(get("/api/runs/" + first).param("includeEvents", "false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events.length()").value(0))
        .andExpect(jsonPath("$.agents.length()").value(2));
    mvc.perform(get("/api/runs/" + first))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events.length()").value(ids.size()));
    mvc.perform(get("/api/runs/" + first + "/events").param("after", "-1"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/runs/" + java.util.UUID.randomUUID() + "/events"))
        .andExpect(status().isNotFound());
  }

  @Test
  void eventSequenceKeepsCommitOrderWhenATransactionCommitsLate() throws Exception {
    String id = createDynamicWorkflow(true, 3);
    var firstAllocated = new java.util.concurrent.CountDownLatch(1);
    var releaseFirst = new java.util.concurrent.CountDownLatch(1);
    var firstSeq = new java.util.concurrent.atomic.AtomicLong();
    var secondSeq = new java.util.concurrent.atomic.AtomicLong();
    var secondAllocatedAt = new java.util.concurrent.atomic.AtomicLong();
    Thread first =
        new Thread(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      repo.event(id, EventType.PAUSE_REQUESTED, "先分配、晚提交的事件");
                      firstSeq.set(
                          db.queryForObject(
                              "SELECT run_seq FROM events WHERE run_id=? AND message=?",
                              Long.class,
                              id,
                              "先分配、晚提交的事件"));
                      firstAllocated.countDown();
                      try {
                        if (!releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS))
                          throw new IllegalStateException("测试未能释放第一个事务。");
                      } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                      }
                    }));
    first.start();
    assertTrue(firstAllocated.await(5, java.util.concurrent.TimeUnit.SECONDS), "第一个事务未分配序号。");
    Thread second =
        new Thread(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      // 第一个事务持有运行行锁未提交；本事务必须等待，而不是抢先插入更低序号。
                      repo.event(id, EventType.CANCEL_REQUESTED, "等待锁后提交的事件");
                      secondSeq.set(
                          db.queryForObject(
                              "SELECT run_seq FROM events WHERE run_id=? AND message=?",
                              Long.class,
                              id,
                              "等待锁后提交的事件"));
                      secondAllocatedAt.set(System.nanoTime());
                    }));
    second.start();
    Thread.sleep(300);
    long firstCommittedAt = System.nanoTime();
    releaseFirst.countDown();
    first.join(5000);
    second.join(5000);
    assertFalse(first.isAlive(), "第一个事务未结束。");
    assertFalse(second.isAlive(), "第二个事务未结束。");
    assertTrue(firstSeq.get() > 0);
    assertTrue(secondSeq.get() > firstSeq.get(), "提交序号未按提交顺序分配。");
    assertTrue(secondAllocatedAt.get() > firstCommittedAt, "第二个事务未等待第一个事务提交。");
    var page = eventQuery.page(id, 0, 200);
    var messages = page.events().stream().map(event -> event.message()).toList();
    assertTrue(
        messages.indexOf("先分配、晚提交的事件") < messages.indexOf("等待锁后提交的事件"), String.join("|", messages));
    var tail = eventQuery.page(id, firstSeq.get(), 200);
    assertTrue(tail.events().stream().anyMatch(event -> event.message().equals("等待锁后提交的事件")));
    assertTrue(tail.events().stream().noneMatch(event -> event.message().equals("先分配、晚提交的事件")));
  }

  @Test
  void reconnectBacklogBeyondOnePageDrainsWithoutDuplicates() throws Exception {
    String id = createDynamicWorkflow(true, 3);
    for (int i = 0; i < 250; i++) repo.event(id, EventType.MODEL_STARTED, "积压事件 " + i);
    long cursor = 0;
    var ids = new java.util.ArrayList<Long>();
    var seqs = new java.util.ArrayList<Long>();
    int pages = 0;
    while (true) {
      var page = eventQuery.page(id, cursor, 100);
      if (page.events().isEmpty()) break;
      pages++;
      page.events()
          .forEach(
              event -> {
                ids.add(event.id());
                seqs.add(event.runSeq());
              });
      cursor = page.nextCursor();
    }
    assertEquals(repo.events(id).size(), ids.size());
    assertEquals(ids.size(), new java.util.HashSet<>(ids).size());
    for (int i = 1; i < seqs.size(); i++) assertTrue(seqs.get(i) > seqs.get(i - 1), "事件序号未严格递增。");
    assertTrue(pages >= 3, "积压未按多页读取。");
    assertEquals(200, eventQuery.page(id, 0, 200).events().size());
  }

  @Test
  void checkpointSkipMetricUsesOnlyKnownReasonsAndAccountsForLegacyRows() throws Exception {
    double unknown =
        meters.get("workbench.checkpoint.skipped").tag("reason", "UNKNOWN").gauge().value();
    String marker = java.util.UUID.randomUUID().toString();
    db.update(
        "INSERT INTO events(run_id,event_type,message,created_at,reason_code) VALUES(?,?,?,?,?)",
        marker,
        "CHECKPOINT_SKIPPED",
        "历史事件",
        "2026-01-01T00:00:00Z",
        null);
    db.update(
        "INSERT INTO events(run_id,event_type,message,created_at,reason_code) VALUES(?,?,?,?,?)",
        marker,
        "CHECKPOINT_SKIPPED",
        "未知事件",
        "2026-01-01T00:00:00Z",
        "UNTRUSTED_CODE");
    assertEquals(
        unknown + 2,
        meters.get("workbench.checkpoint.skipped").tag("reason", "UNKNOWN").gauge().value());
    String body =
        mvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(body.contains("workbench_checkpoint_skipped"));
    assertFalse(body.contains("UNTRUSTED_CODE"));
    assertFalse(body.contains(marker));
  }

  @Test
  void summaryReportContainsOnlyAllowlistedFields() throws Exception {
    String marker = "PRIVATE_MARKER_" + java.util.UUID.randomUUID();
    String runId = service.create(new CreateTaskRequest(marker, "normal", 4, 2, 30));
    assertEquals("COMPLETED", await(runId).get("STATUS"));
    String taskId = repo.runSnapshot(runId).taskId().toString();
    db.update("UPDATE runs SET result=?,reason=? WHERE id=?", marker, marker, runId);
    db.update("UPDATE events SET message=? WHERE run_id=?", marker, runId);
    db.update("UPDATE agent_invocations SET result=? WHERE run_id=?", marker, runId);
    db.update(
        "UPDATE tool_invocations SET result=?,error_message=? WHERE run_id=?",
        marker,
        marker,
        runId);
    assertTrue(reports.render(runId).contains(marker));
    String summary = reports.renderSummary(runId);
    assertTrue(summary.contains("Agent 运行精简报告"));
    assertTrue(summary.contains("COMPLETED"));
    assertFalse(summary.contains(marker));
    assertFalse(summary.contains(runId));
    assertFalse(summary.contains(taskId));
    String response =
        mvc.perform(get("/api/runs/{id}/report", runId).param("view", "SUMMARY"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(header().string("Content-Disposition", containsString("agent-summary.html")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(response.contains(marker));
    assertFalse(response.contains(runId));
  }

  @Test
  void prometheusReportsUnknownUsageSeparatelyAndOmitsRunIds() throws Exception {
    double before =
        meters
            .get("workbench.model.tokens.unknown.calls")
            .tag("direction", "input")
            .gauge()
            .value();
    String id = service.create(new CreateTaskRequest("监控样本", "normal", 4, 2, 30));
    assertEquals("COMPLETED", await(id).get("STATUS"));
    double after =
        meters
            .get("workbench.model.tokens.unknown.calls")
            .tag("direction", "input")
            .gauge()
            .value();
    assertEquals(3, after - before);
    String body =
        mvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("workbench_model_tokens_unknown_calls")))
            .andExpect(content().string(containsString("workbench_runs")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(body.contains(id));
  }

  @Test
  void monitoringShowsRecordedRunsAndUnknownModelUsage() throws Exception {
    var before = monitoring.overview();
    long countBefore = before.runs().stream().mapToLong(r -> r.count()).sum();
    String id = service.create(new CreateTaskRequest("平台监控样本", "normal", 4, 2, 30));
    assertEquals("COMPLETED", await(id).get("STATUS"));
    var after = monitoring.overview();
    assertEquals(countBefore + 1, after.runs().stream().mapToLong(r -> r.count()).sum());
    assertEquals(before.usage().unknownInputCalls() + 3, after.usage().unknownInputCalls());
    assertTrue(
        after.models().stream()
            .anyMatch(m -> m.status() == dev.workbench.domain.InvocationStatus.COMPLETED));
    mvc.perform(get("/api/monitoring"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.usage.unknownInputCalls").isNumber())
        .andExpect(jsonPath("$.runs").isArray());
  }

  private void runGit(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.add("-C");
    command.add(dir.toString());
    command.addAll(java.util.List.of(args));
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, p.waitFor(), output);
  }

  @Test
  void approvedLocalCommitPreservesOriginalRepositoryAndRejectsStaleApproval(@TempDir Path temp)
      throws Exception {
    Path repoDir = Files.createDirectory(temp.resolve("repo"));
    runGit(repoDir, "init", "-b", "main");
    runGit(repoDir, "config", "user.email", "test@example.invalid");
    runGit(repoDir, "config", "user.name", "Test");
    Files.writeString(repoDir.resolve("sample.txt"), "原文", StandardCharsets.UTF_8);
    runGit(repoDir, "add", "sample.txt");
    runGit(repoDir, "commit", "-m", "fixture");
    var workspace = workspaces.create(repoDir.toString(), "main");
    var file = workspaces.read(workspace.id(), "sample.txt");
    workspaces.write(workspace.id(), "sample.txt", file.sha256(), "已批准的修改");
    assertThrows(
        IllegalStateException.class, () -> commits.commit(workspace.id(), "missing", "修改"));
    var approval = approvals.propose(workspace.id());
    String approvalId = approval.get("ID").toString();
    approvals.decide(approvalId, ApprovalService.Decision.APPROVE);
    var action = commits.commit(workspace.id(), approvalId, "提交已审查修改");
    assertEquals(GitActionStatus.CONFIRMED, action.status());
    assertNotEquals(workspace.baseCommit(), action.commitId());
    assertTrue(workspaces.status(workspace.id()).changes().isBlank());
    assertEquals("原文", Files.readString(repoDir.resolve("sample.txt")));
    assertEquals(workspace.baseCommit(), workspaces.status(workspace.id()).currentBase());
    assertTrue(
        approvals.list(workspace.id()).stream().anyMatch(a -> "STALE".equals(a.get("STATUS"))));

    var committedFile = workspaces.read(workspace.id(), "sample.txt");
    var changed = workspaces.write(workspace.id(), "sample.txt", committedFile.sha256(), "新的变更");
    var secondApproval = approvals.propose(workspace.id());
    approvals.decide(secondApproval.get("ID").toString(), ApprovalService.Decision.APPROVE);
    workspaces.write(workspace.id(), "sample.txt", changed.sha256(), "变更后再次修改");
    assertThrows(
        IllegalStateException.class,
        () -> commits.commit(workspace.id(), secondApproval.get("ID").toString(), "不应提交"));
    assertEquals(1, commits.list(workspace.id()).size());
  }

  @Test
  void reportEscapesUntrustedTaskText() throws Exception {
    String id =
        service.create(
            new CreateTaskRequest(
                "<script>alert('x')</script>",
                "normal",
                4,
                2,
                30,
                "mock",
                null,
                null,
                false,
                null,
                false,
                2,
                java.util.List.of("<svg onload=alert(1)>"),
                java.util.List.of("<img src=x onerror=alert(1)>")));
    await(id);
    String time = java.time.Instant.now().toString();
    db.update(
        "INSERT INTO"
            + " tool_invocations(id,run_id,agent_id,tool_name,status,duration_ms,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        java.util.UUID.randomUUID().toString(),
        id,
        repo.agents(id).getFirst().get("ID"),
        "<tool-unsafe>",
        "COMPLETED",
        3L,
        time,
        time);
    String report = reports.render(id);
    assertFalse(report.contains("<script>"));
    assertTrue(report.contains("&lt;script&gt;"));
    assertFalse(report.contains("<svg onload"));
    assertFalse(report.contains("<img src=x"));
    assertTrue(report.contains("&lt;svg onload"));
    assertTrue(report.contains("任务约束与验收要求"));
    assertTrue(report.contains("结构化交接"));
    assertTrue(report.contains("ANALYZE_EVIDENCE"));
    assertTrue(report.contains("分工具：&lt;tool-unsafe&gt;"));
    assertFalse(report.contains("<tool-unsafe>"));
    assertTrue(report.contains("未知"));
    mvc.perform(get("/api/runs/" + id + "/report"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(
                    "Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'"));
  }

  @Test
  void taskEntryIsPersistedAndDoesNotGrantToolsOrRunTests() throws Exception {
    var response =
        mvc.perform(
                post("/api/tasks")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(
                        "{\"goal\":\"梳理需求\",\"entryKind\":\"REQUIREMENT\",\"scenario\":\"normal\",\"modelMode\":\"mock\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String runId =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("runId").asText();
    assertEquals("COMPLETED", await(runId).get("STATUS"));
    var task = repo.taskDefinition(repo.runSnapshot(runId).taskId().toString());
    assertEquals(dev.workbench.domain.TaskEntryKind.REQUIREMENT, task.entryKind());
    assertFalse(task.writeEnabled());
    assertNull(task.workspaceId());
    boolean carriesEntryGuidance =
        repo.handoffs(runId).stream()
                .map(h -> (Handoff) h.get("PAYLOAD"))
                .anyMatch(h -> h.constraints().stream().anyMatch(c -> c.contains("任务入口：需求")))
            || plans.find(runId).stream().anyMatch(node -> node.label().contains("需求说明"));
    assertTrue(carriesEntryGuidance, "需求入口应通过交接或规则计划携带入口约定");
    assertTrue(reports.render(runId).contains("用户选择的入口：需求"));
    assertTrue(
        repo.agentToolGrants(runId).stream()
            .flatMap(g -> g.tools().stream())
            .noneMatch(t -> t == ToolKind.WORKSPACE_WRITE));
    int taskCount = repo.tasks().size();
    mvc.perform(
            post("/api/tasks")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"goal\":\"无效入口\",\"entryKind\":\"NOT_REAL\"}"))
        .andExpect(status().isBadRequest());
    assertEquals(taskCount, repo.tasks().size());
    String bugResponse =
        mvc.perform(
                post("/api/tasks")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(
                        "{\"goal\":\"观察问题\",\"entryKind\":\"BUG\",\"scenario\":\"normal\",\"modelMode\":\"mock\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String bugRun =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(bugResponse)
            .get("runId")
            .asText();
    assertEquals("COMPLETED", await(bugRun).get("STATUS"));
    var bugTask = repo.taskDefinition(repo.runSnapshot(bugRun).taskId().toString());
    assertEquals(dev.workbench.domain.TaskEntryKind.BUG, bugTask.entryKind());
    assertNull(bugTask.bugId());
    assertTrue(
        repo.events(bugRun).stream().noneMatch(e -> "TEST_STARTED".equals(e.get("EVENT_TYPE"))));
  }

  @Test
  void perAgentBudgetsAreIndependentAndPersistAcrossRecovery() throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    String prefix =
        "{\"goal\":\"单实例预算\",\"scenario\":\"normal\",\"modelMode\":\"mock\",\"maxRounds\":8,\"maxTools\":2,";
    String firstResponse =
        mvc.perform(
                post("/api/tasks")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(prefix + "\"maxAgentRounds\":1}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String first = json.readTree(firstResponse).get("runId").asText();
    assertEquals("LIMITED", await(first).get("STATUS"));
    assertTrue(repo.run(first).get("REASON").toString().contains("单个 Agent 模型"));
    assertEquals("AGENT_MODEL_LIMIT", repo.run(first).get("STOP_CAUSE"));
    assertTrue(reports.render(first).contains("AGENT_MODEL_LIMIT"));
    assertEquals(1, repo.models(first).size());
    assertEquals(1, repo.tools(first).size());
    String secondResponse =
        mvc.perform(
                post("/api/tasks")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(prefix + "\"maxAgentTools\":0}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second = json.readTree(secondResponse).get("runId").asText();
    assertEquals("LIMITED", await(second).get("STATUS"));
    assertTrue(repo.run(second).get("REASON").toString().contains("单个 Agent 工具"));
    assertEquals("AGENT_TOOL_LIMIT", repo.run(second).get("STOP_CAUSE"));
    assertTrue(repo.tools(second).isEmpty());
    assertTrue(
        monitoring.overview().stopCauses().stream()
            .anyMatch(
                c ->
                    c.cause() == dev.workbench.domain.StopCause.AGENT_MODEL_LIMIT
                        && c.count() >= 1));
    assertTrue(
        meters.get("workbench.run.stop.cause").tag("cause", "AGENT_MODEL_LIMIT").gauge().value()
            >= 1);
    long missingBefore = monitoring.overview().missingStopCauses();
    long invalidBefore = monitoring.overview().inconsistentStopCauses();
    db.update("UPDATE runs SET stop_cause=NULL WHERE id=?", first);
    assertEquals(missingBefore + 1, monitoring.overview().missingStopCauses());
    db.update("UPDATE runs SET stop_cause='FUTURE_CAUSE' WHERE id=?", first);
    assertEquals(invalidBefore + 1, monitoring.overview().inconsistentStopCauses());
    db.update("UPDATE runs SET stop_cause='TOOL_EFFECT_UNKNOWN' WHERE id=?", first);
    assertEquals(invalidBefore + 1, monitoring.overview().inconsistentStopCauses());
    assertTrue(
        meters.get("workbench.run.stop.cause").tag("cause", "INCONSISTENT").gauge().value() >= 1);
    db.update("UPDATE runs SET stop_cause='AGENT_MODEL_LIMIT' WHERE id=?", first);
    String thirdResponse =
        mvc.perform(
                post("/api/tasks")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(prefix + "\"maxAgentRounds\":2,\"maxAgentTools\":1}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String third = json.readTree(thirdResponse).get("runId").asText();
    assertEquals("COMPLETED", await(third).get("STATUS"));
    assertTrue(repo.models(third).size() > 2);
    assertEquals(2, repo.runSnapshot(third).maxAgentRounds());
    assertEquals(1, repo.runSnapshot(third).maxAgentTools());
    assertTrue(reports.render(third).contains("单 Agent 模型调用上限：2"));
    String checkpoint =
        repo.checkpoints(third).stream()
            .filter(c -> "BEFORE_REVIEW".equals(c.get("STAGE")))
            .findFirst()
            .orElseThrow()
            .get("ID")
            .toString();
    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", third);
    String recovered = service.recover(third, checkpoint);
    assertEquals("COMPLETED", await(recovered).get("STATUS"));
    assertEquals(2, repo.runSnapshot(recovered).maxAgentRounds());
    assertEquals(1, repo.runSnapshot(recovered).maxAgentTools());
    assertTrue(repo.runSnapshot(recovered).rounds() >= repo.runSnapshot(third).rounds());
    int before = repo.tasks().size();
    mvc.perform(
            post("/api/tasks")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(prefix + "\"maxAgentRounds\":0}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/tasks")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(prefix + "\"maxAgentTools\":-1}"))
        .andExpect(status().isBadRequest());
    assertEquals(before, repo.tasks().size());
  }

  @Test
  void invalidTaskEnumsAreRejectedBeforeTaskCreation() {
    int before = repo.tasks().size();
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(new CreateTaskRequest("目标", "unknown", 4, 2, 30)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(new CreateTaskRequest("目标", "normal", 4, 2, 30, "unknown")));
    assertEquals(before, repo.tasks().size());
  }

  @Test
  void taskCreationRollsBackWhenRunInsertFails() throws Exception {
    int tasksBefore = repo.tasks().size();
    int runsBefore = db.queryForObject("SELECT COUNT(*) FROM runs", Integer.class);
    int eventsBefore = db.queryForObject("SELECT COUNT(*) FROM events", Integer.class);
    int plansBefore = db.queryForObject("SELECT COUNT(*) FROM plan_nodes", Integer.class);
    db.execute(
        "ALTER TABLE runs ADD CONSTRAINT task_creation_test_failure"
            + " CHECK (max_agent_rounds IS NULL OR max_agent_rounds <> 19)");
    try {
      var request =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readValue(
                  "{\"goal\":\"模拟事务失败\",\"scenario\":\"normal\",\"maxAgentRounds\":19}",
                  CreateTaskRequest.class);
      assertThrows(
          org.springframework.dao.DataAccessException.class, () -> service.create(request));
      assertEquals(tasksBefore, repo.tasks().size());
      assertEquals(runsBefore, db.queryForObject("SELECT COUNT(*) FROM runs", Integer.class));
      assertEquals(eventsBefore, db.queryForObject("SELECT COUNT(*) FROM events", Integer.class));
      assertEquals(
          plansBefore, db.queryForObject("SELECT COUNT(*) FROM plan_nodes", Integer.class));
    } finally {
      db.execute("ALTER TABLE runs DROP CONSTRAINT task_creation_test_failure");
    }
  }

  private Map<String, Object> await(String id) throws Exception {
    for (int i = 0; i < 120; i++) {
      var r = repo.run(id);
      if (!r.get("STATUS").equals("PENDING")
          && !r.get("STATUS").equals("RUNNING")
          && !r.get("STATUS").equals("PAUSED")) return r;
      Thread.sleep(100);
    }
    fail("运行超时");
    return null;
  }

  private Handoff awaitSystemHandoff(String id, dev.workbench.domain.HandoffStatus status)
      throws Exception {
    for (int i = 0; i < 50; i++) {
      var found =
          repo.handoffs(id).stream()
              .map(row -> (Handoff) row.get("PAYLOAD"))
              .filter(h -> h.source() == HandoffSource.SYSTEM && h.status() == status)
              .findFirst();
      if (found.isPresent()) return found.get();
      Thread.sleep(40);
    }
    fail("未找到系统异常交接。");
    return null;
  }

  @Test
  void normalRunPersistsActualEventsAndCheckpoints() throws Exception {
    String id = service.create(new CreateTaskRequest("分析示例", "normal", 4, 2, 30));
    var r = await(id);
    assertEquals("COMPLETED", r.get("STATUS"), String.valueOf(r.get("REASON")));
    assertEquals(3, r.get("ROUNDS"));
    assertEquals(1, r.get("TOOLS"));
    assertTrue(repo.events(id).stream().anyMatch(e -> e.get("EVENT_TYPE").equals("TOOL_FINISHED")));
    assertEquals(2, repo.checkpoints(id).size());
    assertEquals("COMPLETED", repo.agents(id).getFirst().get("STATUS"));
    assertEquals(2, repo.agents(id).size());
    assertEquals("analyst", repo.agents(id).getFirst().get("DEFINITION_ID"));
    assertEquals(AgentRole.ANALYST.version(), repo.agents(id).getFirst().get("DEFINITION_VERSION"));
    assertEquals("reviewer", repo.agents(id).getLast().get("DEFINITION_ID"));
    assertThrows(
        IllegalStateException.class,
        () ->
            repo.recordAgentDefinition(
                id,
                repo.agents(id).getFirst().get("ID").toString(),
                new dev.workbench.framework.agent.definition.AgentDefinitionRef("analyst", 4),
                "a".repeat(64)));
    var nodes = plans.find(id);
    assertEquals(2, nodes.size());
    assertEquals("analyst", nodes.getFirst().definitionRef().id());
    assertEquals(
        nodes.getFirst().definitionSha256(), repo.agents(id).getFirst().get("DEFINITION_SHA256"));
    assertEquals(
        nodes.getFirst().definitionRef().version(),
        repo.agents(id).getFirst().get("DEFINITION_VERSION"));
    assertEquals("reviewer", nodes.getLast().definitionRef().id());
    db.update(
        "UPDATE plan_nodes SET definition_sha256=NULL WHERE id=?",
        nodes.getFirst().id().toString());
    assertThrows(
        IllegalStateException.class,
        () -> repo.plannedDefinitionForAgent(id, nodes.getFirst().agentId().toString()));
    db.update(
        "UPDATE plan_nodes SET definition_sha256=? WHERE id=?",
        nodes.getFirst().definitionSha256(),
        nodes.getFirst().id().toString());
    assertEquals(AgentRole.ANALYST, nodes.getFirst().role());
    assertEquals(AgentRole.ANALYST, nodes.getLast().prerequisite());
    assertTrue(nodes.stream().allMatch(n -> n.status() == PlanNodeStatus.COMPLETED));
    assertEquals(repo.agents(id).getFirst().get("ID"), nodes.getFirst().agentId().toString());
    assertNotNull(nodes.getLast().finishedAt());
    mvc.perform(get("/api/runs/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.planNodes[0].role").value("ANALYST"))
        .andExpect(jsonPath("$.planNodes[0].definitionRef.id").value("analyst"))
        .andExpect(jsonPath("$.agents[0].DEFINITION_ID").value("analyst"))
        .andExpect(jsonPath("$.planNodes[1].status").value("COMPLETED"));
    assertTrue(repo.agents(id).stream().allMatch(a -> a.get("STATUS").equals("COMPLETED")));
    var tool = repo.tools(id).getFirst();
    assertEquals("COMPLETED", tool.get("STATUS"));
    assertNotNull(tool.get("DURATION_MS"));
    assertEquals(repo.agents(id).getFirst().get("ID"), tool.get("AGENT_ID"));
    var models = repo.models(id);
    assertEquals(3, models.size());
    assertTrue(
        models.stream()
            .allMatch(
                m ->
                    m.get("STATUS").equals("COMPLETED")
                        && m.get("DURATION_MS") != null
                        && m.get("INPUT_TOKENS") == null));
    var handoffs = repo.handoffs(id);
    assertEquals(4, handoffs.size());
    var result =
        (Handoff)
            handoffs.stream()
                .filter(h -> h.get("DIRECTION").equals("AGENT_TRANSFER"))
                .findFirst()
                .orElseThrow()
                .get("PAYLOAD");
    assertEquals(HandoffSource.MOCK_MODEL, result.source());
    assertEquals(id, result.evidenceRunId());
    assertFalse(result.evidenceEventIds().isEmpty());
    assertEquals(repo.agents(id).getLast().get("ID"), result.recipientAgentId());
    var transferRow =
        handoffs.stream()
            .filter(h -> h.get("DIRECTION").equals("AGENT_TRANSFER"))
            .findFirst()
            .orElseThrow();
    assertEquals(transferRow.get("ID"), result.handoffId().toString());
    assertEquals(1, result.schemaVersion());
    repo.handoff(HandoffDirection.AGENT_TRANSFER, result);
    assertEquals(handoffs.size(), repo.handoffs(id).size());
    var conflict =
        new Handoff(
            result.taskId(),
            result.runId(),
            result.agentId(),
            result.sender(),
            result.receiver(),
            result.goal(),
            result.constraints(),
            result.status(),
            "不同内容",
            result.evidenceRunId(),
            result.evidenceEventIds(),
            result.source(),
            result.recipientAgentId(),
            result.handoffId(),
            result.schemaVersion());
    assertThrows(
        IllegalStateException.class, () -> repo.handoff(HandoffDirection.AGENT_TRANSFER, conflict));
    var invalidEvidence =
        new Handoff(
            result.taskId(),
            result.runId(),
            result.agentId(),
            result.sender(),
            result.receiver(),
            result.goal(),
            result.constraints(),
            result.status(),
            result.summary(),
            result.evidenceRunId(),
            java.util.List.of(Long.MAX_VALUE),
            result.source(),
            result.recipientAgentId());
    assertThrows(
        IllegalArgumentException.class,
        () -> repo.handoff(HandoffDirection.AGENT_TRANSFER, invalidEvidence));
    assertThrows(
        IllegalArgumentException.class, () -> repo.handoff(HandoffDirection.RESULT, result));
  }

  @Test
  void taskRequirementsPersistAndReachStructuredHandoff() throws Exception {
    String id =
        service.create(
            new CreateTaskRequest(
                "实现目标",
                "normal",
                4,
                2,
                30,
                "mock",
                null,
                null,
                false,
                null,
                false,
                2,
                java.util.List.of("  限定范围  "),
                java.util.List.of("回归需要真实证据")));
    assertEquals("COMPLETED", await(id).get("STATUS"));
    var task = repo.taskDefinition(repo.runSnapshot(id).taskId().toString());
    assertEquals(java.util.List.of("限定范围"), task.constraints());
    assertEquals(java.util.List.of("回归需要真实证据"), task.acceptanceCriteria());
    var transfer =
        (Handoff)
            repo.handoffs(id).stream()
                .filter(h -> "AGENT_TRANSFER".equals(h.get("DIRECTION")))
                .findFirst()
                .orElseThrow()
                .get("PAYLOAD");
    assertTrue(transfer.constraints().contains("用户约束：限定范围"));
    assertTrue(transfer.constraints().contains("待验证验收条件：回归需要真实证据"));
    int before = repo.tasks().size();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.create(
                new CreateTaskRequest(
                    "目标",
                    "normal",
                    4,
                    2,
                    30,
                    "mock",
                    null,
                    null,
                    false,
                    null,
                    false,
                    2,
                    java.util.Collections.nCopies(11, "约束"),
                    java.util.List.of())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.create(
                new CreateTaskRequest(
                    "目标",
                    "normal",
                    4,
                    2,
                    30,
                    "mock",
                    null,
                    null,
                    false,
                    null,
                    false,
                    2,
                    java.util.List.of(),
                    java.util.List.of("a".repeat(301)))));
    assertEquals(before, repo.tasks().size());
  }

  @Test
  void budgetStopsBeforeReviewAndRetainsAnalysis() throws Exception {
    String id = service.create(new CreateTaskRequest("预算不足", "normal", 2, 2, 30));
    assertEquals("LIMITED", await(id).get("STATUS"));
    assertEquals("COMPLETED", repo.agents(id).getFirst().get("STATUS"));
    assertEquals("LIMITED", repo.agents(id).getLast().get("STATUS"));
    assertEquals(
        dev.workbench.domain.HandoffParty.REVIEW_AGENT,
        awaitSystemHandoff(id, dev.workbench.domain.HandoffStatus.LIMITED).sender());
    assertEquals(PlanNodeStatus.COMPLETED, plans.find(id).getFirst().status());
    assertEquals(PlanNodeStatus.LIMITED, plans.find(id).getLast().status());
    assertTrue(
        repo.handoffs(id).stream().anyMatch(h -> h.get("DIRECTION").equals("AGENT_TRANSFER")));
  }

  @Test
  void toolLimitPreventsInvocation() throws Exception {
    String id = service.create(new CreateTaskRequest("测试限制", "normal", 4, 0, 30));
    var r = await(id);
    assertEquals("LIMITED", r.get("STATUS"));
    assertEquals(0, r.get("TOOLS"));
    assertFalse(repo.events(id).stream().anyMatch(e -> e.get("EVENT_TYPE").equals("TOOL_STARTED")));
    assertTrue(repo.tools(id).isEmpty());
  }

  @Test
  void individualToolLimitStopsBeforeTheToolStarts() throws Exception {
    String id =
        service.create(
            new CreateTaskRequest(
                "单工具限额", "normal", 4, 2, 30, "mock", null, null, false, null, false, 0));
    assertEquals("LIMITED", await(id).get("STATUS"));
    assertEquals(0, repo.runSnapshot(id).tools());
    assertEquals(0, repo.runSnapshot(id).maxPerTool());
    assertTrue(repo.tools(id).isEmpty());
    assertTrue(repo.run(id).get("REASON").toString().contains("单工具"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.create(
                new CreateTaskRequest(
                    "无效限额", "normal", 4, 2, 30, "mock", null, null, false, null, false, 21)));
  }

  @Test
  void individualToolUsageIncludesRecoveryAncestorsAndKeepsTypesSeparate() {
    String task = java.util.UUID.randomUUID().toString();
    String original = java.util.UUID.randomUUID().toString();
    String recovered = java.util.UUID.randomUUID().toString();
    repo.createRun(original, task, null, 0, 0, 4, 4, 1, 30, 0);
    repo.startTool(
        java.util.UUID.randomUUID().toString(),
        original,
        java.util.UUID.randomUUID().toString(),
        ToolKind.SIMULATED_INSPECTION.recordName());
    repo.createRun(recovered, task, original, 0, 1, 4, 4, 1, 30, 0);
    assertEquals(1, repo.countToolCalls(recovered, ToolKind.SIMULATED_INSPECTION));
    assertEquals(0, repo.countToolCalls(recovered, ToolKind.WORKSPACE_READ));
    assertEquals(1, repo.runSnapshot(recovered).maxPerTool());
  }

  @Test
  void tokenBudgetStopsWhenModelUsageIsUnknown() throws Exception {
    String id =
        service.create(
            new CreateTaskRequest(
                "用量未知预算",
                "normal",
                4,
                2,
                30,
                "mock",
                null,
                null,
                false,
                null,
                false,
                2,
                java.util.List.of(),
                java.util.List.of(),
                100L));
    assertEquals("LIMITED", await(id).get("STATUS"));
    assertTrue(repo.run(id).get("REASON").toString().contains("用量未知"));
    assertTrue(repo.tools(id).isEmpty());
    assertEquals(1, repo.models(id).size());
    assertNull(repo.models(id).getFirst().get("INPUT_TOKENS"));
    assertEquals(1, repo.reportedTokens(id).unknownCalls());
    assertEquals(100L, repo.runSnapshot(id).maxReportedTokens());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.create(
                new CreateTaskRequest(
                    "非法预算",
                    "normal",
                    4,
                    2,
                    30,
                    "mock",
                    null,
                    null,
                    false,
                    null,
                    false,
                    2,
                    java.util.List.of(),
                    java.util.List.of(),
                    0L)));
  }

  @Test
  void tokenUsageIncludesKnownAndUnknownAcrossRecoveryLineage() {
    String task = java.util.UUID.randomUUID().toString();
    String first = java.util.UUID.randomUUID().toString();
    String next = java.util.UUID.randomUUID().toString();
    String model1 = java.util.UUID.randomUUID().toString();
    String model2 = java.util.UUID.randomUUID().toString();
    repo.createRun(first, task, null, 0, 0, 4, 2, 2, 100L, 30, 0);
    repo.startModel(model1, first, java.util.UUID.randomUUID().toString(), ModelKind.MOCK);
    repo.finishModel(model1, InvocationStatus.COMPLETED, 10, 5, null, 1);
    repo.createRun(next, task, first, 1, 0, 4, 2, 2, 100L, 30, 0);
    repo.startModel(model2, next, java.util.UUID.randomUUID().toString(), ModelKind.MOCK);
    repo.finishModel(model2, InvocationStatus.FAILED, null, null, "未知", 1);
    assertEquals(15, repo.reportedTokens(next).knownTokens());
    assertEquals(1, repo.reportedTokens(next).unknownCalls());
    assertEquals(100L, repo.runSnapshot(next).maxReportedTokens());
  }

  @Test
  void specificToolLimitIsEnforcedAndCommonLimitHasPrecedence() throws Exception {
    String body =
        """
        {"goal":"指定工具上限","scenario":"repeat","modelMode":"mock",
         "maxRounds":8,"maxTools":5,"maxPerTool":5,"maxSeconds":30,
         "toolBudgets":{"SIMULATED_INSPECTION":1}}
        """;
    String response =
        mvc.perform(post("/api/tasks").contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("runId").asText();
    assertEquals("LIMITED", await(id).get("STATUS"));
    assertEquals("SPECIFIC_TOOL_LIMIT", repo.run(id).get("STOP_CAUSE"));
    assertEquals(1, repo.tools(id).size());
    assertTrue(reports.render(id).contains("模拟检查工具：1 次"));
    assertTrue(reports.renderSummary(id).contains("模拟检查工具：1 次"));
    assertEquals(
        1,
        repo.taskDefinition(repo.runSnapshot(id).taskId().toString())
            .toolBudgets()
            .get(ToolKind.SIMULATED_INSPECTION));
    mvc.perform(get("/api/runs/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.toolBudgets.SIMULATED_INSPECTION").value(1));
    assertTrue(
        meters.get("workbench.run.stop.cause").tag("cause", "SPECIFIC_TOOL_LIMIT").gauge().value()
            >= 1);
    String common = body.replace("\"maxPerTool\":5", "\"maxPerTool\":1");
    String commonResponse =
        mvc.perform(post("/api/tasks").contentType("application/json").content(common))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String commonId =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(commonResponse)
            .get("runId")
            .asText();
    assertEquals("LIMITED", await(commonId).get("STATUS"));
    assertEquals("PER_TOOL_LIMIT", repo.run(commonId).get("STOP_CAUSE"));
  }

  @Test
  void searchBudgetIsAcceptedPersistedAndReportedWithoutGrantingWorkspace() throws Exception {
    String body = "{\"goal\":\"搜索预算\",\"toolBudgets\":{\"WORKSPACE_SEARCH\":0}}";
    String response =
        mvc.perform(post("/api/tasks").contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String runId =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("runId").asText();
    await(runId);
    assertEquals(
        0,
        repo.taskDefinition(repo.runSnapshot(runId).taskId().toString())
            .toolBudgets()
            .get(ToolKind.WORKSPACE_SEARCH));
    assertTrue(reports.render(runId).contains("搜索授权文件：0 次"));
    assertTrue(
        repo.agentToolGrants(runId).stream()
            .flatMap(g -> g.tools().stream())
            .noneMatch(t -> t == ToolKind.WORKSPACE_SEARCH));
  }

  @Test
  void specificToolLimitCarriesAcrossRecoveryAndRejectsInvalidPolicies() throws Exception {
    String body =
        """
        {"goal":"工具策略恢复","scenario":"normal","modelMode":"mock",
         "maxRounds":8,"maxTools":5,"maxPerTool":5,"maxSeconds":30,
         "toolBudgets":{"SIMULATED_INSPECTION":1}}
        """;
    String response =
        mvc.perform(post("/api/tasks").contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String original =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("runId").asText();
    assertEquals("COMPLETED", await(original).get("STATUS"));
    var checkpoint =
        repo.checkpoints(original).stream()
            .filter(c -> "BEFORE_TOOL".equals(c.get("STAGE")))
            .findFirst()
            .orElseThrow();
    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", original);
    String restored = service.recover(original, checkpoint.get("ID").toString());
    assertEquals("LIMITED", await(restored).get("STATUS"));
    assertEquals("SPECIFIC_TOOL_LIMIT", repo.run(restored).get("STOP_CAUSE"));
    assertTrue(repo.tools(restored).isEmpty());
    assertEquals(1, repo.countToolCalls(restored, ToolKind.SIMULATED_INSPECTION));
    assertEquals(
        repo.toolBudgets(repo.runSnapshot(original).taskId().toString()),
        repo.toolBudgets(repo.runSnapshot(restored).taskId().toString()));
    int before = repo.tasks().size();
    for (String invalid :
        java.util.List.of(
            "{\"SIMULATED_INSPECTION\":-1}",
            "{\"SIMULATED_INSPECTION\":21}",
            "{\"SIMULATED_INSPECTION\":1.5}",
            "{\"SIMULATED_INSPECTION\":\"1\"}",
            "{\"SIMULATED_INSPECTION\":null}",
            "{\"UNKNOWN_TOOL\":1}",
            "{\"SIMULATED_INSPECTION\":1,\"SIMULATED_INSPECTION\":2}")) {
      String bad = "{\"goal\":\"invalid\",\"toolBudgets\":" + invalid + "}";
      mvc.perform(post("/api/tasks").contentType("application/json").content(bad))
          .andExpect(status().isBadRequest());
    }
    assertEquals(before, repo.tasks().size());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "INSERT INTO task_tool_budgets_v2(task_id,tool_kind,policy_version,max_calls)"
                    + " VALUES(?,?,?,?)",
                java.util.UUID.randomUUID().toString(),
                "SIMULATED_INSPECTION",
                2,
                1));
  }

  @Test
  void roleSpecificLimitsArePersistedAndEnforcedWithoutGrantingTools() throws Exception {
    String body =
        """
        {"goal":"按角色模型限制","scenario":"normal","modelMode":"mock",
         "maxRounds":8,"maxTools":3,"maxSeconds":30,
         "roleBudgets":{"ANALYST":{"maxModelCalls":1},"REVIEWER":{"maxModelCalls":3}}}
        """;
    String response =
        mvc.perform(post("/api/tasks").contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("runId").asText();
    assertEquals("LIMITED", await(id).get("STATUS"));
    assertEquals("ROLE_MODEL_LIMIT", repo.run(id).get("STOP_CAUSE"));
    assertEquals(1, repo.models(id).size());
    assertEquals(PlanNodeStatus.SKIPPED, plans.find(id).getLast().status());
    assertTrue(reports.render(id).contains("分析 Agent：模型 1，工具 未设置"));
    assertTrue(reports.renderSummary(id).contains("分析 Agent：模型 1，工具 未设置"));
    var task = repo.taskDefinition(repo.runSnapshot(id).taskId().toString());
    assertEquals(
        new dev.workbench.domain.RoleBudget(1, null), task.roleBudgets().get(AgentRole.ANALYST));
    assertEquals(
        new dev.workbench.domain.RoleBudget(3, null), task.roleBudgets().get(AgentRole.REVIEWER));
    mvc.perform(get("/api/runs/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roleBudgets.ANALYST.maxModelCalls").value(1))
        .andExpect(jsonPath("$.roleBudgets.REVIEWER.maxModelCalls").value(3));
    assertTrue(
        meters.get("workbench.run.stop.cause").tag("cause", "ROLE_MODEL_LIMIT").gauge().value()
            >= 1);

    String toolBody =
        """
        {"goal":"按角色工具限制","scenario":"normal","modelMode":"mock",
         "maxRounds":8,"maxTools":3,"maxSeconds":30,
         "roleBudgets":{"ANALYST":{"maxToolCalls":0}}}
        """;
    String toolResponse =
        mvc.perform(post("/api/tasks").contentType("application/json").content(toolBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String toolId =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(toolResponse)
            .get("runId")
            .asText();
    assertEquals("LIMITED", await(toolId).get("STATUS"));
    assertEquals("ROLE_TOOL_LIMIT", repo.run(toolId).get("STOP_CAUSE"));
    assertTrue(repo.tools(toolId).isEmpty());

    String precedenceBody =
        """
        {"goal":"统一限额优先","scenario":"normal","modelMode":"mock",
         "maxRounds":8,"maxTools":3,"maxSeconds":30,"maxAgentRounds":1,
         "roleBudgets":{"ANALYST":{"maxModelCalls":1}}}
        """;
    String precedenceResponse =
        mvc.perform(post("/api/tasks").contentType("application/json").content(precedenceBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String precedenceId =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(precedenceResponse)
            .get("runId")
            .asText();
    assertEquals("LIMITED", await(precedenceId).get("STATUS"));
    assertEquals("AGENT_MODEL_LIMIT", repo.run(precedenceId).get("STOP_CAUSE"));
  }

  @Test
  void roleLimitsSurviveRecoveryAndInvalidPoliciesAreRejected() throws Exception {
    String body =
        """
        {"goal":"角色策略恢复","scenario":"normal","modelMode":"mock",
         "maxRounds":8,"maxTools":3,"maxSeconds":30,
         "roleBudgets":{"ANALYST":{"maxModelCalls":2},"REVIEWER":{"maxModelCalls":1}}}
        """;
    String response =
        mvc.perform(post("/api/tasks").contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String original =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("runId").asText();
    assertEquals("COMPLETED", await(original).get("STATUS"));
    var checkpoint =
        repo.checkpoints(original).stream()
            .filter(c -> "BEFORE_REVIEW".equals(c.get("STAGE")))
            .findFirst()
            .orElseThrow();
    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", original);
    String restored = service.recover(original, checkpoint.get("ID").toString());
    assertEquals("COMPLETED", await(restored).get("STATUS"));
    assertEquals(1, repo.agents(restored).size());
    assertEquals(1, repo.models(restored).size());
    assertEquals(
        repo.roleBudgets(repo.runSnapshot(original).taskId().toString()),
        repo.roleBudgets(repo.runSnapshot(restored).taskId().toString()));
    int before = repo.tasks().size();
    for (String invalid :
        java.util.List.of(
            "{\"ANALYST\":{\"maxModelCalls\":0}}",
            "{\"ANALYST\":{\"maxToolCalls\":-1}}",
            "{\"ANALYST\":{\"maxModelCalls\":1.5}}",
            "{\"ANALYST\":{\"maxModelCalls\":\"1\"}}",
            "{\"ANALYST\":{\"maxModelCalls\":1,\"maxModleCalls\":2}}",
            "{\"ANALYST\":{\"maxModelCalls\":1,\"maxModelCalls\":2}}",
            "{\"ANALYST\":null}",
            "{\"ANALYST\":{\"maxModelCalls\":1},\"ANALYST\":{\"maxModelCalls\":2}}",
            "{\"NOT_A_ROLE\":{\"maxModelCalls\":1}}")) {
      String bad = "{\"goal\":\"invalid\",\"roleBudgets\":" + invalid + "}";
      mvc.perform(post("/api/tasks").contentType("application/json").content(bad))
          .andExpect(status().isBadRequest());
    }
    assertEquals(before, repo.tasks().size());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "INSERT INTO"
                    + " task_role_budgets(task_id,role_code,policy_version,max_model_calls,max_tool_calls)"
                    + " VALUES(?,?,?,?,?)",
                java.util.UUID.randomUUID().toString(),
                "ANALYST",
                2,
                1,
                null));
  }

  @Test
  void optedInIdenticalToolLimitStopsRepeatedModelCalls() throws Exception {
    var request =
        new CreateTaskRequest(
            "重复工具测试",
            "repeat",
            8,
            5,
            30,
            "mock",
            null,
            null,
            false,
            null,
            false,
            5,
            java.util.List.of(),
            java.util.List.of(),
            null,
            2);
    String id = service.create(request);
    assertEquals("LIMITED", await(id).get("STATUS"));
    assertTrue(repo.run(id).get("REASON").toString().contains("连续相同"));
    assertEquals(2, repo.tools(id).size());
    assertEquals(2, repo.runSnapshot(id).tools());
    assertEquals(2, repo.runSnapshot(id).maxIdenticalTools());
    assertEquals(
        2,
        db.queryForObject(
            "SELECT COUNT(*) FROM tool_attempt_fingerprints WHERE run_id=?", Integer.class, id));
    assertEquals(PlanNodeStatus.SKIPPED, plans.find(id).getLast().status());

    String disabled =
        service.create(
            new CreateTaskRequest(
                "未开启重复保护", "repeat", 8, 5, 30, "mock", null, null, false, null, false, 5));
    assertEquals("COMPLETED", await(disabled).get("STATUS"));
    assertEquals(4, repo.tools(disabled).size());
    assertNull(repo.runSnapshot(disabled).maxIdenticalTools());
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM tool_attempt_fingerprints WHERE run_id=?",
            Integer.class,
            disabled));
  }

  @Test
  void repeatedCallFingerprintTracksRecoveryAndResetsOnDifferentInput() {
    String task = java.util.UUID.randomUUID().toString();
    String first = java.util.UUID.randomUUID().toString();
    String next = java.util.UUID.randomUUID().toString();
    String a = dev.workbench.framework.tool.ToolFingerprint.of(ToolKind.WORKSPACE_READ, "a", "bc");
    String b = dev.workbench.framework.tool.ToolFingerprint.of(ToolKind.WORKSPACE_READ, "ab", "c");
    assertNotEquals(a, b);
    assertNotEquals(
        dev.workbench.framework.tool.ToolFingerprint.of(ToolKind.WORKSPACE_READ, (String) null),
        dev.workbench.framework.tool.ToolFingerprint.of(ToolKind.WORKSPACE_READ, ""));
    repo.createRun(first, task, null, 0, 0, 8, 5, 5, null, 2, 30, 0, false);
    String agent = java.util.UUID.randomUUID().toString();
    repo.startTool(
        java.util.UUID.randomUUID().toString(), first, agent, ToolKind.WORKSPACE_READ, a);
    repo.startTool(
        java.util.UUID.randomUUID().toString(), first, agent, ToolKind.WORKSPACE_READ, a);
    repo.createRun(next, task, first, 0, 2, 8, 5, 5, null, 2, 30, 0, false);
    assertEquals(2, repo.consecutiveToolAttempts(next, ToolKind.WORKSPACE_READ, a, 2));
    repo.startTool(java.util.UUID.randomUUID().toString(), next, agent, ToolKind.WORKSPACE_READ, b);
    assertEquals(0, repo.consecutiveToolAttempts(next, ToolKind.WORKSPACE_READ, a, 2));
    repo.startTool(java.util.UUID.randomUUID().toString(), next, agent, ToolKind.WORKSPACE_READ, a);
    assertEquals(1, repo.consecutiveToolAttempts(next, ToolKind.WORKSPACE_READ, a, 2));
  }

  @Test
  void cancelSlowTool() throws Exception {
    String id = service.create(new CreateTaskRequest("测试取消", "slow", 4, 2, 30));
    for (int i = 0;
        i < 50
            && repo.events(id).stream().noneMatch(e -> e.get("EVENT_TYPE").equals("TOOL_STARTED"));
        i++) Thread.sleep(50);
    service.cancel(id);
    assertEquals("CANCELLED", await(id).get("STATUS"));
    assertEquals("CANCELLED", repo.tools(id).getFirst().get("STATUS"));
    assertEquals(
        dev.workbench.domain.HandoffParty.ANALYSIS_AGENT,
        awaitSystemHandoff(id, dev.workbench.domain.HandoffStatus.CANCELLED).sender());
  }

  @Test
  void pauseAtSafePointResumeAndPreserveBudget() throws Exception {
    String id = service.create(new CreateTaskRequest("暂停与继续", "slow", 4, 2, 30));
    for (int i = 0; i < 100 && repo.tools(id).isEmpty(); i++) Thread.sleep(50);
    service.pause(id);
    for (int i = 0; i < 100 && !repo.run(id).get("STATUS").equals("PAUSED"); i++) Thread.sleep(50);
    assertEquals("PAUSED", repo.run(id).get("STATUS"));
    int tools = ((Number) repo.run(id).get("TOOLS")).intValue();
    Thread.sleep(300);
    assertEquals(tools, ((Number) repo.run(id).get("TOOLS")).intValue());
    service.resume(id);
    assertEquals("COMPLETED", await(id).get("STATUS"));
    assertEquals(1, repo.tools(id).size());
  }

  @Test
  void queuedCancellationDoesNotWaitForEarlierRunOrStartAgent() throws Exception {
    String first = service.create(new CreateTaskRequest("占用执行器", "slow", 4, 2, 30));
    for (int i = 0;
        i < 50
            && repo.events(first).stream()
                .noneMatch(e -> e.get("EVENT_TYPE").equals("TOOL_STARTED"));
        i++) Thread.sleep(50);
    String queued = service.create(new CreateTaskRequest("排队任务", "normal", 4, 2, 30));
    service.cancel(queued);
    assertEquals("CANCELLED", repo.run(queued).get("STATUS"));
    assertEquals("USER_CANCELLED", repo.run(queued).get("STOP_CAUSE"));
    assertTrue(repo.events(queued).stream().anyMatch(e -> "CANCELLED".equals(e.get("EVENT_TYPE"))));
    assertTrue(repo.handoffs(queued).isEmpty());
    assertTrue(plans.find(queued).stream().allMatch(n -> n.status() == PlanNodeStatus.SKIPPED));
    assertTrue(plans.find(queued).stream().allMatch(n -> n.agentId() == null));
    assertTrue(repo.agents(queued).isEmpty());
    service.cancel(first);
    assertEquals("CANCELLED", await(first).get("STATUS"));
    Thread.sleep(200);
    assertEquals("CANCELLED", repo.run(queued).get("STATUS"));
    assertTrue(repo.agents(queued).isEmpty());
  }

  @Test
  void failedToolIsTrackedSeparately() throws Exception {
    String id = service.create(new CreateTaskRequest("测试失败", "fail", 4, 2, 30));
    assertEquals("FAILED", await(id).get("STATUS"));
    var tool = repo.tools(id).getFirst();
    assertEquals("FAILED", tool.get("STATUS"));
    assertNotNull(tool.get("ERROR_MESSAGE"));
    assertNotNull(tool.get("DURATION_MS"));
    var handoff = awaitSystemHandoff(id, dev.workbench.domain.HandoffStatus.FAILED);
    assertEquals(dev.workbench.domain.HandoffParty.ANALYSIS_AGENT, handoff.sender());
    assertEquals(dev.workbench.domain.HandoffParty.COORDINATOR, handoff.receiver());
  }

  @Test
  void interruptedRunRestoresFromSavedSafeNode() throws Exception {
    String id = service.create(new CreateTaskRequest("测试恢复", "slow", 4, 2, 30));
    for (int i = 0; i < 50 && repo.checkpoints(id).isEmpty(); i++) Thread.sleep(50);
    service.cancel(id);
    await(id);
    // 仅测试构造故障历史；生产逻辑不允许将取消终态改为中断。
    db.update("UPDATE runs SET status='INTERRUPTED',reason='模拟进程中断' WHERE id=?", id);
    String cp = repo.checkpoints(id).getFirst().get("ID").toString();
    String restored = service.recover(id, cp);
    assertEquals("COMPLETED", await(restored).get("STATUS"));
    assertTrue(plans.find(restored).stream().allMatch(n -> n.status() == PlanNodeStatus.COMPLETED));
    assertEquals(id, repo.run(restored).get("PARENT_ID"));
    assertThrows(IllegalStateException.class, () -> service.recover(id, cp));
  }

  @Test
  void terminalRunAndInvocationIgnoreLateResults() {
    String runId = java.util.UUID.randomUUID().toString();
    String modelId = java.util.UUID.randomUUID().toString();
    repo.createRun(runId, java.util.UUID.randomUUID().toString(), null, 0, 0, 4, 2, 30, 0);
    repo.update(runId, RunStatus.RUNNING, null, null, 0, 0, 0);
    repo.startModel(modelId, runId, java.util.UUID.randomUUID().toString(), ModelKind.MOCK);
    repo.finishModel(modelId, InvocationStatus.CANCELLED, null, null, "已取消", 1);
    repo.update(runId, RunStatus.CANCELLED, "已取消", null, 0, 0, 1);
    assertThrows(
        IllegalStateException.class,
        () -> repo.update(runId, RunStatus.RUNNING, null, null, 0, 0, 2));
    repo.finishModel(modelId, InvocationStatus.COMPLETED, 123, 456, null, 2);
    assertEquals(RunStatus.CANCELLED, repo.runSnapshot(runId).status());
    assertEquals("CANCELLED", repo.models(runId).getFirst().get("STATUS"));
    assertNull(repo.models(runId).getFirst().get("INPUT_TOKENS"));
  }

  @Test
  void readOnlyWorkspaceRecoveryRequiresUnchangedStateAndRechecksAfterQueue(@TempDir Path temp)
      throws Exception {
    Path source = Files.createDirectory(temp.resolve("source"));
    runGit(source, "init", "-b", "main");
    runGit(source, "config", "user.email", "test@example.invalid");
    runGit(source, "config", "user.name", "Test");
    Files.writeString(source.resolve("sample.txt"), "原始内容", StandardCharsets.UTF_8);
    runGit(source, "add", "sample.txt");
    runGit(source, "commit", "-m", "fixture");
    var workspace = workspaces.create(source.toString(), "main");
    String old =
        service.create(
            new CreateTaskRequest(
                "分析只读文件", "normal", 6, 2, 30, "mock", workspace.id(), "sample.txt", false, null));
    assertEquals("COMPLETED", await(old).get("STATUS"));
    var checkpoints = repo.checkpoints(old);
    var start =
        checkpoints.stream()
            .filter(c -> "BEFORE_TOOL".equals(c.get("STAGE")))
            .findFirst()
            .orElseThrow();
    var review =
        checkpoints.stream()
            .filter(c -> Integer.valueOf(6).equals(c.get("VERSION")))
            .findFirst()
            .orElseThrow();
    assertEquals(true, review.get("VALID"));
    String originalAgent = repo.agents(old).getFirst().get("ID").toString();
    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", old);
    assertThrows(
        IllegalStateException.class, () -> service.recover(old, start.get("ID").toString()));
    String restored = service.recover(old, review.get("ID").toString());
    assertEquals("COMPLETED", await(restored).get("STATUS"));
    assertEquals(1, repo.agents(restored).size());
    assertTrue(repo.tools(restored).isEmpty());
    assertEquals(PlanNodeStatus.REUSED, plans.find(restored).getFirst().status());
    assertEquals(originalAgent, plans.find(restored).getFirst().sourceAgentId().toString());
    assertTrue(repo.runSnapshot(restored).rounds() >= repo.runSnapshot(old).rounds());
    var next =
        repo.checkpoints(restored).stream()
            .filter(c -> Integer.valueOf(6).equals(c.get("VERSION")))
            .findFirst()
            .orElseThrow();
    assertEquals(true, next.get("VALID"));

    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", restored);
    String busy = service.create(new CreateTaskRequest("占用调度队列", "slow", 4, 2, 30));
    for (int i = 0; i < 100 && repo.tools(busy).isEmpty(); i++) Thread.sleep(100);
    assertFalse(repo.tools(busy).isEmpty());
    String queued = service.recover(restored, next.get("ID").toString());
    assertEquals("PENDING", repo.run(queued).get("STATUS"));
    var file = workspaces.read(workspace.id(), "sample.txt");
    var changedFile = workspaces.write(workspace.id(), "sample.txt", file.sha256(), "已改变");
    service.cancel(busy);
    var failed = await(queued);
    assertEquals("FAILED", failed.get("STATUS"));
    assertTrue(failed.get("REASON").toString().contains("工作区"));
    assertEquals("PRECONDITION_FAILED", failed.get("RECOVERY_DISPATCH"));
    assertEquals("RECOVERY_PRECONDITION_FAILED", failed.get("STOP_CAUSE"));
    assertTrue(repo.agents(queued).isEmpty());
    assertFalse(repo.hasRecovery(restored));
    assertThrows(
        IllegalStateException.class, () -> service.recover(old, review.get("ID").toString()));
    workspaces.write(workspace.id(), "sample.txt", changedFile.sha256(), "原始内容");
    String retry = service.recover(restored, next.get("ID").toString());
    assertEquals("COMPLETED", await(retry).get("STATUS"));
    assertEquals(originalAgent, plans.find(retry).getFirst().sourceAgentId().toString());
    assertTrue(repo.tools(retry).isEmpty());
    db.update("UPDATE runs SET status='RUNNING' WHERE id=?", retry);
    try {
      assertThrows(
          IllegalStateException.class,
          () -> repo.rejectRecoveryPrecondition(retry, 0, 0, 0, "不应允许"));
      assertTrue(repo.hasRecovery(restored));
    } finally {
      db.update("UPDATE runs SET status='COMPLETED' WHERE id=?", retry);
    }
    assertEquals("原始内容", Files.readString(source.resolve("sample.txt")));

    Files.writeString(Path.of(workspace.workspacePath()).resolve("untracked.txt"), "未跟踪");
    String untracked =
        service.create(
            new CreateTaskRequest(
                "分析含未跟踪文件的工作区",
                "normal",
                6,
                2,
                30,
                "mock",
                workspace.id(),
                "sample.txt",
                false,
                null));
    assertEquals("COMPLETED", await(untracked).get("STATUS"));
    assertTrue(
        repo.events(untracked).stream()
            .anyMatch(
                e ->
                    "CHECKPOINT_SKIPPED".equals(e.get("EVENT_TYPE"))
                        && "WORKSPACE_BEFORE_UNAVAILABLE".equals(e.get("REASON_CODE"))));
    assertTrue(
        repo.events(untracked).stream()
            .anyMatch(
                e ->
                    "CHECKPOINT_SKIPPED".equals(e.get("EVENT_TYPE"))
                        && e.get("MESSAGE").toString().contains("分析前")));
    assertTrue(
        repo.checkpoints(untracked).stream()
            .noneMatch(c -> Integer.valueOf(6).equals(c.get("VERSION"))));
  }

  @Test
  void recoveryPreconditionFailureIsAtomicAndDoesNotGuessOnStorageFailure() throws Exception {
    String parent = service.create(new CreateTaskRequest("恢复事务样本", "normal", 5, 2, 30));
    assertEquals("COMPLETED", await(parent).get("STATUS"));
    var checkpoint =
        repo.checkpoints(parent).stream()
            .filter(c -> "BEFORE_TOOL".equals(c.get("STAGE")))
            .findFirst()
            .orElseThrow();
    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", parent);
    String child = java.util.UUID.randomUUID().toString();
    repo.createRecoveryRun(
        java.util.UUID.fromString(child),
        repo.runSnapshot(parent),
        repo.checkedCheckpoint(checkpoint.get("ID").toString()));
    db.update("UPDATE runs SET status='RUNNING' WHERE id=?", child);
    db.execute(
        "ALTER TABLE events ADD CONSTRAINT recovery_precondition_test_failure CHECK (event_type <>"
            + " 'FAILED' OR POSITION('"
            + child
            + "' IN message)=0)");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> repo.rejectRecoveryPrecondition(child, 0, 0, 0, "故障注入 " + child));
      assertEquals("RUNNING", repo.run(child).get("STATUS"));
      assertEquals("UNKNOWN", repo.run(child).get("RECOVERY_DISPATCH"));
      assertTrue(plans.find(child).stream().allMatch(n -> n.status() == PlanNodeStatus.PENDING));
      assertTrue(repo.hasRecovery(parent));
    } finally {
      db.execute("ALTER TABLE events DROP CONSTRAINT recovery_precondition_test_failure");
    }
    repo.rejectRecoveryPrecondition(child, 0, 0, 0, "前置条件不满足");
    assertEquals("PRECONDITION_FAILED", repo.run(child).get("RECOVERY_DISPATCH"));
    assertTrue(plans.find(child).stream().allMatch(n -> n.status() == PlanNodeStatus.SKIPPED));
    assertFalse(repo.hasRecovery(parent));
  }

  @Test
  void reviewCheckpointReusesVerifiedAnalysisWithoutRepeatingTools() throws Exception {
    String old = service.create(new CreateTaskRequest("恢复到审查", "normal", 6, 2, 30));
    assertEquals("COMPLETED", await(old).get("STATUS"));
    var checkpoint =
        repo.checkpoints(old).stream()
            .filter(c -> "BEFORE_REVIEW".equals(c.get("STAGE")))
            .findFirst()
            .orElseThrow();
    assertEquals(true, checkpoint.get("VALID"));
    String originalAgent = repo.agents(old).getFirst().get("ID").toString();
    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", old);
    String restored = service.recover(old, checkpoint.get("ID").toString());
    assertEquals("COMPLETED", await(restored).get("STATUS"));
    assertTrue(repo.tools(restored).isEmpty());
    assertEquals(1, repo.agents(restored).size());
    assertEquals(PlanNodeStatus.REUSED, plans.find(restored).getFirst().status());
    assertEquals(originalAgent, plans.find(restored).getFirst().sourceAgentId().toString());
    assertTrue(reports.render(restored).contains("REUSED"));
    assertNull(plans.find(restored).getFirst().agentId());
    assertTrue(repo.runSnapshot(restored).rounds() >= repo.runSnapshot(old).rounds());
    var nextCheckpoint =
        repo.checkpoints(restored).stream()
            .filter(c -> "BEFORE_REVIEW".equals(c.get("STAGE")))
            .findFirst()
            .orElseThrow();
    assertEquals(5, ((Number) nextCheckpoint.get("VERSION")).intValue());
    assertEquals(true, nextCheckpoint.get("VALID"));
    String unrelated = service.create(new CreateTaskRequest("无关来源", "normal", 4, 2, 30));
    assertEquals("COMPLETED", await(unrelated).get("STATUS"));
    var unrelatedAgent =
        java.util.UUID.fromString(repo.agents(unrelated).getFirst().get("ID").toString());
    assertThrows(
        IllegalArgumentException.class,
        () -> repo.ancestorAnalysisReference(restored, unrelated, unrelatedAgent));
    db.update(
        "UPDATE runs SET task_id=? WHERE id=?",
        repo.runSnapshot(old).taskId().toString(),
        unrelated);
    assertThrows(
        IllegalArgumentException.class,
        () -> repo.ancestorAnalysisReference(restored, unrelated, unrelatedAgent));
    String savedSourceResult =
        db.queryForObject(
            "SELECT result FROM agent_invocations WHERE id=?", String.class, originalAgent);
    db.update("UPDATE agent_invocations SET result='篡改' WHERE id=?", originalAgent);
    assertFalse(repo.validCheckpoint(repo.checkpoint(nextCheckpoint.get("ID").toString())));
    db.update("UPDATE agent_invocations SET result=? WHERE id=?", savedSourceResult, originalAgent);
    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", restored);
    String restoredAgain = service.recover(restored, nextCheckpoint.get("ID").toString());
    assertEquals("COMPLETED", await(restoredAgain).get("STATUS"));
    assertEquals(1, repo.agents(restoredAgain).size());
    assertTrue(repo.tools(restoredAgain).isEmpty());
    assertEquals(originalAgent, plans.find(restoredAgain).getFirst().sourceAgentId().toString());
    assertTrue(repo.runSnapshot(restoredAgain).rounds() >= repo.runSnapshot(restored).rounds());
    var transfer =
        repo.handoffs(restoredAgain).stream()
            .filter(h -> "AGENT_TRANSFER".equals(h.get("DIRECTION")))
            .map(h -> (Handoff) h.get("PAYLOAD"))
            .findFirst()
            .orElseThrow();
    assertEquals(old, transfer.evidenceRunId());
  }

  @Test
  void reviewCheckpointRejectsAlteredAnalysisSource() throws Exception {
    String id = service.create(new CreateTaskRequest("校验分析来源", "normal", 5, 2, 30));
    assertEquals("COMPLETED", await(id).get("STATUS"));
    String cp =
        repo.checkpoints(id).stream()
            .filter(c -> "BEFORE_REVIEW".equals(c.get("STAGE")))
            .findFirst()
            .orElseThrow()
            .get("ID")
            .toString();
    db.update(
        "UPDATE agent_invocations SET result='篡改' WHERE id=?",
        repo.agents(id).getFirst().get("ID"));
    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", id);
    assertFalse(repo.validCheckpoint(repo.checkpoint(cp)));
    assertThrows(IllegalArgumentException.class, () -> service.recover(id, cp));
  }

  @Test
  void recoveryCreationRollsBackAndConfirmedQueueRejectionAllowsRetry() throws Exception {
    String old = service.create(new CreateTaskRequest("恢复事务", "normal", 6, 2, 30));
    assertEquals("COMPLETED", await(old).get("STATUS"));
    var checkpointRow =
        repo.checkpoints(old).stream()
            .filter(c -> "BEFORE_REVIEW".equals(c.get("STAGE")))
            .findFirst()
            .orElseThrow();
    String checkpointId = checkpointRow.get("ID").toString();
    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", old);
    db.execute(
        "ALTER TABLE events ADD CONSTRAINT recovery_event_test_failure CHECK"
            + " (event_type <> 'RECOVERY_STARTED' OR POSITION('"
            + old
            + "' IN message)=0)");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> service.recover(old, checkpointId));
      assertFalse(repo.hasRecovery(old));
      assertEquals(
          0, db.queryForObject("SELECT COUNT(*) FROM runs WHERE parent_id=?", Integer.class, old));
    } finally {
      db.execute("ALTER TABLE events DROP CONSTRAINT recovery_event_test_failure");
    }
    String rejected = java.util.UUID.randomUUID().toString();
    repo.createRecoveryRun(
        java.util.UUID.fromString(rejected),
        repo.runSnapshot(old),
        repo.checkedCheckpoint(checkpointId));
    assertTrue(repo.hasRecovery(old));
    repo.rejectRecoveryBeforeDispatch(rejected);
    assertEquals("FAILED", repo.run(rejected).get("STATUS"));
    assertEquals("REJECTED", repo.run(rejected).get("RECOVERY_DISPATCH"));
    assertTrue(repo.agents(rejected).isEmpty());
    assertTrue(plans.find(rejected).stream().allMatch(n -> n.status() == PlanNodeStatus.SKIPPED));
    assertFalse(repo.hasRecovery(old));
    db.update("UPDATE runs SET status='RUNNING' WHERE id=?", rejected);
    assertTrue(repo.hasRecovery(old));
    db.update("UPDATE runs SET status='FAILED' WHERE id=?", rejected);
    String accepted = service.recover(old, checkpointId);
    assertEquals("COMPLETED", await(accepted).get("STATUS"));
    assertTrue(repo.hasRecovery(old));
    assertThrows(IllegalStateException.class, () -> service.recover(old, checkpointId));
  }

  @Test
  void stoppedRunAndSystemHandoffCommitAtomically() {
    var taskId = java.util.UUID.randomUUID();
    var runId = java.util.UUID.randomUUID();
    var analystId = java.util.UUID.randomUUID();
    String goal = "停止事务测试";
    String reason = "用户已取消运行。";
    var task =
        new dev.workbench.domain.TaskDefinition(
            taskId,
            goal,
            dev.workbench.domain.Scenario.NORMAL,
            dev.workbench.domain.ModelMode.MOCK,
            null,
            null,
            false,
            null,
            false,
            java.util.List.of(),
            java.util.List.of(),
            dev.workbench.domain.TaskEntryKind.GENERAL);
    repo.createTaskAndFirstRun(
        task, runId, new dev.workbench.domain.RunLimits(4, 2, 2, null, null, 30, null, null), "测试");
    repo.update(runId.toString(), RunStatus.RUNNING, null, null, 0, 0, 0);
    repo.startAgent(analystId.toString(), runId.toString(), goal, AgentRole.ANALYST);
    var handoff =
        new Handoff(
            taskId.toString(),
            runId.toString(),
            analystId.toString(),
            dev.workbench.domain.HandoffParty.ANALYSIS_AGENT,
            dev.workbench.domain.HandoffParty.COORDINATOR,
            goal,
            java.util.List.of(),
            dev.workbench.domain.HandoffStatus.CANCELLED,
            reason,
            runId.toString(),
            java.util.List.of(),
            HandoffSource.SYSTEM,
            null);
    var completion =
        new dev.workbench.store.StopCompletion(
            runId,
            analystId,
            dev.workbench.domain.StopCause.USER_CANCELLED,
            reason,
            1,
            0,
            100,
            handoff);
    db.execute(
        "ALTER TABLE events ADD CONSTRAINT stop_terminal_test_failure CHECK (event_type <>"
            + " 'CANCELLED' OR run_id <> '"
            + runId
            + "')");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> repo.finishStoppedRun(completion));
      assertEquals("RUNNING", repo.run(runId.toString()).get("STATUS"));
      assertEquals("RUNNING", repo.agents(runId.toString()).getFirst().get("STATUS"));
      assertEquals(PlanNodeStatus.RUNNING, plans.find(runId.toString()).getFirst().status());
      assertTrue(repo.handoffs(runId.toString()).isEmpty());
    } finally {
      db.execute("ALTER TABLE events DROP CONSTRAINT stop_terminal_test_failure");
    }
    repo.finishStoppedRun(completion);
    assertEquals("CANCELLED", repo.run(runId.toString()).get("STATUS"));
    assertEquals("USER_CANCELLED", repo.run(runId.toString()).get("STOP_CAUSE"));
    assertEquals(PlanNodeStatus.CANCELLED, plans.find(runId.toString()).getFirst().status());
    assertEquals(1, repo.handoffs(runId.toString()).size());
    assertTrue(
        repo.events(runId.toString()).stream()
            .anyMatch(e -> "CANCELLED".equals(e.get("EVENT_TYPE"))));
  }

  @Test
  void reviewedRunCompletionRollsBackOnTerminalEventFailure() {
    var taskId = java.util.UUID.randomUUID();
    var runId = java.util.UUID.randomUUID();
    String analyst = java.util.UUID.randomUUID().toString();
    String reviewer = java.util.UUID.randomUUID().toString();
    String goal = "运行终态事务测试";
    var task =
        new dev.workbench.domain.TaskDefinition(
            taskId,
            goal,
            dev.workbench.domain.Scenario.NORMAL,
            dev.workbench.domain.ModelMode.MOCK,
            null,
            null,
            false,
            null,
            false,
            java.util.List.of(),
            java.util.List.of(),
            dev.workbench.domain.TaskEntryKind.GENERAL);
    repo.createTaskAndFirstRun(
        task, runId, new dev.workbench.domain.RunLimits(4, 2, 2, null, null, 30, null, null), "测试");
    repo.update(runId.toString(), RunStatus.RUNNING, null, null, 0, 0, 0);
    repo.startAgent(analyst, runId.toString(), goal, AgentRole.ANALYST);
    repo.finishAgent(analyst, InvocationStatus.COMPLETED, "分析");
    repo.startAgent(reviewer, runId.toString(), goal, AgentRole.REVIEWER);
    var result =
        new Handoff(
            taskId.toString(),
            runId.toString(),
            reviewer,
            dev.workbench.domain.HandoffParty.REVIEW_AGENT,
            dev.workbench.domain.HandoffParty.COORDINATOR,
            goal,
            java.util.List.of(),
            dev.workbench.domain.HandoffStatus.COMPLETED,
            "审查摘要",
            null,
            java.util.List.of(),
            HandoffSource.MOCK_MODEL,
            null);
    var completion =
        new dev.workbench.store.ReviewCompletion(
            runId,
            java.util.UUID.fromString(reviewer),
            "审查摘要",
            result,
            RunStatus.COMPLETED,
            null,
            null,
            "最终结果",
            2,
            1,
            100);
    db.execute(
        "ALTER TABLE events ADD CONSTRAINT review_terminal_test_failure CHECK (event_type <>"
            + " 'RUN_COMPLETED' OR run_id <> '"
            + runId
            + "')");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> repo.finishReviewedRun(completion));
      assertEquals("RUNNING", repo.run(runId.toString()).get("STATUS"));
      assertEquals(PlanNodeStatus.RUNNING, plans.find(runId.toString()).getLast().status());
      assertEquals(
          "RUNNING",
          repo.agents(runId.toString()).stream()
              .filter(a -> reviewer.equals(a.get("ID")))
              .findFirst()
              .orElseThrow()
              .get("STATUS"));
      assertTrue(repo.handoffs(runId.toString()).isEmpty());
      assertTrue(
          repo.events(runId.toString()).stream()
              .noneMatch(e -> "HANDOFF".equals(e.get("EVENT_TYPE"))));
    } finally {
      db.execute("ALTER TABLE events DROP CONSTRAINT review_terminal_test_failure");
    }
    repo.update(runId.toString(), RunStatus.PAUSED, null, null, 0, 0, 0);
    assertThrows(IllegalStateException.class, () -> repo.finishReviewedRun(completion));
    assertEquals("PAUSED", repo.run(runId.toString()).get("STATUS"));
    assertEquals(PlanNodeStatus.RUNNING, plans.find(runId.toString()).getLast().status());
    assertTrue(repo.handoffs(runId.toString()).isEmpty());
    repo.update(runId.toString(), RunStatus.RUNNING, null, null, 0, 0, 0);
    repo.finishReviewedRun(completion);
    assertEquals("COMPLETED", repo.run(runId.toString()).get("STATUS"));
    assertEquals(PlanNodeStatus.COMPLETED, plans.find(runId.toString()).getLast().status());
    assertEquals(
        "COMPLETED",
        repo.agents(runId.toString()).stream()
            .filter(a -> reviewer.equals(a.get("ID")))
            .findFirst()
            .orElseThrow()
            .get("STATUS"));
    assertEquals(1, repo.handoffs(runId.toString()).size());
    assertTrue(
        repo.events(runId.toString()).stream()
            .anyMatch(e -> "RUN_COMPLETED".equals(e.get("EVENT_TYPE"))));
    assertThrows(IllegalStateException.class, () -> repo.finishReviewedRun(completion));
  }

  @Test
  void agentCompletionAndResultHandoffCommitAtomically() {
    var taskId = java.util.UUID.randomUUID();
    var runId = java.util.UUID.randomUUID();
    String analyst = java.util.UUID.randomUUID().toString();
    String goal = "结果事务测试";
    var task =
        new dev.workbench.domain.TaskDefinition(
            taskId,
            goal,
            dev.workbench.domain.Scenario.NORMAL,
            dev.workbench.domain.ModelMode.MOCK,
            null,
            null,
            false,
            null,
            false,
            java.util.List.of(),
            java.util.List.of(),
            dev.workbench.domain.TaskEntryKind.GENERAL);
    repo.createTaskAndFirstRun(
        task, runId, new dev.workbench.domain.RunLimits(4, 2, 2, null, null, 30, null, null), "测试");
    repo.startAgent(analyst, runId.toString(), goal, AgentRole.ANALYST);
    var result =
        new Handoff(
            taskId.toString(),
            runId.toString(),
            analyst,
            dev.workbench.domain.HandoffParty.ANALYSIS_AGENT,
            dev.workbench.domain.HandoffParty.COORDINATOR,
            goal,
            java.util.List.of(),
            dev.workbench.domain.HandoffStatus.COMPLETED,
            "结果摘要",
            null,
            java.util.List.of(),
            HandoffSource.MOCK_MODEL,
            null);
    assertThrows(
        IllegalArgumentException.class, () -> repo.finishAgentWithResult(analyst, "不一致", result));
    db.execute(
        "ALTER TABLE handoffs ADD CONSTRAINT result_handoff_test_failure CHECK (id <> '"
            + result.handoffId()
            + "')");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> repo.finishAgentWithResult(analyst, "结果摘要", result));
      assertEquals(PlanNodeStatus.RUNNING, plans.find(runId.toString()).getFirst().status());
      assertEquals("RUNNING", repo.agents(runId.toString()).getFirst().get("STATUS"));
      assertTrue(repo.handoffs(runId.toString()).isEmpty());
    } finally {
      db.execute("ALTER TABLE handoffs DROP CONSTRAINT result_handoff_test_failure");
    }
    repo.finishAgentWithResult(analyst, "结果摘要", result);
    assertEquals(PlanNodeStatus.COMPLETED, plans.find(runId.toString()).getFirst().status());
    assertEquals("COMPLETED", repo.agents(runId.toString()).getFirst().get("STATUS"));
    assertEquals(1, repo.handoffs(runId.toString()).size());
  }

  @Test
  void coordinatorDelegationAndAnalystStartCommitAtomically() {
    var taskId = java.util.UUID.randomUUID();
    var runId = java.util.UUID.randomUUID();
    String analyst = java.util.UUID.randomUUID().toString();
    String goal = "委派事务测试";
    var task =
        new dev.workbench.domain.TaskDefinition(
            taskId,
            goal,
            dev.workbench.domain.Scenario.NORMAL,
            dev.workbench.domain.ModelMode.MOCK,
            null,
            null,
            false,
            null,
            false,
            java.util.List.of(),
            java.util.List.of(),
            dev.workbench.domain.TaskEntryKind.GENERAL);
    repo.createTaskAndFirstRun(
        task, runId, new dev.workbench.domain.RunLimits(4, 2, 2, null, null, 30, null, null), "测试");
    var delegation =
        new Handoff(
            taskId.toString(),
            runId.toString(),
            analyst,
            dev.workbench.domain.HandoffParty.COORDINATOR,
            dev.workbench.domain.HandoffParty.ANALYSIS_AGENT,
            goal,
            java.util.List.of(),
            dev.workbench.domain.HandoffStatus.ASSIGNED,
            "分析任务",
            null,
            java.util.List.of(),
            HandoffSource.COORDINATOR,
            analyst);
    db.execute(
        "ALTER TABLE handoff_receipts ADD CONSTRAINT delegation_receipt_test_failure CHECK"
            + " (receiver_agent_id <> '"
            + analyst
            + "')");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> repo.startAgentWithDelegation(analyst, runId.toString(), goal, delegation));
      assertTrue(repo.agents(runId.toString()).isEmpty());
      assertTrue(repo.handoffs(runId.toString()).isEmpty());
      assertEquals(PlanNodeStatus.PENDING, plans.find(runId.toString()).getFirst().status());
    } finally {
      db.execute("ALTER TABLE handoff_receipts DROP CONSTRAINT delegation_receipt_test_failure");
    }
    repo.startAgentWithDelegation(analyst, runId.toString(), goal, delegation);
    assertEquals(analyst, repo.handoffs(runId.toString()).getFirst().get("RECEIVER_AGENT_ID"));
    assertEquals(PlanNodeStatus.RUNNING, plans.find(runId.toString()).getFirst().status());
    repo.finishAgent(analyst, InvocationStatus.COMPLETED, "完成");
  }

  @Test
  void handoffReceiptAndReceiverStartCommitAtomically() {
    var taskId = java.util.UUID.randomUUID();
    var runId = java.util.UUID.randomUUID();
    String analyst = java.util.UUID.randomUUID().toString();
    String reviewer = java.util.UUID.randomUUID().toString();
    String goal = "交接事务测试";
    var task =
        new dev.workbench.domain.TaskDefinition(
            taskId,
            goal,
            dev.workbench.domain.Scenario.NORMAL,
            dev.workbench.domain.ModelMode.MOCK,
            null,
            null,
            false,
            null,
            false,
            java.util.List.of(),
            java.util.List.of(),
            dev.workbench.domain.TaskEntryKind.GENERAL);
    repo.createTaskAndFirstRun(
        task, runId, new dev.workbench.domain.RunLimits(4, 2, 2, null, null, 30, null, null), "测试");
    repo.startAgent(analyst, runId.toString(), goal, AgentRole.ANALYST);
    var handoff =
        new Handoff(
            taskId.toString(),
            runId.toString(),
            analyst,
            dev.workbench.domain.HandoffParty.ANALYSIS_AGENT,
            dev.workbench.domain.HandoffParty.REVIEW_AGENT,
            goal,
            java.util.List.of(),
            dev.workbench.domain.HandoffStatus.COMPLETED,
            "分析结果",
            runId.toString(),
            java.util.List.of(),
            HandoffSource.MOCK_MODEL,
            reviewer);
    repo.handoff(HandoffDirection.AGENT_TRANSFER, handoff);
    assertNull(repo.handoffs(runId.toString()).getFirst().get("RECEIVER_AGENT_ID"));
    assertThrows(
        IllegalStateException.class,
        () ->
            repo.startAgentFromHandoff(
                reviewer, runId.toString(), goal, AgentRole.REVIEWER, handoff.handoffId()));
    repo.finishAgent(analyst, InvocationStatus.COMPLETED, "分析结果");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repo.startAgentFromHandoff(
                java.util.UUID.randomUUID().toString(),
                runId.toString(),
                goal,
                AgentRole.REVIEWER,
                handoff.handoffId()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repo.startAgentFromHandoff(
                reviewer, runId.toString(), goal, AgentRole.CODER, handoff.handoffId()));
    db.execute(
        "ALTER TABLE handoff_receipts ADD CONSTRAINT handoff_receipt_test_failure CHECK"
            + " (receiver_agent_id <> '"
            + reviewer
            + "')");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              repo.startAgentFromHandoff(
                  reviewer, runId.toString(), goal, AgentRole.REVIEWER, handoff.handoffId()));
      assertEquals(1, repo.agents(runId.toString()).size());
      assertEquals(PlanNodeStatus.PENDING, plans.find(runId.toString()).getLast().status());
      assertNull(repo.handoffs(runId.toString()).getFirst().get("RECEIVER_AGENT_ID"));
    } finally {
      db.execute("ALTER TABLE handoff_receipts DROP CONSTRAINT handoff_receipt_test_failure");
    }
    repo.startAgentFromHandoff(
        reviewer, runId.toString(), goal, AgentRole.REVIEWER, handoff.handoffId());
    assertEquals(reviewer, repo.handoffs(runId.toString()).getFirst().get("RECEIVER_AGENT_ID"));
    assertEquals(PlanNodeStatus.RUNNING, plans.find(runId.toString()).getLast().status());
    assertThrows(
        IllegalStateException.class,
        () ->
            repo.startAgentFromHandoff(
                reviewer, runId.toString(), goal, AgentRole.REVIEWER, handoff.handoffId()));
    assertEquals(
        1,
        repo.events(runId.toString()).stream()
            .filter(e -> "HANDOFF_ACCEPTED".equals(e.get("EVENT_TYPE")))
            .count());
    repo.finishAgent(reviewer, InvocationStatus.COMPLETED, "完成");
  }

  @Test
  void completedButNonAdjacentAgentCannotProvideReviewerHandoff() {
    var taskId = java.util.UUID.randomUUID();
    var runId = java.util.UUID.randomUUID();
    String analyst = java.util.UUID.randomUUID().toString();
    String coder = java.util.UUID.randomUUID().toString();
    String reviewer = java.util.UUID.randomUUID().toString();
    String goal = "校验交接依赖";
    var task =
        new dev.workbench.domain.TaskDefinition(
            taskId,
            goal,
            dev.workbench.domain.Scenario.NORMAL,
            dev.workbench.domain.ModelMode.MOCK,
            null,
            null,
            true,
            null,
            false,
            java.util.List.of(),
            java.util.List.of(),
            dev.workbench.domain.TaskEntryKind.GENERAL);
    repo.createTaskAndFirstRun(
        task, runId, new dev.workbench.domain.RunLimits(4, 2, 2, null, null, 30, null, null), "测试");
    repo.startAgent(analyst, runId.toString(), goal, AgentRole.ANALYST);
    repo.finishAgent(analyst, InvocationStatus.COMPLETED, "分析完成");
    repo.startAgent(coder, runId.toString(), goal, AgentRole.CODER);
    repo.finishAgent(coder, InvocationStatus.COMPLETED, "开发完成");
    var wrongHandoff =
        new Handoff(
            taskId.toString(),
            runId.toString(),
            analyst,
            dev.workbench.domain.HandoffParty.ANALYSIS_AGENT,
            dev.workbench.domain.HandoffParty.REVIEW_AGENT,
            goal,
            java.util.List.of(),
            dev.workbench.domain.HandoffStatus.COMPLETED,
            "分析结果",
            runId.toString(),
            java.util.List.of(),
            HandoffSource.MOCK_MODEL,
            reviewer);
    repo.handoff(HandoffDirection.AGENT_TRANSFER, wrongHandoff);
    assertThrows(
        IllegalStateException.class,
        () ->
            repo.startAgentFromHandoff(
                reviewer, runId.toString(), goal, AgentRole.REVIEWER, wrongHandoff.handoffId()));
    assertEquals(2, repo.agents(runId.toString()).size());
    assertEquals(PlanNodeStatus.PENDING, plans.find(runId.toString()).getLast().status());
    assertNull(repo.handoffs(runId.toString()).getFirst().get("RECEIVER_AGENT_ID"));

    var correctHandoff =
        new Handoff(
            taskId.toString(),
            runId.toString(),
            coder,
            dev.workbench.domain.HandoffParty.CODING_AGENT,
            dev.workbench.domain.HandoffParty.REVIEW_AGENT,
            goal,
            java.util.List.of(),
            dev.workbench.domain.HandoffStatus.COMPLETED,
            "开发结果",
            runId.toString(),
            java.util.List.of(),
            HandoffSource.MOCK_MODEL,
            reviewer);
    repo.handoff(HandoffDirection.AGENT_TRANSFER, correctHandoff);
    repo.startAgentFromHandoff(
        reviewer, runId.toString(), goal, AgentRole.REVIEWER, correctHandoff.handoffId());
    assertEquals(PlanNodeStatus.RUNNING, plans.find(runId.toString()).getLast().status());
  }

  @Test
  void planRejectsReviewBeforeAnalysisAndLeavesNoInvocation() {
    String runId = java.util.UUID.randomUUID().toString();
    plans.initialize(runId, false);
    String agentId = java.util.UUID.randomUUID().toString();
    assertThrows(
        IllegalStateException.class,
        () -> repo.startAgent(agentId, runId, "目标", AgentRole.REVIEWER));
    assertTrue(repo.agents(runId).isEmpty());
    assertTrue(plans.find(runId).stream().allMatch(n -> n.status() == PlanNodeStatus.PENDING));
    assertTrue(plans.find(java.util.UUID.randomUUID().toString()).isEmpty());
  }

  @Test
  void evaluationComparisonDoesNotMisclassifyUnknownOrDifferentSuites() throws Exception {
    String baseline = java.util.UUID.randomUUID().toString();
    String candidate = java.util.UUID.randomUUID().toString();
    evaluationRecords.create(baseline);
    evaluationRecords.create(candidate);
    db.update("UPDATE evaluation_cases SET status='PASSED' WHERE batch_id=?", baseline);
    db.update(
        "UPDATE evaluation_cases SET status='FAILED' WHERE batch_id=? AND case_code='NORMAL'",
        candidate);
    db.update(
        "UPDATE evaluation_cases SET status='FAILED' WHERE batch_id=? AND case_code='TOOL_FAILURE'",
        baseline);
    db.update(
        "UPDATE evaluation_cases SET status='PASSED' WHERE batch_id=? AND case_code='TOOL_FAILURE'",
        candidate);
    db.update(
        "UPDATE evaluation_cases SET status='SKIPPED' WHERE batch_id=? AND case_code='TOOL_LIMIT'",
        candidate);
    mvc.perform(
            get("/api/evaluations/compare")
                .param("baseline", baseline)
                .param("candidate", candidate))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sameSuite").value(true))
        .andExpect(jsonPath("$.comparableDefinition").value(true))
        .andExpect(jsonPath("$.cases[0].outcome").value("REGRESSED"))
        .andExpect(jsonPath("$.cases[1].outcome").value("IMPROVED"))
        .andExpect(jsonPath("$.cases[2].outcome").value("INCONCLUSIVE"));
    var baselineDefinition = evaluationRecords.get(baseline);
    assertNotNull(baselineDefinition.definitionSha256());
    assertTrue(baselineDefinition.definitionJson().contains("\"maxRounds\":4"));
    db.update("UPDATE evaluation_batches SET definition_json=? WHERE id=?", "corrupted", candidate);
    mvc.perform(
            get("/api/evaluations/compare")
                .param("baseline", baseline)
                .param("candidate", candidate))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sameSuite").value(true))
        .andExpect(jsonPath("$.comparableDefinition").value(false))
        .andExpect(jsonPath("$.cases[0].outcome").value("INCONCLUSIVE"));
    String changed = baselineDefinition.definitionJson() + " ";
    db.update(
        "UPDATE evaluation_batches SET definition_json=?,definition_sha256=? WHERE id=?",
        changed,
        dev.workbench.evaluation.SeedSuite.sha256(changed),
        candidate);
    mvc.perform(
            get("/api/evaluations/compare")
                .param("baseline", baseline)
                .param("candidate", candidate))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comparableDefinition").value(false))
        .andExpect(jsonPath("$.cases[0].outcome").value("INCONCLUSIVE"));
    db.update(
        "UPDATE evaluation_batches SET definition_json=NULL,definition_sha256=NULL WHERE id=?",
        candidate);
    mvc.perform(
            get("/api/evaluations/compare")
                .param("baseline", baseline)
                .param("candidate", candidate))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comparableDefinition").value(false))
        .andExpect(jsonPath("$.cases[0].outcome").value("INCONCLUSIVE"));
    db.update("UPDATE evaluation_batches SET suite='OTHER_SUITE' WHERE id=?", candidate);
    db.update(
        "DELETE FROM evaluation_cases WHERE batch_id=? AND case_code='TOOL_LIMIT'", candidate);
    mvc.perform(
            get("/api/evaluations/compare")
                .param("baseline", baseline)
                .param("candidate", candidate))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sameSuite").value(false))
        .andExpect(jsonPath("$.cases[0].outcome").value("INCONCLUSIVE"))
        .andExpect(jsonPath("$.cases[2].outcome").value("INCONCLUSIVE"));
    mvc.perform(
            get("/api/evaluations/compare")
                .param("baseline", baseline)
                .param("candidate", baseline))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/evaluations/compare")
                .param("baseline", baseline)
                .param("candidate", java.util.UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
    evaluationRecords.finishBatch(baseline, EvaluationStatus.COMPLETED);
    evaluationRecords.finishBatch(candidate, EvaluationStatus.COMPLETED);
  }

  @Test
  void oldSeedSuiteRemainsReadableAndCannotBeComparedAsSameDefinition() throws Exception {
    String old = java.util.UUID.randomUUID().toString();
    String current = java.util.UUID.randomUUID().toString();
    evaluationRecords.create(old);
    evaluationRecords.create(current);
    String oldDefinition = "{\"suite\":\"LOCAL_AGENT_V1\",\"assertionVersion\":1}";
    db.update(
        "UPDATE evaluation_batches SET suite='LOCAL_AGENT_V1',definition_json=?,definition_sha256=?"
            + " WHERE id=?",
        oldDefinition,
        dev.workbench.evaluation.SeedSuite.sha256(oldDefinition),
        old);
    db.update("DELETE FROM evaluation_cases WHERE batch_id=? AND case_code='REPEAT_LIMIT'", old);
    assertEquals(3, evaluationRecords.cases(old).size());
    mvc.perform(get("/api/evaluations/compare").param("baseline", old).param("candidate", current))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sameSuite").value(false))
        .andExpect(jsonPath("$.comparableDefinition").value(false))
        .andExpect(jsonPath("$.cases[3].outcome").value("INCONCLUSIVE"));
    evaluationRecords.finishBatch(old, EvaluationStatus.COMPLETED);
    evaluationRecords.finishBatch(current, EvaluationStatus.COMPLETED);
  }

  @Test
  void evaluationComparisonShowsResourcesWithoutInventingUnknownUsage() throws Exception {
    String runId = service.create(new CreateTaskRequest("评测资源", "normal", 4, 2, 30));
    assertEquals("COMPLETED", await(runId).get("STATUS"));
    var calls = repo.models(runId);
    assertTrue(calls.size() >= 2);
    db.update(
        "UPDATE model_invocations SET input_tokens=?,output_tokens=? WHERE id=?",
        12,
        3,
        calls.getFirst().get("ID"));
    String baseline = java.util.UUID.randomUUID().toString();
    String candidate = java.util.UUID.randomUUID().toString();
    evaluationRecords.create(baseline);
    evaluationRecords.create(candidate);
    db.update(
        "UPDATE evaluation_cases SET run_id=? WHERE batch_id=? AND case_code='NORMAL'",
        runId,
        candidate);
    mvc.perform(
            get("/api/evaluations/compare")
                .param("baseline", baseline)
                .param("candidate", candidate))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cases[0].baselineResources").doesNotExist())
        .andExpect(jsonPath("$.cases[0].candidateResources.available").value(true))
        .andExpect(jsonPath("$.cases[0].candidateResources.modelCalls").value(calls.size()))
        .andExpect(jsonPath("$.cases[0].candidateResources.knownInputTokens").value(12))
        .andExpect(
            jsonPath("$.cases[0].candidateResources.unknownInputCalls").value(calls.size() - 1));
    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", runId);
    mvc.perform(
            get("/api/evaluations/compare")
                .param("baseline", baseline)
                .param("candidate", candidate))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cases[0].candidateResources.recordedDurationMs").doesNotExist());
    db.update(
        "UPDATE evaluation_cases SET run_id=? WHERE batch_id=? AND case_code='NORMAL'",
        java.util.UUID.randomUUID().toString(),
        candidate);
    mvc.perform(
            get("/api/evaluations/compare")
                .param("baseline", baseline)
                .param("candidate", candidate))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cases[0].candidateResources.available").value(false));
    evaluationRecords.finishBatch(baseline, EvaluationStatus.COMPLETED);
    evaluationRecords.finishBatch(candidate, EvaluationStatus.COMPLETED);
  }

  @Test
  void evaluationChecksAndOutcomeCommitTogether() {
    String batch = java.util.UUID.randomUUID().toString();
    evaluationRecords.create(batch);
    var sample = dev.workbench.evaluation.SeedCase.NORMAL;
    assertTrue(evaluationRecords.cases(batch).getFirst().checks().isEmpty());
    evaluationRecords.startCase(batch, sample);
    var code = dev.workbench.evaluation.EvaluationCheckCode.RUN_STATUS;
    var statusCheck = new dev.workbench.evaluation.EvaluationCheck(code, false, "状态不匹配");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evaluationRecords.finishCaseWithChecks(batch, sample, java.util.List.of(statusCheck)));
    var checks =
        java.util.List.of(
            statusCheck,
            new dev.workbench.evaluation.EvaluationCheck(
                dev.workbench.evaluation.EvaluationCheckCode.PLAN_NODE_COUNT, true, "节点数符合"),
            new dev.workbench.evaluation.EvaluationCheck(
                dev.workbench.evaluation.EvaluationCheckCode.PLAN_NODES_COMPLETED, true, "节点已完成"),
            new dev.workbench.evaluation.EvaluationCheck(
                dev.workbench.evaluation.EvaluationCheckCode.TOOL_SUCCESS, true, "模拟写入失败"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evaluationRecords.finishCaseWithChecks(
                batch, sample, java.util.List.of(statusCheck, statusCheck)));
    db.execute(
        "ALTER TABLE evaluation_checks ADD CONSTRAINT evaluation_checks_test_failure CHECK (detail"
            + " <> '模拟写入失败')");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> evaluationRecords.finishCaseWithChecks(batch, sample, checks));
      var pending = evaluationRecords.cases(batch).getFirst();
      assertEquals(CaseStatus.RUNNING, pending.status());
      assertTrue(pending.checks().isEmpty());
    } finally {
      db.execute("ALTER TABLE evaluation_checks DROP CONSTRAINT evaluation_checks_test_failure");
    }
    evaluationRecords.finishCaseWithChecks(batch, sample, checks);
    var result = evaluationRecords.cases(batch).getFirst();
    assertEquals(CaseStatus.FAILED, result.status());
    assertEquals(checks, result.checks());
    evaluationRecords.finishBatch(batch, EvaluationStatus.COMPLETED);
  }

  @Test
  void dynamicEvaluationUsesVersionedWorkflowPlansAndRecordedHandoffs() throws Exception {
    var started =
        mvc.perform(post("/api/evaluations").param("suite", "DYNAMIC_WORKFLOW_V1"))
            .andExpect(status().isOk())
            .andReturn();
    String id =
        new ObjectMapper().readTree(started.getResponse().getContentAsString()).path("id").asText();
    for (int i = 0; i < 150 && evaluationRecords.get(id).status() == EvaluationStatus.RUNNING; i++)
      Thread.sleep(100);
    var batch = evaluationRecords.get(id);
    assertEquals(EvaluationStatus.COMPLETED, batch.status());
    assertEquals("DYNAMIC_WORKFLOW_V1", batch.suite());
    assertEquals(
        dev.workbench.evaluation.SeedSuite.sha256(batch.definitionJson()),
        batch.definitionSha256());
    assertTrue(batch.definitionJson().contains("\"template\":\"dual-analysis\""));

    var cases = evaluationRecords.cases(id);
    assertEquals(2, cases.size());
    assertTrue(
        cases.stream().allMatch(result -> result.status() == CaseStatus.PASSED), cases.toString());
    assertTrue(
        cases.stream()
            .flatMap(result -> result.checks().stream())
            .allMatch(check -> check.passed()));
    assertEquals(
        List.of(3, 2),
        cases.stream().map(result -> repo.models(result.runId().toString()).size()).toList());
    assertEquals(2, workflowRuns.workflowHandoffs(cases.getFirst().runId().toString()).size());
    mvc.perform(get("/api/evaluations/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.batch.suite").value("DYNAMIC_WORKFLOW_V1"))
        .andExpect(jsonPath("$.cases[0].checks.length()").value(6))
        .andExpect(jsonPath("$.cases[1].checks.length()").value(6));

    String baseline = dev.workbench.domain.WorkbenchIds.newId();
    evaluationRecords.create(baseline);
    evaluationRecords.finishBatch(baseline, EvaluationStatus.INTERRUPTED);
    mvc.perform(get("/api/evaluations/compare").param("baseline", baseline).param("candidate", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sameSuite").value(false))
        .andExpect(jsonPath("$.comparableDefinition").value(false));
  }

  @Test
  void dynamicCheckEvaluationCoversSuccessAndBlockedCounterexample() throws Exception {
    var started =
        mvc.perform(post("/api/evaluations").param("suite", "DYNAMIC_CHECK_V1"))
            .andExpect(status().isOk())
            .andReturn();
    String id =
        new ObjectMapper().readTree(started.getResponse().getContentAsString()).path("id").asText();
    for (int i = 0; i < 600 && evaluationRecords.get(id).status() == EvaluationStatus.RUNNING; i++)
      Thread.sleep(100);
    var batch = evaluationRecords.get(id);
    assertEquals(EvaluationStatus.COMPLETED, batch.status(), String.valueOf(batch));
    assertEquals("DYNAMIC_CHECK_V1", batch.suite());
    assertTrue(batch.definitionJson().contains("\"template\":\"workspace-analysis\""));
    assertTrue(batch.definitionJson().contains("\"template\":\"bug-fix\""));
    var cases = evaluationRecords.cases(id);
    assertEquals(2, cases.size());
    assertTrue(
        cases.stream().allMatch(result -> result.status() == CaseStatus.PASSED), cases.toString());
    assertTrue(
        cases.stream()
            .flatMap(result -> result.checks().stream())
            .allMatch(check -> check.passed()));

    var success = cases.getFirst();
    String successRun = success.runId().toString();
    assertEquals("COMPLETED", repo.run(successRun).get("STATUS"));
    assertEquals(3, repo.models(successRun).size());
    assertEquals(1, workflowRuns.workflowChecks(successRun).size());
    assertEquals(
        dev.workbench.framework.workflow.check.SystemCheckStatus.PASSED,
        workflowRuns.workflowChecks(successRun).getFirst().status());

    var blocked = cases.getLast();
    String blockedRun = blocked.runId().toString();
    assertEquals("BLOCKED", repo.run(blockedRun).get("STATUS"));
    assertEquals("CHECK_FAILED", repo.run(blockedRun).get("STOP_CAUSE"));
    assertEquals(
        dev.workbench.framework.workflow.check.SystemCheckStatus.BLOCKED,
        workflowRuns.workflowChecks(blockedRun).getFirst().status());
    assertTrue(repo.agents(blockedRun).isEmpty());
    assertTrue(repo.models(blockedRun).isEmpty());
    assertTrue(repo.tools(blockedRun).isEmpty());

    var successView = executionViews.view(successRun);
    assertEquals("COMPLETED", successView.runStatus());
    assertTrue(
        successView.nodes().stream()
            .anyMatch(
                node -> "SYSTEM_CHECK".equals(node.kind()) && "COMPLETED".equals(node.status())));
    var blockedView = executionViews.view(blockedRun);
    assertEquals("BLOCKED", blockedView.runStatus());
    assertTrue(
        blockedView.blocking().stream()
            .anyMatch(
                item ->
                    "CHECK_FAILED".equals(item.code())
                        && item.nodeId() != null
                        && !item.blockingNodeIds().isEmpty()));
    assertTrue(
        blockedView.availableActions().stream()
            .noneMatch(item -> "CANCEL".equals(item.actionCode())));
    var blockedCheckNode =
        blockedView.nodes().stream()
            .filter(node -> "SYSTEM_CHECK".equals(node.kind()))
            .findFirst()
            .orElseThrow();
    assertEquals("FAILED", blockedCheckNode.status());
    var blockedDetail = executionViews.detail(blockedRun, blockedCheckNode.id());
    assertFalse(blockedDetail.checks().isEmpty());
    assertNotNull(blockedDetail.checks().getFirst().summary());
    assertTrue(blockedDetail.attempts().isEmpty());
  }

  @Test
  void seedEvaluationUsesRecordedEvidenceAndReportsUnknownTokens() throws Exception {
    String batch = evaluations.start();
    assertThrows(IllegalStateException.class, () -> evaluations.start());
    for (int i = 0;
        i < 300 && evaluationRecords.get(batch).status() == EvaluationStatus.RUNNING;
        i++) Thread.sleep(100);
    assertEquals(EvaluationStatus.COMPLETED, evaluationRecords.get(batch).status());
    var cases = evaluationRecords.cases(batch);
    assertEquals(4, cases.size());
    assertTrue(cases.stream().allMatch(c -> c.status() == CaseStatus.PASSED), cases.toString());
    assertEquals(
        java.util.List.of(4, 2, 3, 3), cases.stream().map(c -> c.checks().size()).toList());
    assertTrue(cases.stream().flatMap(c -> c.checks().stream()).allMatch(c -> c.passed()));
    var repeat = cases.getLast();
    assertEquals(dev.workbench.evaluation.SeedCase.REPEAT_LIMIT, repeat.code());
    assertEquals("REPEATED_TOOL_LIMIT", repo.run(repeat.runId().toString()).get("STOP_CAUSE"));
    assertEquals(2, repo.tools(repeat.runId().toString()).size());
    assertTrue(evaluationRecords.get(batch).definitionJson().contains("\"maxIdenticalTools\":2"));
    String runId = cases.getFirst().runId().toString();
    var stats = metrics.agents(runId);
    assertEquals(2, stats.size());
    assertEquals(1, stats.getFirst().toolCalls());
    assertEquals(0, stats.getLast().toolCalls());
    assertNull(stats.getFirst().knownInputTokens());
    assertEquals(stats.getFirst().modelCalls(), stats.getFirst().unknownInputCount());
    assertNotNull(stats.getFirst().durationMs());
    mvc.perform(get("/api/evaluations/" + batch))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cases[0].status").value("PASSED"))
        .andExpect(jsonPath("$.cases[0].checks[0].code").value("RUN_STATUS"))
        .andExpect(jsonPath("$.cases[0].checks[0].passed").value(true));
    mvc.perform(get("/api/runs/" + runId + "/agent-metrics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].toolCalls").value(1));
  }

  @Test
  void interruptedEvaluationDoesNotTurnUnexecutedCasesIntoPasses() {
    String batch = java.util.UUID.randomUUID().toString();
    evaluationRecords.create(batch);
    evaluationRecords.startCase(batch, dev.workbench.evaluation.SeedCase.NORMAL);
    evaluationRecords.finishBatch(batch, EvaluationStatus.INTERRUPTED);
    assertEquals(EvaluationStatus.INTERRUPTED, evaluationRecords.get(batch).status());
    assertEquals(CaseStatus.INTERRUPTED, evaluationRecords.cases(batch).getFirst().status());
    assertTrue(
        evaluationRecords.cases(batch).subList(1, 3).stream()
            .allMatch(c -> c.status() == CaseStatus.SKIPPED));
  }

  @Test
  void evaluationCancellationStopsSchedulingAndPreservesActualResults() throws Exception {
    String batch = evaluations.start();
    for (int i = 0; i < 100 && evaluationRecords.cases(batch).getFirst().runId() == null; i++)
      Thread.sleep(10);
    mvc.perform(post("/api/evaluations/" + batch + "/cancel")).andExpect(status().isOk());
    for (int i = 0;
        i < 300
            && (evaluationRecords.get(batch).status() == EvaluationStatus.RUNNING
                || evaluationRecords.get(batch).status() == EvaluationStatus.CANCELLING);
        i++) Thread.sleep(100);
    assertEquals(EvaluationStatus.CANCELLED, evaluationRecords.get(batch).status());
    var cases = evaluationRecords.cases(batch);
    assertTrue(
        cases.subList(1, cases.size()).stream().allMatch(c -> c.status() == CaseStatus.SKIPPED));
    if (cases.getFirst().status() == CaseStatus.CANCELLED)
      assertEquals("CANCELLED", repo.run(cases.getFirst().runId().toString()).get("STATUS"));
    else assertEquals(CaseStatus.PASSED, cases.getFirst().status());
    assertThrows(IllegalStateException.class, () -> evaluations.cancel(batch));
  }

  @Test
  void interruptedRunKeepsFinishedHistoryAndDoesNotStartPendingStep() {
    String runId = java.util.UUID.randomUUID().toString();
    String agentId = java.util.UUID.randomUUID().toString();
    repo.createRun(runId, java.util.UUID.randomUUID().toString(), null, 0, 0, 4, 2, 30, 0);
    repo.startAgent(agentId, runId, "模拟中断", AgentRole.ANALYST);
    repo.interruptRunning();
    assertEquals("INTERRUPTED", repo.run(runId).get("STATUS"));
    assertEquals(PlanNodeStatus.INTERRUPTED, plans.find(runId).getFirst().status());
    assertEquals(PlanNodeStatus.SKIPPED, plans.find(runId).getLast().status());
    assertEquals("INTERRUPTED", repo.agents(runId).getFirst().get("STATUS"));
  }

  @Test
  void failedInvocationInsertRollsBackStepStart() {
    String runId = java.util.UUID.randomUUID().toString();
    String duplicateId = java.util.UUID.randomUUID().toString();
    repo.createRun(runId, java.util.UUID.randomUUID().toString(), null, 0, 0, 4, 2, 30, 0);
    db.update(
        "INSERT INTO"
            + " agent_invocations(id,run_id,role_name,task_goal,status,result,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        duplicateId,
        "another-run",
        "role",
        "goal",
        "RUNNING",
        null,
        "time",
        "time");
    assertThrows(
        RuntimeException.class, () -> repo.startAgent(duplicateId, runId, "目标", AgentRole.ANALYST));
    assertEquals(PlanNodeStatus.PENDING, plans.find(runId).getFirst().status());
    assertNull(plans.find(runId).getFirst().agentId());
  }

  @Test
  void alteredCheckpointIsRejected() throws Exception {
    String id = service.create(new CreateTaskRequest("检查点校验", "normal", 4, 2, 30));
    var old = await(id);
    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", id);
    String cp = repo.checkpoints(id).getFirst().get("ID").toString();
    db.update("UPDATE checkpoints SET rounds=rounds+1 WHERE id=?", cp);
    assertEquals(false, repo.checkpoints(id).getFirst().get("VALID"));
    assertThrows(IllegalArgumentException.class, () -> service.recover(id, cp));
    assertEquals("INTERRUPTED", repo.run(id).get("STATUS"));
  }

  @Test
  void legacyEngineCheckpointCannotResumeInAgentScope() throws Exception {
    String id = service.create(new CreateTaskRequest("旧版本检查点", "normal", 4, 2, 30));
    await(id);
    db.update("UPDATE runs SET status='INTERRUPTED' WHERE id=?", id);
    String checkpointId = repo.checkpoints(id).getFirst().get("ID").toString();
    db.update("UPDATE checkpoints SET engine='LEGACY' WHERE id=?", checkpointId);
    assertEquals(false, repo.checkpoints(id).getFirst().get("VALID"));
    assertThrows(IllegalArgumentException.class, () -> service.recover(id, checkpointId));
  }
}
