package dev.workbench.task;

import dev.workbench.domain.EventType;
import dev.workbench.domain.EventRef;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.model.ModelRateLimitException;
import dev.workbench.framework.workflow.check.StaleEvidenceException;
import dev.workbench.framework.workflow.check.SystemCheckInput;
import dev.workbench.framework.workflow.check.SystemCheckRef;
import dev.workbench.framework.workflow.check.SystemCheckResult;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.PlanRepository;
import dev.workbench.framework.workflow.plan.WorkflowAttempt;
import dev.workbench.framework.workflow.plan.WorkflowCheck;
import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import dev.workbench.framework.workflow.plan.WorkflowHandoff.ToolEvidence;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.workspace.WorkspaceService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** 维护动态计划的尝试与交接，复用运行仓库的实例与终态事务。 */
@Repository
public class WorkflowExecutionRepository {
  private final RunRepository runs;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final PlanRepository plans;
  private final WorkflowRecoveryRepository recovery;
  private final WorkspaceService workspaces;

  public WorkflowExecutionRepository(
      RunRepository runs,
      JdbcTemplate db,
      TransactionTemplate tx,
      PlanRepository plans,
      WorkflowRecoveryRepository recovery,
      WorkspaceService workspaces) {
    this.runs = runs;
    this.db = db;
    this.tx = tx;
    this.plans = plans;
    this.recovery = recovery;
    this.workspaces = workspaces;
  }

  /** 固定图上的认领、尝试、实例和多来源交接一起提交。 */
  public ClaimedNode claimWorkflowNode(String runId, UUID version, UUID nodeId, String goal) {
    return tx.execute(
        ignored -> {
          var nodes = lockWorkflowRun(runId, version);
          if (nodes.stream().anyMatch(node -> node.status() == PlanNodeStatus.RUNNING))
            throw new IllegalStateException("运行已有在途步骤。");
          var node = plans.nextReady(nodes);
          if (node == null || !nodeId.equals(node.id()))
            throw new IllegalStateException("节点不再是计划中的下一个就绪步骤。");
          if (node.systemCheck()) throw new IllegalStateException("系统检查节点必须由检查执行器认领。");
          String time = RunRepository.now();
          String agentId = RunRepository.id();
          String attemptId = RunRepository.id();
          int number =
              db.queryForObject(
                  "SELECT COALESCE(MAX(attempt_no),0)+1 FROM workflow_attempts WHERE node_id=?",
                  Integer.class,
                  nodeId.toString());
          if (number > plans.workflowPlan(runId).maxAttempts())
            throw new IllegalStateException("节点尝试次数已经达到计划上限。");
          plans.startNode(runId, nodeId, agentId, time);
          runs.insertAgent(agentId, runId, goal, node.role(), time);
          db.update(
              "INSERT INTO workflow_attempts(id,node_id,attempt_no,agent_id) VALUES(?,?,?,?)",
              attemptId,
              nodeId.toString(),
              number,
              agentId);
          var upstream = results(runId, nodeId);
          if (upstream.size() != node.dependencyIds().size()
              || upstream.stream().anyMatch(input -> input.result().isBlank()))
            throw new IllegalStateException("计划依赖缺少可用于交接的已完成结果。");
          requireCurrentCheckEvidence(runId, upstream);
          for (var input : upstream) {
            if (input.kind() == PlanNodeKind.SYSTEM_CHECK) {
              // 检查结果没有 Agent 尝试，使用独立接收收据；正文仍以检查记录为权威。
              db.update(
                  "INSERT INTO"
                      + " workflow_handoff_checks(receiver_attempt_id,dependency_node_id,check_id,accepted_at)"
                      + " VALUES(?,?,?,?)",
                  attemptId,
                  input.dependencyNodeId().toString(),
                  input.checkId().toString(),
                  time);
              continue;
            }
            db.update(
                "INSERT INTO"
                    + " workflow_handoffs(receiver_attempt_id,dependency_node_id,sender_attempt_id,schema_version,accepted_at)"
                    + " VALUES(?,?,?,1,?)",
                attemptId,
                input.dependencyNodeId().toString(),
                input.attemptId().toString(),
                time);
            for (var evidence : input.toolEvidence())
              recordToolEvidence(attemptId, input.dependencyNodeId(), evidence);
          }
          var startedRef =
              EventRef.empty()
                  .withPlan(node.planVersionId())
                  .withNode(node.id())
                  .withAttempt(UUID.fromString(attemptId))
                  .withAgent(UUID.fromString(agentId));
          if (!upstream.isEmpty())
            runs.event(
                runId,
                EventType.HANDOFF_ACCEPTED,
                node.label() + "已接收 " + upstream.size() + " 条前置结果。",
                startedRef);
          runs.event(
              runId,
              EventType.AGENT_STARTED,
              node.label() + "已启动 Agent，实例：" + agentId,
              startedRef);
          return new ClaimedNode(node, agentId, List.copyOf(upstream));
        });
  }

  public record ClaimedNode(PlanNode node, String agentId, List<WorkflowInput> dependencies) {}

  public record ClaimedCheck(
      PlanNode node, String checkId, int attemptNo, List<SystemCheckInput> inputs) {}

  /**
   * 认领系统检查节点：节点、检查记录和输入引用在同一事务提交。
   *
   * <p>不创建 Agent 实例或尝试，因此模型和工具调用为零；依赖结果仍从原权威表读取，检查冻结本次判定所见的 来源版本。
   */
  public ClaimedCheck claimWorkflowCheck(String runId, UUID version, UUID nodeId) {
    return tx.execute(
        ignored -> {
          var nodes = lockWorkflowRun(runId, version);
          if (nodes.stream().anyMatch(node -> node.status() == PlanNodeStatus.RUNNING))
            throw new IllegalStateException("运行已有在途步骤。");
          var node = plans.nextReady(nodes);
          if (node == null || !nodeId.equals(node.id()))
            throw new IllegalStateException("节点不再是计划中的下一个就绪步骤。");
          if (!node.systemCheck()) throw new IllegalStateException("该节点不是系统检查节点。");
          int number =
              db.queryForObject(
                  "SELECT COALESCE(MAX(attempt_no),0)+1 FROM workflow_checks WHERE node_id=?",
                  Integer.class,
                  nodeId.toString());
          if (number > plans.workflowPlan(runId).maxAttempts())
            throw new IllegalStateException("节点尝试次数已经达到计划上限。");
          var upstream = results(runId, nodeId);
          if (upstream.size() != node.dependencyIds().size()
              || upstream.stream()
                  .anyMatch(input -> input.result() == null || input.result().isBlank()))
            throw new IllegalStateException("计划依赖缺少可用于交接的已完成结果。");
          requireCurrentCheckEvidence(runId, upstream);
          String time = RunRepository.now();
          plans.startCheckNode(runId, nodeId, time);
          String checkId = RunRepository.id();
          db.update(
              "INSERT INTO"
                  + " workflow_checks(id,node_id,attempt_no,check_id,check_version,status,inputs_sha256,started_at)"
                  + " VALUES(?,?,?,?,?,?,?,?)",
              checkId,
              nodeId.toString(),
              number,
              node.checkRef().id(),
              node.checkRef().version(),
              SystemCheckStatus.RUNNING.name(),
              inputsFingerprint(node, upstream),
              time);
          for (var input : upstream) {
            db.update(
                "INSERT INTO"
                    + " workflow_check_inputs(check_id,dependency_node_id,source_kind,source_attempt_id,source_check_id,source_run_id,result_sha256)"
                    + " VALUES(?,?,?,?,?,?,?)",
                checkId,
                input.dependencyNodeId().toString(),
                input.kind().name(),
                input.attemptId() == null ? null : input.attemptId().toString(),
                input.checkId() == null ? null : input.checkId().toString(),
                input.sourceRunId().toString(),
                AgentAssemblySnapshot.sha256(input.result()));
          }
          runs.event(
              runId,
              EventType.CHECK_STARTED,
              node.label() + "开始执行系统检查 " + node.checkRef() + "。",
              EventRef.empty()
                  .withPlan(node.planVersionId())
                  .withNode(node.id())
                  .withCheck(UUID.fromString(checkId)));
          return new ClaimedCheck(
              node, checkId, number, upstream.stream().map(WorkflowInput::toCheckInput).toList());
        });
  }

  /** 输入摘要只包含来源标识与结果摘要，用于发现检查后来源被改写。 */
  private static String inputsFingerprint(PlanNode node, List<WorkflowInput> inputs) {
    var fields = new StringBuilder("workflow-check-inputs-v1|").append(node.id());
    for (var input :
        inputs.stream()
            .sorted(java.util.Comparator.comparing(item -> item.dependencyNodeId().toString()))
            .toList()) {
      fields
          .append('|')
          .append(input.dependencyNodeId())
          .append(':')
          .append(input.kind())
          .append(':')
          .append(
              input.attemptId() != null
                  ? "attempt=" + input.attemptId()
                  : "check=" + input.checkId())
          .append(':')
          .append(AgentAssemblySnapshot.sha256(input.result()));
    }
    return AgentAssemblySnapshot.sha256(fields.toString());
  }

  /**
   * 结算系统检查。
   *
   * @return 运行是否已经结束（完成或因检查失败阻塞）
   */
  public boolean finishWorkflowCheck(
      String runId,
      UUID version,
      String checkId,
      SystemCheckResult result,
      int rounds,
      int tools,
      long elapsed) {
    return Boolean.TRUE.equals(
        tx.execute(
            ignored -> {
              var nodes = lockWorkflowRun(runId, version);
              var saved =
                  db
                      .query(
                          """
                          SELECT c.node_id,c.status,n.node_label
                          FROM workflow_checks c JOIN plan_nodes n ON n.id=c.node_id
                          WHERE c.id=? AND n.run_id=?
                          """,
                          (row, index) ->
                              new CheckSettlement(
                                  UUID.fromString(row.getString("node_id")),
                                  row.getString("status"),
                                  row.getString("node_label")),
                          checkId,
                          runId)
                      .stream()
                      .findFirst()
                      .orElseThrow(() -> new IllegalStateException("找不到该检查记录。"));
              if (!SystemCheckStatus.RUNNING.name().equals(saved.status()))
                throw new IllegalStateException("检查记录已经结算。");
              if (nodes.stream()
                  .noneMatch(
                      node ->
                          node.id().equals(saved.nodeId())
                              && node.status() == PlanNodeStatus.RUNNING))
                throw new IllegalStateException("当前运行没有在途的系统检查节点。");
              String time = RunRepository.now();
              if (db.update(
                      "UPDATE workflow_checks SET status=?,summary=?,evidence=?,"
                          + "external_evidence_id=?,finished_at=? WHERE id=? AND status='RUNNING'",
                      result.status().name(),
                      result.summary(),
                      result.evidence(),
                      result.externalEvidenceId(),
                      time,
                      checkId)
                  != 1) throw new IllegalStateException("检查记录已经结算。");
              var checked =
                  nodes.stream()
                      .filter(node -> node.id().equals(saved.nodeId()))
                      .findFirst()
                      .orElseThrow(() -> new IllegalStateException("当前运行没有该检查节点。"));
              var checkRef =
                  EventRef.empty()
                      .withPlan(checked.planVersionId())
                      .withNode(checked.id())
                      .withCheck(UUID.fromString(checkId));
              runs.event(
                  runId,
                  EventType.CHECK_FINISHED,
                  saved.label() + "检查结果：" + result.status().name() + "。",
                  checkRef);
              if (!result.status().passed()) {
                plans.finishCheckNode(runId, saved.nodeId(), PlanNodeStatus.FAILED, time);
                runs.update(
                    runId,
                    RunStatus.BLOCKED,
                    result.summary(),
                    null,
                    rounds,
                    tools,
                    elapsed,
                    StopCause.CHECK_FAILED);
                runs.event(runId, EventType.BLOCKED, "系统检查未通过：" + result.summary(), checkRef);
                if (TaskSupplementService.REPRODUCTION_MISSING_INFO.equals(result.reasonCode()))
                  openSupplementRequest(runId, checked, result.reasonCode());
                return true;
              }
              plans.finishCheckNode(runId, saved.nodeId(), PlanNodeStatus.COMPLETED, time);
              var updated = plans.find(runId);
              if (updated.stream()
                  .allMatch(
                      node ->
                          node.status() == PlanNodeStatus.COMPLETED
                              || node.status() == PlanNodeStatus.REUSED)) {
                completeRun(runId, updated.size(), rounds, tools, elapsed);
                return true;
              }
              recovery.saveBoundary(runId, plans.workflowPlan(runId), updated, elapsed);
              return false;
            }));
  }

  private record CheckSettlement(UUID nodeId, String status, String label) {}

  /**
   * 复现缺少可执行测试时保存待补充事项，供流程图展示问题并接收用户提交。
   *
   * <p>同一运行已有未处理请求时不重复创建；提问使用稳定文案，检测细节仍以检查记录为权威。
   */
  private void openSupplementRequest(String runId, PlanNode node, String reasonCode) {
    Integer existing =
        db.queryForObject(
            "SELECT COUNT(*) FROM run_supplements WHERE run_id=? AND status='OPEN'",
            Integer.class,
            runId);
    if (existing != null && existing > 0) return;
    String taskId = runs.runSnapshot(runId).taskId().toString();
    db.update(
        "INSERT INTO"
            + " run_supplements(id,run_id,node_id,reason_code,question,kind,status,task_version,"
            + "created_at) VALUES(?,?,?,?,?,'TEST_FILE','OPEN',?,?)",
        RunRepository.id(),
        runId,
        node.id().toString(),
        reasonCode,
        "缺少可执行测试或验证条件。请补充一个 tests/ 下的 test_*.py 文件内容；"
            + "提交通过校验后将从安全边界继续并重新执行复现检查。",
        runs.taskVersion(taskId),
        RunRepository.now());
    runs.event(
        runId,
        EventType.SUPPLEMENT_REQUESTED,
        "复现检查缺少可执行测试，已保存待补充事项；补充前不会启动任何下游节点。",
        EventRef.empty().withPlan(node.planVersionId()).withNode(node.id()));
  }

  /** 仅接受已结算的限流拒绝；带有工具调用历史的尝试原样结束。 */
  public boolean requeueAfterRateLimit(String runId, UUID version, String agentId) {
    return Boolean.TRUE.equals(
        tx.execute(
            ignored -> {
              var node =
                  lockWorkflowRun(runId, version).stream()
                      .filter(
                          candidate ->
                              candidate.status() == PlanNodeStatus.RUNNING
                                  && UUID.fromString(agentId).equals(candidate.agentId()))
                      .findFirst()
                      .orElseThrow(() -> new IllegalStateException("找不到可结算的节点尝试。"));
              int number =
                  db.queryForObject(
                      "SELECT attempt_no FROM workflow_attempts WHERE agent_id=?",
                      Integer.class,
                      agentId);
              if (number >= plans.workflowPlan(runId).maxAttempts()
                  || runs.countAgentToolCalls(agentId) != 0) return false;
              int rejected =
                  db.queryForObject(
                      """
                      SELECT COUNT(*) FROM model_invocations WHERE run_id=? AND agent_id=?
                        AND status='FAILED' AND error_message=?
                      """,
                      Integer.class,
                      runId,
                      agentId,
                      ModelRateLimitException.SAFE_MESSAGE);
              int unresolved =
                  db.queryForObject(
                      """
                      SELECT COUNT(*) FROM model_invocations WHERE run_id=? AND agent_id=?
                        AND (status<>'COMPLETED' AND (status<>'FAILED' OR error_message IS DISTINCT FROM ?))
                      """,
                      Integer.class,
                      runId,
                      agentId,
                      ModelRateLimitException.SAFE_MESSAGE);
              if (rejected != 1 || unresolved != 0) return false;
              runs.finishAgent(agentId, InvocationStatus.FAILED, null);
              recordAttemptStop(agentId, StopCause.MODEL_RATE_LIMIT);
              plans.requeueFailedNode(runId, node.id(), agentId);
              var attemptId =
                  db.queryForObject(
                      "SELECT id FROM workflow_attempts WHERE agent_id=?",
                      String.class,
                      agentId);
              runs.event(
                  runId,
                  EventType.NODE_RETRY_SCHEDULED,
                  node.label() + "因模型限流安排再次尝试。",
                  EventRef.empty()
                      .withPlan(node.planVersionId())
                      .withNode(node.id())
                      .withAttempt(UUID.fromString(attemptId))
                      .withAgent(UUID.fromString(agentId)));
              return true;
            }));
  }

  /** 转移已授权的直接依赖结果及来源，可追溯到安全恢复中复用的实例。 */
  public record WorkflowInput(
      PlanNodeKind kind,
      String key,
      UUID nodeId,
      UUID dependencyNodeId,
      UUID sourceRunId,
      String label,
      UUID attemptId,
      UUID agentId,
      UUID checkId,
      SystemCheckStatus checkStatus,
      String result,
      String evidence,
      List<ToolEvidence> toolEvidence) {
    public WorkflowInput {
      toolEvidence = toolEvidence == null ? List.of() : List.copyOf(toolEvidence);
      if (kind == PlanNodeKind.AGENT && (attemptId == null || agentId == null))
        throw new IllegalArgumentException("Agent 交接缺少尝试或实例标识。");
      if (kind == PlanNodeKind.SYSTEM_CHECK && (checkId == null || attemptId != null))
        throw new IllegalArgumentException("系统检查交接缺少检查标识或混入 Agent 尝试。");
    }

    /** 检查输入需要区分来源类型，Agent 结果沿用原有字段。 */
    public SystemCheckInput toCheckInput() {
      return new SystemCheckInput(
          dependencyNodeId,
          key,
          label,
          kind,
          attemptId,
          checkId,
          true,
          checkStatus,
          result,
          toolEvidence);
    }
  }

  /**
   * 消费系统检查结果前再次核对当前工作区版本。
   *
   * <p>只复核直接依赖中的检查证据。Bug 复现检查在写节点前仍应保持一致；回归检查在审查前必须对应当前 代码。证据缺失、归属不符或工作区变化时拒绝启动下游节点，并保留原检查记录。
   */
  private void requireCurrentCheckEvidence(String runId, List<WorkflowInput> upstream) {
    if (upstream.stream().noneMatch(input -> input.kind() == PlanNodeKind.SYSTEM_CHECK)) return;
    var task = runs.taskDefinition(runs.runSnapshot(runId).taskId().toString());
    for (var input : upstream) {
      if (input.kind() != PlanNodeKind.SYSTEM_CHECK) continue;
      if (input.checkId() == null) continue;
      var externalRows =
          db.query(
              "SELECT external_evidence_id FROM workflow_checks WHERE id=?",
              (row, index) -> row.getString("external_evidence_id"),
              input.checkId().toString());
      String external = externalRows.isEmpty() ? null : externalRows.getFirst();
      if (external == null || external.isBlank()) continue;
      var evidence =
          db
              .query(
                  """
                  SELECT bug_id,workspace_id,workspace_fingerprint FROM bug_checks WHERE id=?
                  """,
                  (row, index) ->
                      new ExternalEvidence(
                          row.getString("bug_id"),
                          row.getString("workspace_id"),
                          row.getString("workspace_fingerprint")),
                  external)
              .stream()
              .findFirst();
      if (evidence.isEmpty()) throw new StaleEvidenceException("系统检查的外部证据已缺失，不能继续依赖。");
      var item = evidence.get();
      if (!java.util.Objects.equals(task.bugId(), item.bugId())
          || !java.util.Objects.equals(task.workspaceId(), item.workspaceId()))
        throw new StaleEvidenceException("系统检查证据的 Bug 或工作区归属与当前任务不一致。");
      String current;
      try {
        current = workspaces.verificationFingerprint(task.workspaceId());
      } catch (RuntimeException e) {
        throw new StaleEvidenceException("无法确认当前工作区版本，不能继续使用该检查证据。");
      }
      if (!java.util.Objects.equals(current, item.workspaceFingerprint()))
        throw new StaleEvidenceException("系统检查后工作区已变化，旧检查证据不能继续放行下游节点。");
    }
  }

  private record ExternalEvidence(String bugId, String workspaceId, String workspaceFingerprint) {}

  private List<PlanNode> lockWorkflowRun(String runId, UUID version) {
    var status =
        db.queryForObject("SELECT status FROM runs WHERE id=? FOR UPDATE", String.class, runId);
    var plan = plans.workflowPlan(runId);
    if (!RunStatus.RUNNING.name().equals(status)
        || plan == null
        || !plan.id().equals(version)
        || plan.approvedAt() == null) throw new IllegalStateException("运行未执行，或有效计划尚未确认。");
    var nodes = plans.checkedWorkflowNodes(runId, plan);
    plan.requireTaskAuthorization(runs.taskDefinition(runs.runSnapshot(runId).taskId().toString()));
    recovery.validateReuse(runId, plan, nodes);
    return nodes;
  }

  public void checkpointBeforeFirstNode(String runId, UUID version, long elapsed) {
    tx.executeWithoutResult(
        ignored -> {
          var nodes = lockWorkflowRun(runId, version);
          recovery.saveBoundary(runId, plans.workflowPlan(runId), nodes, elapsed);
        });
  }

  /** 结算节点与后续安全边界；最后一个节点与运行终态一起提交。 */
  public boolean finishWorkflowNode(
      String runId,
      UUID version,
      String agentId,
      String result,
      int rounds,
      int tools,
      long elapsed) {
    return Boolean.TRUE.equals(
        tx.execute(
            ignored -> {
              var nodes = lockWorkflowRun(runId, version);
              if (nodes.stream()
                  .noneMatch(
                      node ->
                          node.status() == PlanNodeStatus.RUNNING
                              && UUID.fromString(agentId).equals(node.agentId())))
                throw new IllegalStateException("运行中没有可结算的该节点实例。");
              runs.finishAgent(agentId, InvocationStatus.COMPLETED, result);
              var updated = plans.find(runId);
              if (updated.stream()
                  .allMatch(
                      node ->
                          node.status() == PlanNodeStatus.COMPLETED
                              || node.status() == PlanNodeStatus.REUSED)) {
                completeRun(runId, updated.size(), rounds, tools, elapsed);
                return true;
              }
              recovery.saveBoundary(runId, plans.workflowPlan(runId), updated, elapsed);
              return false;
            }));
  }

  private void completeRun(String runId, int nodeCount, int rounds, int tools, long elapsed) {
    var outputs = results(runId, null);
    if (outputs.size() != nodeCount) throw new IllegalStateException("工作流缺少已结算的节点结果。");
    StringBuilder result = new StringBuilder();
    for (var output : outputs) {
      result.append(output.label()).append('\n');
      result.append(output.result(), 0, Math.min(550, output.result().length())).append("\n\n");
    }
    if (result.length() > 1900) result.setLength(1900);
    var scope = plans.workflowPlan(runId).scope();
    String reason = null;
    if (scope == WorkflowTemplate.Scope.WORKSPACE_BUG_FIX) {
      reason = "回归检查通过，仍需用户核对代码差异、原问题与验收条件；系统不会自动合并或关闭 Bug。";
    } else if (scope.allowsWrite()) {
      boolean confirmedWrite =
          outputs.stream()
              .flatMap(output -> output.toolEvidence().stream())
              .anyMatch(evidence -> evidence.tool() == ToolKind.WORKSPACE_WRITE);
      reason = confirmedWrite ? "已保存写入记录，等待核对工作区差异和验收条件。" : "尚无成功写入记录，请核对修改结果和验收条件。";
    } else if (scope.hasWorkspace()
        && outputs.stream().allMatch(output -> output.toolEvidence().isEmpty())) {
      reason = "尚无成功的授权文件读取或搜索记录，分析结果需要核实。";
    }
    boolean blocked = reason != null;
    runs.update(
        runId,
        blocked ? RunStatus.BLOCKED : RunStatus.COMPLETED,
        reason,
        result.toString().stripTrailing(),
        rounds,
        tools,
        elapsed);
    runs.event(
        runId,
        blocked ? EventType.BLOCKED : EventType.RUN_COMPLETED,
        blocked ? reason : "工作流已完成，已保存各步骤的模型结果。");
  }

  /**
   * 读取已结算的直接依赖结果。
   *
   * <p>Agent 结果仍从原始实例调用读取；系统检查结果从检查记录读取，不复制正文。恢复复用的 Agent 节点继续 指向父运行的原始尝试。调用方必须已持有运行锁并在同一事务内使用返回值。
   */
  private List<WorkflowInput> results(String runId, UUID recipient) {
    String recipientId = recipient == null ? null : recipient.toString();
    var inputs = new java.util.ArrayList<SequencedInput>();
    db.query(
        """
        SELECT n.id AS dependency_id,n.node_key,n.node_label,n.sequence_no,
          p.id AS source_node,p.run_id AS source_run,a.id AS attempt_id,i.id AS agent_id,i.result,
          p.workspace_read,p.workspace_write
        FROM plan_nodes n JOIN runs r ON r.id=n.run_id
        JOIN workflow_attempts a ON a.agent_id=COALESCE(n.agent_id,n.source_agent_id)
        JOIN plan_nodes p ON p.id=a.node_id AND p.agent_id=a.agent_id
        JOIN agent_invocations i ON i.id=a.agent_id AND i.run_id=p.run_id
        WHERE n.run_id=? AND n.node_kind='AGENT' AND p.status='COMPLETED'
        AND i.status='COMPLETED' AND i.result IS NOT NULL
        AND i.definition_id=n.definition_id AND i.definition_version=n.definition_version
        AND i.definition_sha256=n.definition_sha256
        AND ((n.status='COMPLETED' AND n.id=p.id)
          OR (n.status='REUSED' AND p.run_id=r.parent_id AND n.node_key=p.node_key))
        AND (CAST(? AS VARCHAR(36)) IS NULL OR EXISTS (
          SELECT 1 FROM plan_node_dependencies d
          WHERE d.run_id=n.run_id AND d.node_id=? AND d.prerequisite_id=n.id))
        ORDER BY n.sequence_no
        """,
        (row, index) ->
            inputs.add(
                new SequencedInput(
                    row.getInt("sequence_no"),
                    new WorkflowInput(
                        PlanNodeKind.AGENT,
                        row.getString("node_key"),
                        UUID.fromString(row.getString("source_node")),
                        UUID.fromString(row.getString("dependency_id")),
                        UUID.fromString(row.getString("source_run")),
                        row.getString("node_label"),
                        UUID.fromString(row.getString("attempt_id")),
                        UUID.fromString(row.getString("agent_id")),
                        null,
                        null,
                        row.getString("result"),
                        null,
                        row.getBoolean("workspace_read")
                            ? toolEvidence(
                                row.getString("source_run"),
                                row.getString("agent_id"),
                                row.getBoolean("workspace_write"))
                            : List.of()))),
        runId,
        recipientId,
        recipientId);
    db.query(
        """
        SELECT n.id AS dependency_id,n.node_key,n.node_label,n.sequence_no,n.run_id,
          c.id AS check_id,c.status AS check_status,c.summary,c.evidence
        FROM plan_nodes n JOIN workflow_checks c ON c.node_id=n.id
          AND c.attempt_no=(SELECT MAX(x.attempt_no) FROM workflow_checks x WHERE x.node_id=n.id)
        WHERE n.run_id=? AND n.node_kind='SYSTEM_CHECK' AND n.status='COMPLETED'
        AND c.status='PASSED'
        AND (CAST(? AS VARCHAR(36)) IS NULL OR EXISTS (
          SELECT 1 FROM plan_node_dependencies d
          WHERE d.run_id=n.run_id AND d.node_id=? AND d.prerequisite_id=n.id))
        ORDER BY n.sequence_no
        """,
        (row, index) ->
            inputs.add(
                new SequencedInput(
                    row.getInt("sequence_no"),
                    new WorkflowInput(
                        PlanNodeKind.SYSTEM_CHECK,
                        row.getString("node_key"),
                        UUID.fromString(row.getString("dependency_id")),
                        UUID.fromString(row.getString("dependency_id")),
                        UUID.fromString(row.getString("run_id")),
                        row.getString("node_label"),
                        null,
                        null,
                        UUID.fromString(row.getString("check_id")),
                        SystemCheckStatus.valueOf(row.getString("check_status")),
                        row.getString("summary"),
                        row.getString("evidence"),
                        List.of()))),
        runId,
        recipientId,
        recipientId);
    inputs.sort(java.util.Comparator.comparingInt(SequencedInput::sequence));
    return inputs.stream().map(SequencedInput::input).toList();
  }

  private record SequencedInput(int sequence, WorkflowInput input) {}

  private List<ToolEvidence> toolEvidence(String runId, String agentId, boolean writeAuthorized) {
    return db.query(
        """
        SELECT i.id AS tool_id,g.tool_kind,i.tool_version,i.resource_version
        FROM tool_invocations i JOIN agent_tool_grants g
          ON g.agent_id=i.agent_id AND g.tool_version=i.tool_version
        WHERE i.run_id=? AND i.agent_id=? AND i.status='COMPLETED'
          AND ((g.tool_kind='WORKSPACE_READ' AND i.tool_name=?)
            OR (g.tool_kind='WORKSPACE_SEARCH' AND i.tool_name=?)
            OR (g.tool_kind='WORKSPACE_WRITE' AND i.tool_name=? AND ?))
        ORDER BY i.created_at,i.id
        """,
        WorkflowExecutionRepository::readToolEvidence,
        runId,
        agentId,
        ToolKind.WORKSPACE_READ.recordName(),
        ToolKind.WORKSPACE_SEARCH.recordName(),
        ToolKind.WORKSPACE_WRITE.recordName(),
        writeAuthorized);
  }

  private void recordToolEvidence(String receiver, UUID dependency, ToolEvidence evidence) {
    if (evidence.tool() == ToolKind.WORKSPACE_WRITE) {
      db.update(
          """
          INSERT INTO workflow_handoff_write_evidence(
            receiver_attempt_id,dependency_node_id,tool_id,tool_version,resource_version)
          VALUES(?,?,?,?,?)
          """,
          receiver,
          dependency.toString(),
          evidence.toolCallId().toString(),
          evidence.version(),
          evidence.resourceVersion());
    } else {
      db.update(
          """
          INSERT INTO workflow_handoff_tool_evidence(
            receiver_attempt_id,dependency_node_id,tool_id,tool_kind,tool_version,resource_version)
          VALUES(?,?,?,?,?,?)
          """,
          receiver,
          dependency.toString(),
          evidence.toolCallId().toString(),
          evidence.tool().name(),
          evidence.version(),
          evidence.resourceVersion());
    }
  }

  private List<ToolEvidence> acceptedToolEvidence(String receiver, String dependency) {
    return db.query(
        """
        SELECT tool_id,tool_kind,tool_version,resource_version FROM workflow_handoff_tool_evidence
        WHERE receiver_attempt_id=? AND dependency_node_id=?
        UNION ALL
        SELECT tool_id,'WORKSPACE_WRITE' AS tool_kind,tool_version,resource_version
        FROM workflow_handoff_write_evidence
        WHERE receiver_attempt_id=? AND dependency_node_id=?
        ORDER BY tool_id
        """,
        WorkflowExecutionRepository::readToolEvidence,
        receiver,
        dependency,
        receiver,
        dependency);
  }

  private static ToolEvidence readToolEvidence(java.sql.ResultSet row, int index)
      throws java.sql.SQLException {
    return new ToolEvidence(
        UUID.fromString(row.getString("tool_id")),
        ToolKind.valueOf(row.getString("tool_kind")),
        row.getInt("tool_version"),
        row.getString("resource_version"));
  }

  public void approveWorkflow(String runId, UUID version) {
    tx.executeWithoutResult(
        ignored -> {
          var status =
              db.queryForObject(
                  "SELECT status FROM runs WHERE id=? FOR UPDATE", String.class, runId);
          var plan = plans.workflowPlan(runId);
          if (!RunStatus.PENDING.name().equals(status)
              || plan == null
              || !plan.id().equals(version)
              || !plan.reviewRequired()
              || plan.approvedAt() != null) throw new IllegalStateException("当前运行没有待确认的该计划版本。");
          plans.checkedWorkflowNodes(runId, plan);
          plan.requireTaskAuthorization(
              runs.taskDefinition(runs.runSnapshot(runId).taskId().toString()));
          if (db.update(
                  "UPDATE workflow_plans SET approved_at=? WHERE id=? AND run_id=?"
                      + " AND review_required AND approved_at IS NULL",
                  RunRepository.now(),
                  version.toString(),
                  runId)
              != 1) throw new IllegalStateException("当前运行没有待确认的该计划版本。");
          runs.event(
              runId,
              EventType.PLAN_APPROVED,
              "用户确认了工作流计划 " + version + "。",
              EventRef.plan(version));
        });
  }

  public List<WorkflowAttempt> workflowAttempts(String runId) {
    return db.query(
        """
        SELECT a.id,a.node_id,a.attempt_no,a.agent_id,a.stop_cause,i.status,i.created_at,i.updated_at
        FROM workflow_attempts a JOIN agent_invocations i ON i.id=a.agent_id
        JOIN plan_nodes n ON n.id=a.node_id AND n.run_id=i.run_id
        WHERE n.run_id=? ORDER BY n.sequence_no,a.attempt_no
        """,
        (row, index) -> {
          String cause = row.getString("stop_cause");
          return new WorkflowAttempt(
              UUID.fromString(row.getString("id")),
              UUID.fromString(row.getString("node_id")),
              row.getInt("attempt_no"),
              UUID.fromString(row.getString("agent_id")),
              InvocationStatus.valueOf(row.getString("status")),
              Instant.parse(row.getString("created_at")),
              Instant.parse(row.getString("updated_at")),
              cause == null ? null : StopCause.valueOf(cause));
        },
        runId);
  }

  /** 按计划顺序返回系统检查尝试；没有该表记录的旧运行返回空列表。 */
  public List<WorkflowCheck> workflowChecks(String runId) {
    var inputsByCheck = new java.util.LinkedHashMap<UUID, java.util.List<WorkflowCheck.Input>>();
    db.query(
        """
        SELECT i.check_id,i.dependency_node_id,i.source_kind,i.source_attempt_id,
          i.source_check_id,i.source_run_id,i.result_sha256
        FROM workflow_check_inputs i JOIN workflow_checks c ON c.id=i.check_id
        JOIN plan_nodes n ON n.id=c.node_id
        JOIN plan_nodes d ON d.id=i.dependency_node_id
        WHERE n.run_id=? ORDER BY i.check_id,d.sequence_no
        """,
        row -> {
          inputsByCheck
              .computeIfAbsent(
                  UUID.fromString(row.getString("check_id")), key -> new java.util.ArrayList<>())
              .add(
                  new WorkflowCheck.Input(
                      UUID.fromString(row.getString("dependency_node_id")),
                      PlanNodeKind.valueOf(row.getString("source_kind")),
                      nullableUuid(row.getString("source_attempt_id")),
                      nullableUuid(row.getString("source_check_id")),
                      UUID.fromString(row.getString("source_run_id")),
                      row.getString("result_sha256")));
        },
        runId);
    var task = runs.taskDefinition(runs.runSnapshot(runId).taskId().toString());
    String currentFingerprint = currentWorkspaceFingerprint(task.workspaceId());
    return db.query(
        """
        SELECT c.*,n.node_key FROM workflow_checks c JOIN plan_nodes n ON n.id=c.node_id
        WHERE n.run_id=? ORDER BY n.sequence_no,c.attempt_no
        """,
        (row, index) -> {
          UUID id = UUID.fromString(row.getString("id"));
          String finished = row.getString("finished_at");
          String external = row.getString("external_evidence_id");
          return new WorkflowCheck(
              id,
              UUID.fromString(row.getString("node_id")),
              row.getString("node_key"),
              row.getInt("attempt_no"),
              new SystemCheckRef(row.getString("check_id"), row.getInt("check_version")),
              SystemCheckStatus.valueOf(row.getString("status")),
              row.getString("summary"),
              row.getString("evidence"),
              external,
              externalEvidenceCurrent(external, currentFingerprint),
              row.getString("inputs_sha256"),
              Instant.parse(row.getString("started_at")),
              finished == null ? null : Instant.parse(finished),
              inputsByCheck.getOrDefault(id, java.util.List.of()));
        },
        runId);
  }

  private Boolean externalEvidenceCurrent(String external, String currentFingerprint) {
    if (external == null) return null;
    var rows =
        db.query(
            "SELECT workspace_fingerprint FROM bug_checks WHERE id=?",
            (row, index) -> row.getString("workspace_fingerprint"),
            external);
    String fingerprint = rows.isEmpty() ? null : rows.getFirst();
    return fingerprint != null && fingerprint.equals(currentFingerprint);
  }

  private String currentWorkspaceFingerprint(String workspaceId) {
    if (workspaceId == null) return null;
    try {
      return workspaces.verificationFingerprint(workspaceId);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static UUID nullableUuid(String value) {
    return value == null ? null : UUID.fromString(value);
  }

  public List<WorkflowHandoff> workflowHandoffs(String runId) {
    return db.query(
        """
        SELECT h.schema_version,h.accepted_at,s.id AS sender_attempt,s.node_id AS sender_node,
               s.agent_id AS sender_agent,h.dependency_node_id,r.id AS receiver_attempt,r.node_id AS receiver_node,
               r.agent_id AS receiver_agent
        FROM workflow_handoffs h JOIN workflow_attempts r ON r.id=h.receiver_attempt_id
        JOIN workflow_attempts s ON s.id=h.sender_attempt_id
        JOIN plan_nodes n ON n.id=r.node_id JOIN plan_nodes p ON p.id=s.node_id
        JOIN plan_nodes d ON d.id=h.dependency_node_id AND d.run_id=n.run_id
        JOIN plan_node_dependencies e ON e.run_id=n.run_id AND e.node_id=n.id
          AND e.prerequisite_id=d.id
        WHERE n.run_id=? AND d.plan_version_id=n.plan_version_id
        ORDER BY n.sequence_no,r.attempt_no,p.sequence_no,s.attempt_no
        """,
        (row, index) ->
            new WorkflowHandoff(
                row.getInt("schema_version"),
                UUID.fromString(row.getString("sender_attempt")),
                UUID.fromString(row.getString("sender_node")),
                UUID.fromString(row.getString("sender_agent")),
                UUID.fromString(row.getString("dependency_node_id")),
                UUID.fromString(row.getString("receiver_attempt")),
                UUID.fromString(row.getString("receiver_node")),
                UUID.fromString(row.getString("receiver_agent")),
                Instant.parse(row.getString("accepted_at")),
                acceptedToolEvidence(
                    row.getString("receiver_attempt"), row.getString("dependency_node_id"))),
        runId);
  }

  /** 动态工作流的停止事实来自尝试终态和事件，无需构造旧版角色交接。 */
  public void finishWorkflowStoppedRun(
      String runId,
      UUID agentId,
      StopCause cause,
      String reason,
      int rounds,
      int tools,
      long elapsed) {
    tx.executeWithoutResult(
        ignored -> {
          if (agentId != null) {
            Integer active =
                db.queryForObject(
                    """
                    SELECT COUNT(*) FROM workflow_attempts a JOIN plan_nodes n ON n.id=a.node_id
                    WHERE n.run_id=? AND n.agent_id=a.agent_id AND a.agent_id=?
                    AND n.plan_version_id IS NOT NULL AND n.status='RUNNING'
                    """,
                    Integer.class,
                    runId,
                    agentId.toString());
            if (active == null || active != 1) throw new IllegalStateException("工作流没有可结算的该尝试。");
            runs.finishAgent(
                agentId.toString(), InvocationStatus.fromRunStatus(cause.status()), null);
            recordAttemptStop(agentId.toString(), cause);
          }
          // 停止发生在检查结算前时，保留检查记录并标明结果未知，不能当作通过。
          db.update(
              "UPDATE workflow_checks SET status=?,summary=COALESCE(summary,?),finished_at=?"
                  + " WHERE status=? AND node_id IN (SELECT id FROM plan_nodes WHERE run_id=?)",
              SystemCheckStatus.INTERRUPTED.name(),
              "运行在检查结算前停止，检查结果未知。",
              RunRepository.now(),
              SystemCheckStatus.RUNNING.name(),
              runId);
          runs.settleStoppedRun(runId, cause, reason, rounds, tools, elapsed);
        });
  }

  private void recordAttemptStop(String agentId, StopCause cause) {
    int changed =
        db.update(
            "UPDATE workflow_attempts SET stop_cause=? WHERE agent_id=? AND stop_cause IS NULL",
            cause.name(),
            agentId);
    if (changed != 1) throw new IllegalStateException("无法登记节点尝试停止原因。");
  }
}
