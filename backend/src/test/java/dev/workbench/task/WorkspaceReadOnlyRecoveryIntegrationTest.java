package dev.workbench.task;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.tool.ResourceVersion;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.planning.PlanService;
import dev.workbench.framework.workflow.plan.WorkflowCheckpoint;
import dev.workbench.delivery.ExecutionViewService;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.persistence.repository.WorkflowCheckpointData;
import dev.workbench.persistence.repository.WorkflowCheckpointRepository;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import dev.workbench.store.RunRecordService;

/** 只读工作区分析的安全边界：版本证据、复用来源、预算继承与拒绝路径。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:workspacerecovery;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-test-workspaces"
    })
class WorkspaceReadOnlyRecoveryIntegrationTest {
  @Autowired RunService service;
  @Autowired RunRecordService repo;
  @Autowired WorkspaceService workspaces;
  @Autowired PlanService plans;
  @Autowired WorkflowExecutionService workflowRuns;
  @Autowired WorkflowRecoveryService recovery;
  @Autowired WorkflowCheckpointRepository checkpointRepository;
  @Autowired ExecutionViewService executionViews;
  @Autowired JdbcTemplate db;
  @Autowired TransactionTemplate transactions;
  @Autowired io.agentscope.core.state.AgentStateStore agentStates;
  @TempDir Path temp;

  @Test
  void checkpointAndSavedEventRollBackTogetherAndCanBeRetried() throws Exception {
    var workspace = workspace();
    String runId = create(workspace.id());
    var plan = plans.workflowPlan(runId);
    workflowRuns.approveWorkflow(runId, plan.id());
    repo.update(runId, RunStatus.RUNNING, null, null, 0, 0, 0);

    transactions.executeWithoutResult(
        status -> {
          workflowRuns.checkpointBeforeFirstNode(runId, plan.id(), 0);
          assertEquals(1, recovery.checkpoints(runId, plan).size());
          assertEquals(1, savedCheckpointEvents(runId));
          status.setRollbackOnly();
        });

    assertTrue(recovery.checkpoints(runId, plan).isEmpty());
    assertEquals(0, savedCheckpointEvents(runId));
    workflowRuns.checkpointBeforeFirstNode(runId, plan.id(), 0);
    assertEquals(1, recovery.checkpoints(runId, plan).size());
    assertEquals(1, savedCheckpointEvents(runId));
  }

  private long savedCheckpointEvents(String runId) {
    return repo.events(runId).stream()
        .filter(
            event ->
                dev.workbench.domain.EventType.CHECKPOINT_SAVED
                    .name()
                    .equals(event.get("EVENT_TYPE")))
        .count();
  }

  @Test
  void recoversBeforeFirstNodeWithoutReusingAnything() throws Exception {
    var prepared = interruptedBeforeFirstNode();
    var checkpoint = availableCheckpoint(prepared.runId);
    assertEquals(0, checkpoint.completedNodes());

    String recovered = service.recover(prepared.runId, checkpoint.id().toString());
    var recoveredRun = await(recovered);
    assertEquals(
        "COMPLETED",
        recoveredRun.get("STATUS"),
        String.valueOf(recoveredRun.get("REASON"))
            + " 事件："
            + repo.events(recovered)
            + " 节点："
            + plans.find(recovered).stream()
                .map(node -> node.key() + ":" + node.status())
                .toList());
    var nodes = plans.find(recovered);
    assertEquals(3, nodes.size());
    assertTrue(nodes.stream().allMatch(node -> node.status() == PlanNodeStatus.COMPLETED));
    assertEquals(2, workflowRuns.workflowAttempts(recovered).size());
    assertEquals(1, workflowRuns.workflowChecks(recovered).size());
    assertEquals(3, repo.run(recovered).get("ROUNDS"));
    assertEquals(1, repo.run(recovered).get("TOOLS"));
    assertThrows(
        IllegalStateException.class, () -> service.recover(recovered, checkpoint.id().toString()));
  }

  @Test
  void reusesSettledReadOnlyAnalysisAndContinuesRemainingBudget() throws Exception {
    var prepared = interruptedAfterInspect();
    var checkpoint = availableCheckpoint(prepared.runId);
    assertEquals(1, checkpoint.completedNodes());

    String recovered = service.recover(prepared.runId, checkpoint.id().toString());
    assertEquals("COMPLETED", await(recovered).get("STATUS"));

    var original = plans.find(prepared.runId);
    var reused = plans.find(recovered);
    assertEquals(PlanNodeStatus.COMPLETED, original.getFirst().status());
    assertEquals(PlanNodeStatus.REUSED, reused.getFirst().status());
    assertEquals(
        original.getFirst().agentId().toString(), reused.getFirst().sourceAgentId().toString());
    assertEquals(PlanNodeStatus.COMPLETED, reused.get(1).status());
    assertEquals(PlanNodeStatus.COMPLETED, reused.get(2).status());
    assertEquals(1, workflowRuns.workflowAttempts(recovered).size());
    assertTrue(repo.tools(recovered).isEmpty());
    assertEquals(2, repo.run(recovered).get("ROUNDS"));
    assertEquals(1, repo.run(recovered).get("TOOLS"));
    assertTrue(repo.runSnapshot(recovered).rounds() >= repo.runSnapshot(prepared.runId).rounds());
    assertEquals(1, workflowRuns.workflowChecks(recovered).size());

    var summaryAttempt = workflowRuns.workflowAttempts(recovered).getFirst();
    var input = workflowInput(summaryAttempt.agentId().toString());
    var dependencies = input.path("dependencies");
    assertEquals(2, dependencies.size());
    assertEquals(prepared.runId, dependencies.get(0).path("sourceRunId").asText());
    assertEquals("已保存的分析", dependencies.get(0).path("result").asText());
    assertEquals("SYSTEM_CHECK", dependencies.get(1).path("kind").asText());

    var checkDetail = executionViews.detail(recovered, reused.get(1).id().toString());
    assertEquals("已保存的分析", checkDetail.checks().getFirst().inputs().getFirst().sourceResult());
    var summaryDetail = executionViews.detail(recovered, reused.getLast().id().toString());
    var handoff = summaryDetail.inboundHandoffs().getFirst();
    assertEquals(prepared.runId, handoff.sourceRunId());
    assertEquals("已保存的分析", handoff.sourceResult());
    var receipt = summaryDetail.checkReceipts().getFirst();
    assertEquals(checkDetail.checks().getFirst().id(), receipt.checkId());
    assertEquals(checkDetail.checks().getFirst().summary(), receipt.summary());

    assertTrue(
        recovery.checkpoints(prepared.runId, plans.workflowPlan(prepared.runId)).stream()
            .noneMatch(WorkflowCheckpoint::available));
    assertThrows(
        IllegalStateException.class,
        () -> service.recover(prepared.runId, checkpoint.id().toString()));
    assertTrue(recovery.checkpoints(recovered, plans.workflowPlan(recovered)).isEmpty());
  }

  @Test
  void savesAndRecoversBoundaryAfterCompletedSystemCheck() throws Exception {
    var workspace = workspace();
    String id = create(workspace.id());
    var plan = plans.workflowPlan(id);
    var nodes = plans.find(id);
    workflowRuns.approveWorkflow(id, plan.id());
    repo.update(id, RunStatus.RUNNING, null, null, 0, 0, 0);
    var claim = workflowRuns.claimWorkflowNode(id, plan.id(), nodes.getFirst().id(), "工作流测试");
    repo.recordAgentDefinition(
        id, claim.agentId(), claim.node().definitionRef(), claim.node().definitionSha256());
    db.update(
        "INSERT INTO agent_tool_grants(agent_id,tool_kind,tool_version) VALUES(?,?,?)",
        claim.agentId(),
        ToolKind.WORKSPACE_READ.name(),
        1);
    String sha = workspaces.read(workspace.id(), "authorized.txt").sha256();
    String toolId = dev.workbench.domain.WorkbenchIds.newId();
    repo.startTool(toolId, id, claim.agentId(), ToolKind.WORKSPACE_READ, 1, "d".repeat(64));
    repo.finishTool(
        toolId,
        InvocationStatus.COMPLETED,
        "已读取",
        null,
        1,
        ResourceVersion.encode(Map.of("authorized.txt", sha)));
    String modelId = dev.workbench.domain.WorkbenchIds.newId();
    repo.startModel(modelId, id, claim.agentId(), ModelKind.MOCK);
    repo.finishModel(modelId, InvocationStatus.COMPLETED, 3, 2, null, 5);
    repo.update(id, RunStatus.RUNNING, null, null, 1, 1, 20);
    workflowRuns.finishWorkflowNode(id, plan.id(), claim.agentId(), "已保存的分析", 1, 1, 20);

    var checkClaim = workflowRuns.claimWorkflowCheck(id, plan.id(), nodes.get(1).id());
    workflowRuns.finishWorkflowCheck(
        id,
        plan.id(),
        checkClaim.checkId(),
        dev.workbench.framework.workflow.check.SystemCheckResult.passed("模拟检查通过"),
        1,
        1,
        25);
    workflowRuns.finishWorkflowStoppedRun(id, null, StopCause.INTERRUPTED, "检查后中断", 1, 1, 30);

    var checkpoint = availableCheckpoint(id);
    assertEquals(2, checkpoint.completedNodes());
    var projected = checkpointRepository.findByPlanIdOrderByCompletedNodesAsc(plan.id().toString());
    assertEquals(
        List.of(1, 2), projected.stream().map(WorkflowCheckpointData::completedNodes).toList());
    assertEquals(checkpoint.id().toString(), projected.getLast().id());
    assertTrue(checkpointRepository.findById(checkpoint.id().toString()).isPresent());
    assertTrue(
        checkpointRepository
            .findByIdAndPlanIdAndVersion(
                checkpoint.id().toString(),
                java.util.UUID.randomUUID().toString(),
                checkpoint.version())
            .isEmpty());
    // 恢复视图的继承消耗必须来自检查点实际记录：1 次模型、1 次工具调用（tools 列恒为 0）。
    var inherited = recovery.inherited(checkpoint.id());
    assertEquals(1, inherited.rounds());
    assertEquals(1, inherited.tools());
    assertEquals(25, inherited.elapsedMs());
    String recovered = service.recover(id, checkpoint.id().toString());
    var run = await(recovered);
    assertEquals("COMPLETED", run.get("STATUS"), String.valueOf(run.get("REASON")));
    var recoveredView = executionViews.view(recovered);
    assertEquals(1, recoveredView.recovery().inherited().rounds());
    assertEquals(1, recoveredView.recovery().inherited().tools());
    assertEquals(2, recoveredView.budget().usedRounds());
    assertTrue(recoveredView.budget().inherited());
    assertTrue(recoveredView.budget().remainingRounds() >= 0);
    assertTrue(recoveredView.budget().remainingTools() >= 0);
    var child = plans.find(recovered);
    assertEquals(PlanNodeStatus.REUSED, child.getFirst().status());
    assertEquals(PlanNodeStatus.COMPLETED, child.get(1).status());
    assertEquals(PlanNodeStatus.COMPLETED, child.get(2).status());
    assertEquals(1, workflowRuns.workflowAttempts(recovered).size());
    assertEquals(1, workflowRuns.workflowChecks(recovered).size());
    assertEquals(2, repo.run(recovered).get("ROUNDS"));
    assertEquals(1, repo.run(recovered).get("TOOLS"));
  }

  @Test
  void unreadableFileSkipsCheckpointButKeepsSettledNode() throws Exception {
    var workspace = workspace();
    String id = create(workspace.id());
    var plan = plans.workflowPlan(id);
    var nodes = plans.find(id);
    workflowRuns.approveWorkflow(id, plan.id());
    repo.update(id, RunStatus.RUNNING, null, null, 0, 0, 0);
    var claim = workflowRuns.claimWorkflowNode(id, plan.id(), nodes.getFirst().id(), "工作流测试");
    repo.recordAgentDefinition(
        id, claim.agentId(), claim.node().definitionRef(), claim.node().definitionSha256());
    db.update(
        "INSERT INTO agent_tool_grants(agent_id,tool_kind,tool_version) VALUES(?,?,?)",
        claim.agentId(),
        ToolKind.WORKSPACE_READ.name(),
        1);
    String sha = workspaces.read(workspace.id(), "authorized.txt").sha256();
    String toolId = dev.workbench.domain.WorkbenchIds.newId();
    repo.startTool(toolId, id, claim.agentId(), ToolKind.WORKSPACE_READ, 1, "c".repeat(64));
    repo.finishTool(
        toolId,
        InvocationStatus.COMPLETED,
        "已读取",
        null,
        1,
        ResourceVersion.encode(Map.of("authorized.txt", sha)));
    String modelId = dev.workbench.domain.WorkbenchIds.newId();
    repo.startModel(modelId, id, claim.agentId(), ModelKind.MOCK);
    repo.finishModel(modelId, InvocationStatus.COMPLETED, 3, 2, null, 5);
    repo.update(id, RunStatus.RUNNING, null, null, 1, 1, 20);

    Files.delete(Path.of(workspace.workspacePath()).resolve("authorized.txt"));
    assertDoesNotThrow(
        () -> workflowRuns.finishWorkflowNode(id, plan.id(), claim.agentId(), "已保存的分析", 1, 1, 20));
    assertEquals(PlanNodeStatus.COMPLETED, plans.find(id).getFirst().status());
    assertTrue(recovery.checkpoints(id, plans.workflowPlan(id)).isEmpty());
    assertTrue(
        repo.events(id).stream()
            .anyMatch(
                event ->
                    "CHECKPOINT_SKIPPED".equals(event.get("EVENT_TYPE"))
                        && event.get("MESSAGE").toString().contains("不可读")));
    workflowRuns.finishWorkflowStoppedRun(id, null, StopCause.INTERRUPTED, "测试结束", 1, 1, 25);
  }

  @Test
  void changedFileOrAuthorizationBlocksRecovery() throws Exception {
    var prepared = interruptedAfterInspect();
    Path file = Path.of(prepared.workspace.workspacePath()).resolve("authorized.txt");
    Files.writeString(file, "分析后被修改的内容", StandardCharsets.UTF_8);
    var checkpoint = prepared.checkpointId;
    assertFalse(available(prepared.runId));
    assertThrows(IllegalStateException.class, () -> service.recover(prepared.runId, checkpoint));
    Files.writeString(file, "受控样例", StandardCharsets.UTF_8);
    assertTrue(available(prepared.runId));

    String taskId = repo.runSnapshot(prepared.runId).taskId().toString();
    db.update(
        "UPDATE tasks SET file_scope=?,file_scopes_json=? WHERE id=?",
        "second.txt",
        "[\"second.txt\"]",
        taskId);
    assertFalse(available(prepared.runId));
    assertThrows(IllegalStateException.class, () -> service.recover(prepared.runId, checkpoint));
    db.update(
        "UPDATE tasks SET file_scope=?,file_scopes_json=? WHERE id=?",
        "authorized.txt",
        "[\"authorized.txt\",\"second.txt\"]",
        taskId);

    String agentId = plans.find(prepared.runId).getFirst().agentId().toString();
    db.update("UPDATE agent_invocations SET result=? WHERE id=?", "已篡改", agentId);
    assertFalse(available(prepared.runId));
    assertThrows(IllegalStateException.class, () -> service.recover(prepared.runId, checkpoint));
    db.update("UPDATE agent_invocations SET result=? WHERE id=?", "已保存的分析", agentId);
    assertTrue(available(prepared.runId));
  }

  @Test
  void claimedDownstreamNodeBlocksRecovery() throws Exception {
    var prepared = interruptedAfterInspect(true);
    var checks = workflowRuns.workflowChecks(prepared.runId);
    assertEquals(1, checks.size());
    assertEquals(
        dev.workbench.framework.workflow.check.SystemCheckStatus.INTERRUPTED,
        checks.getFirst().status());
    assertFalse(available(prepared.runId));
    assertThrows(
        IllegalStateException.class, () -> service.recover(prepared.runId, prepared.checkpointId));
  }

  @Test
  void writeTaskShowsUnsupportedRecoveryReasonAndRemainingBudget() throws Exception {
    var workspace = workspace();
    String id =
        service.create(
            new CreateTaskRequest(
                "写入任务恢复边界",
                "normal",
                6,
                4,
                60,
                "mock",
                workspace.id(),
                null,
                true,
                null,
                false,
                null,
                List.of("只修改授权文件"),
                List.of("人工核对"),
                null,
                null,
                TaskEntryKind.CODE,
                null,
                null,
                List.of("authorized.txt"),
                null,
                null,
                "workspace-change",
                true,
                null));
    repo.update(id, RunStatus.INTERRUPTED, null, null, 1, 1, 10);
    var view = executionViews.view(id);
    assertNotNull(view.recoverySupport());
    assertFalse(view.recoverySupport().supported());
    assertTrue(view.recoverySupport().reason().contains("写入"));
    assertEquals(1, view.budget().usedRounds());
    assertTrue(view.budget().remainingRounds() >= 0);
  }

  private boolean available(String runId) {
    return recovery.checkpoints(runId, plans.workflowPlan(runId)).stream()
        .anyMatch(WorkflowCheckpoint::available);
  }

  private WorkflowCheckpoint availableCheckpoint(String runId) {
    return recovery.checkpoints(runId, plans.workflowPlan(runId)).stream()
        .filter(WorkflowCheckpoint::available)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("没有可用检查点"));
  }

  private Prepared interruptedBeforeFirstNode() throws Exception {
    var workspace = workspace();
    String id = create(workspace.id());
    var plan = plans.workflowPlan(id);
    workflowRuns.approveWorkflow(id, plan.id());
    repo.update(id, RunStatus.RUNNING, null, null, 0, 0, 0);
    workflowRuns.checkpointBeforeFirstNode(id, plan.id(), 0);
    workflowRuns.finishWorkflowStoppedRun(id, null, StopCause.INTERRUPTED, "首个节点启动前中断", 0, 0, 5);
    return new Prepared(id, workspace, null);
  }

  private Prepared interruptedAfterInspect() throws Exception {
    return interruptedAfterInspect(false);
  }

  private Prepared interruptedAfterInspect(boolean claimCheck) throws Exception {
    var workspace = workspace();
    String id = create(workspace.id());
    var plan = plans.workflowPlan(id);
    var nodes = plans.find(id);
    workflowRuns.approveWorkflow(id, plan.id());
    repo.update(id, RunStatus.RUNNING, null, null, 0, 0, 0);
    var claim = workflowRuns.claimWorkflowNode(id, plan.id(), nodes.getFirst().id(), "工作流测试");
    repo.recordAgentDefinition(
        id, claim.agentId(), claim.node().definitionRef(), claim.node().definitionSha256());
    db.update(
        "INSERT INTO agent_tool_grants(agent_id,tool_kind,tool_version) VALUES(?,?,?)",
        claim.agentId(),
        ToolKind.WORKSPACE_READ.name(),
        1);
    String sha = workspaces.read(workspace.id(), "authorized.txt").sha256();
    String toolId = dev.workbench.domain.WorkbenchIds.newId();
    repo.startTool(toolId, id, claim.agentId(), ToolKind.WORKSPACE_READ, 1, "b".repeat(64));
    repo.finishTool(
        toolId,
        InvocationStatus.COMPLETED,
        "已读取",
        null,
        1,
        ResourceVersion.encode(Map.of("authorized.txt", sha)));
    String modelId = dev.workbench.domain.WorkbenchIds.newId();
    repo.startModel(modelId, id, claim.agentId(), ModelKind.MOCK);
    repo.finishModel(modelId, InvocationStatus.COMPLETED, 3, 2, null, 5);
    repo.update(id, RunStatus.RUNNING, null, null, 1, 1, 20);
    workflowRuns.finishWorkflowNode(id, plan.id(), claim.agentId(), "已保存的分析", 1, 1, 20);
    // 运行仍为 RUNNING 时先取得刚保存的边界标识；是否可恢复在中断后再复核。
    var boundaryId = recovery.checkpoints(id, plans.workflowPlan(id)).getLast().id().toString();
    if (claimCheck) workflowRuns.claimWorkflowCheck(id, plan.id(), nodes.get(1).id());
    workflowRuns.finishWorkflowStoppedRun(id, null, StopCause.INTERRUPTED, "模拟节点边界中断", 1, 1, 25);
    if (!claimCheck && !available(id))
      throw new IllegalStateException("分析边界不可用；事件：" + repo.events(id));
    return new Prepared(id, workspace, boundaryId);
  }

  private String create(String workspaceId) {
    return service.create(
        new CreateTaskRequest(
            "分析授权文件",
            "normal",
            6,
            4,
            60,
            "mock",
            workspaceId,
            null,
            false,
            null,
            false,
            null,
            List.of(),
            List.of(),
            null,
            null,
            TaskEntryKind.GENERAL,
            null,
            null,
            List.of("authorized.txt", "second.txt"),
            null,
            null,
            "workspace-analysis",
            true,
            null));
  }

  private WorkspaceService.Workspace workspace() throws Exception {
    Path repoDir = Files.createDirectory(temp.resolve("repo-" + java.util.UUID.randomUUID()));
    git(repoDir, "init", "-b", "main");
    git(repoDir, "config", "user.email", "test@example.invalid");
    git(repoDir, "config", "user.name", "Test");
    Files.writeString(repoDir.resolve("authorized.txt"), "受控样例", StandardCharsets.UTF_8);
    Files.writeString(repoDir.resolve("second.txt"), "第二个文件", StandardCharsets.UTF_8);
    git(repoDir, "add", ".");
    git(repoDir, "commit", "-m", "fixture");
    return workspaces.create(repoDir.toString(), "main");
  }

  private java.util.Map<String, Object> await(String runId) throws Exception {
    java.util.Map<String, Object> run = null;
    for (int i = 0; i < 600; i++) {
      run = repo.run(runId);
      if (!Set.of("PENDING", "RUNNING", "PAUSED").contains(run.get("STATUS"))) return run;
      Thread.sleep(100);
    }
    fail("运行未在预期时间内结束：" + run);
    return run;
  }

  private com.fasterxml.jackson.databind.JsonNode workflowInput(String agentId) throws Exception {
    var state =
        agentStates
            .get(null, agentId, "agent_state", io.agentscope.core.state.AgentState.class)
            .orElseThrow();
    String content =
        state.getContext().stream()
            .map(io.agentscope.core.message.Msg::getTextContent)
            .filter(text -> text != null && text.startsWith("{\"goal\""))
            .findFirst()
            .orElseThrow();
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
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

  private record Prepared(
      String runId, WorkspaceService.Workspace workspace, String checkpointId) {}
}
