package dev.workbench.task;

import dev.workbench.domain.EventRef;
import dev.workbench.domain.EventType;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.model.ModelRateLimitException;
import dev.workbench.framework.tool.ResourceVersion;
import dev.workbench.framework.workflow.check.StaleEvidenceException;
import dev.workbench.framework.workflow.check.SystemCheckInput;
import dev.workbench.framework.workflow.check.SystemCheckRef;
import dev.workbench.framework.workflow.check.SystemCheckResult;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.planning.PlanService;
import dev.workbench.framework.workflow.plan.WorkflowAttempt;
import dev.workbench.framework.workflow.plan.WorkflowCheck;
import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import dev.workbench.framework.workflow.plan.WorkflowHandoff.ToolEvidence;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.persistence.repository.PlanGraphRepository;
import dev.workbench.persistence.repository.RunRepository;
import dev.workbench.persistence.repository.SupplementRepository;
import dev.workbench.persistence.repository.WorkflowExecutionRepository;
import dev.workbench.persistence.repository.view.CheckReceipt;
import dev.workbench.workspace.WorkspaceService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import dev.workbench.store.RunRecordService;
import dev.workbench.store.HandoffValidator;
import dev.workbench.store.ReviewCompletion;
import dev.workbench.store.StopCompletion;
import dev.workbench.planning.RecoveryLeaseService;

/** 维护动态计划的尝试与交接，复用运行仓库的实例与终态事务。 */
@Service
public class WorkflowExecutionService {
  private final RunRecordService runs;
  private final WorkflowExecutionRepository records;
  private final TransactionTemplate tx;
  private final PlanService plans;
  private final WorkflowRecoveryService recovery;
  private final WorkspaceService workspaces;
  private final RunRepository runRows;
  private final PlanGraphRepository planRows;
  private final SupplementRepository supplements;

  public WorkflowExecutionService(
      RunRecordService runs,
      WorkflowExecutionRepository records,
      TransactionTemplate tx,
      PlanService plans,
      WorkflowRecoveryService recovery,
      WorkspaceService workspaces,
      RunRepository runRows,
      PlanGraphRepository planRows,
      SupplementRepository supplements) {
    this.runs = runs;
    this.records = records;
    this.tx = tx;
    this.plans = plans;
    this.recovery = recovery;
    this.workspaces = workspaces;
    this.runRows = runRows;
    this.planRows = planRows;
    this.supplements = supplements;
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
          String time = dev.workbench.domain.WorkbenchTime.now();
          String agentId = dev.workbench.domain.WorkbenchIds.newId();
          String attemptId = dev.workbench.domain.WorkbenchIds.newId();
          int number = records.nextAttemptNumber(nodeId.toString());
          if (number > plans.workflowPlan(runId).maxAttempts())
            throw new IllegalStateException("节点尝试次数已经达到计划上限。");
          plans.startNode(runId, nodeId, agentId, time);
          runs.insertAgent(agentId, runId, goal, node.role(), time);
          records.insertAttempt(attemptId, nodeId.toString(), number, agentId);
          var upstream = results(runId, nodeId);
          if (upstream.size() != node.dependencyIds().size()
              || upstream.stream().anyMatch(input -> input.result().isBlank()))
            throw new IllegalStateException("计划依赖缺少可用于交接的已完成结果。");
          requireCurrentCheckEvidence(runId, upstream);
          for (var input : upstream) {
            if (input.kind() == PlanNodeKind.SYSTEM_CHECK) {
              // 检查结果没有 Agent 尝试，使用独立接收收据；正文仍以检查记录为权威。
              records.insertCheckReceipt(
                  attemptId, input.dependencyNodeId().toString(), input.checkId().toString(), time);

              continue;
            }
            records.insertAgentReceipt(
                attemptId, input.dependencyNodeId().toString(), input.attemptId().toString(), time);
            for (var evidence : input.toolEvidence())
              records.insertToolEvidence(attemptId, input.dependencyNodeId().toString(), evidence);
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
              runId, EventType.AGENT_STARTED, node.label() + "已启动 Agent，实例：" + agentId, startedRef);
          return new ClaimedNode(node, agentId, List.copyOf(upstream));
        });
  }

  public record ClaimedNode(PlanNode node, String agentId, List<WorkflowInput> dependencies) {}

  public record ClaimedCheck(
      PlanNode node, String checkId, int attemptNo, List<SystemCheckInput> inputs) {}

  /**
   * 认领系统检查节点：节点、检查记录和输入引用在同一事务提交。
   *
   * <p>不创建 Agent 实例或尝试，因此模型和工具调用为零；依赖结果仍从原权威表读取，检查冻结本次判定所见的来源版本。
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
          int number = records.nextCheckNumber(nodeId.toString());
          if (number > plans.workflowPlan(runId).maxAttempts())
            throw new IllegalStateException("节点尝试次数已经达到计划上限。");
          var upstream = results(runId, nodeId);
          if (upstream.size() != node.dependencyIds().size()
              || upstream.stream()
                  .anyMatch(input -> input.result() == null || input.result().isBlank()))
            throw new IllegalStateException("计划依赖缺少可用于交接的已完成结果。");
          requireCurrentCheckEvidence(runId, upstream);
          String time = dev.workbench.domain.WorkbenchTime.now();
          plans.startCheckNode(runId, nodeId, time);
          String checkId = dev.workbench.domain.WorkbenchIds.newId();
          records.insertCheck(
              checkId,
              nodeId.toString(),
              number,
              node.checkRef(),
              inputsFingerprint(node, upstream),
              time);
          for (var input : upstream) {
            records.insertCheckInput(
                checkId,
                input.dependencyNodeId().toString(),
                input.kind(),
                text(input.attemptId()),
                text(input.checkId()),
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
                  records
                      .findCheckSettlement(runId, checkId)
                      .orElseThrow(() -> new IllegalStateException("找不到该检查记录。"));
              var nodeId = UUID.fromString(saved.nodeId());
              if (saved.status() != SystemCheckStatus.RUNNING)
                throw new IllegalStateException("检查记录已经结算。");

              if (nodes.stream()
                  .noneMatch(
                      node -> node.id().equals(nodeId) && node.status() == PlanNodeStatus.RUNNING))
                throw new IllegalStateException("当前运行没有在途的系统检查节点。");
              String time = dev.workbench.domain.WorkbenchTime.now();
              if (!records.finishCheck(checkId, result, time))
                throw new IllegalStateException("检查记录已经结算。");

              var checked =
                  nodes.stream()
                      .filter(node -> node.id().equals(nodeId))
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
                plans.finishCheckNode(runId, nodeId, PlanNodeStatus.FAILED, time);
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
                if (dev.workbench.domain.BlockReason.REPRODUCTION_MISSING_INFO.equals(result.reasonCode()))
                  openSupplementRequest(runId, checked, result.reasonCode());
                return true;
              }
              plans.finishCheckNode(runId, nodeId, PlanNodeStatus.COMPLETED, time);
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

  /**
   * 复现缺少可执行测试时保存待补充事项，供流程图展示问题并接收用户提交。
   *
   * <p>同一运行已有未处理请求时不重复创建；提问使用稳定文案，检测细节仍以检查记录为权威。
   */
  private void openSupplementRequest(String runId, PlanNode node, String reasonCode) {
    if (!supplements.findOpen(runId).isEmpty()) return;
    String taskId = runs.runSnapshot(runId).taskId().toString();
    supplements.insert(
        dev.workbench.domain.WorkbenchIds.newId(),
        runId,
        node.id().toString(),
        reasonCode,
        "缺少可执行测试或验证条件。请补充一个 tests/ 下的 test_*.py 文件内容；提交通过校验后将从安全边界继续并重新执行复现检查。",
        runs.taskVersion(taskId),
        dev.workbench.domain.WorkbenchTime.now());

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
              var attempt = records.findAttemptIdentity(agentId);
              if (attempt.number() >= plans.workflowPlan(runId).maxAttempts()
                  || runs.countAgentToolCalls(agentId) != 0
                  || !records.canRetryRateLimit(
                      runId, agentId, ModelRateLimitException.SAFE_MESSAGE)) return false;

              runs.finishAgent(agentId, InvocationStatus.FAILED, null);
              recordAttemptStop(agentId, StopCause.MODEL_RATE_LIMIT);
              plans.requeueFailedNode(runId, node.id(), agentId);
              String attemptId = attempt.id();

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
   * <p>只复核直接依赖中的检查证据。Bug 复现检查在写节点前仍应保持一致；回归检查在审查前必须对应当前代码。证据缺失、归属不符或工作区变化时拒绝启动下游节点，并保留原检查记录。
   */
  private void requireCurrentCheckEvidence(String runId, List<WorkflowInput> upstream) {
    if (upstream.stream().noneMatch(input -> input.kind() == PlanNodeKind.SYSTEM_CHECK)) return;
    var task = runs.taskDefinition(runs.runSnapshot(runId).taskId().toString());
    for (var input : upstream) {
      if (input.kind() != PlanNodeKind.SYSTEM_CHECK) continue;
      if (input.checkId() == null) continue;
      String external = records.findExternalReference(input.checkId().toString());
      if (external == null || external.isBlank()) continue;
      var evidence = records.findExternalEvidence(external);

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

  private List<PlanNode> lockWorkflowRun(String runId, UUID version) {
    if (!runRows.lockForUpdate(runId)) throw new IllegalArgumentException("运行不存在。");
    var run = runs.runSnapshot(runId);
    var plan = plans.workflowPlan(runId);
    if (run.status() != RunStatus.RUNNING
        || plan == null
        || !plan.id().equals(version)
        || plan.approvedAt() == null) throw new IllegalStateException("运行未执行，或有效计划尚未确认。");
    var nodes = plans.checkedWorkflowNodes(runId, plan);
    plan.requireTaskAuthorization(runs.taskDefinition(run.taskId().toString()));
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
              var running =
                  nodes.stream()
                      .filter(node -> UUID.fromString(agentId).equals(node.agentId()))
                      .findFirst()
                      .orElseThrow();
              // P07：开发节点没有写出全部必需文件时不能把节点算作完成，也不能放行依赖它的审查。
              var missing = missingRequiredFiles(runId, running.id(), agentId);
              if (!missing.isEmpty()) {
                runs.finishAgent(agentId, InvocationStatus.FAILED, result);
                recordAttemptStop(agentId, StopCause.OUTPUT_MISSING);
                String reason =
                    running.label()
                        + " 缺少必需产出："
                        + String.join("、", missing)
                        + "；已保存的变更和证据保留，等待人工核对。";
                runs.update(
                    runId,
                    RunStatus.BLOCKED,
                    reason,
                    result,
                    rounds,
                    tools,
                    elapsed,
                    StopCause.OUTPUT_MISSING);
                runs.event(runId, EventType.BLOCKED, reason);
                return true;
              }
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

  /**
   * 对比节点规格的必需文件与该实例成功写入回执的路径。
   *
   * <p>只认已完成的 WORKSPACE_WRITE 调用；缺失时节点失败、运行阻塞，已写入的文件和回执保留。
   */
  private List<String> missingRequiredFiles(String runId, UUID nodeId, String agentId) {
    var spec = plans.specsByNode(runId).get(nodeId);
    if (spec == null || spec.requiredFiles().isEmpty()) return List.of();
    var produced = new java.util.HashSet<String>();
    for (var tool : runs.tools(runId)) {
      if (!agentId.equals(String.valueOf(tool.get("AGENT_ID")))
          || !"COMPLETED".equals(String.valueOf(tool.get("STATUS")))
          || !ToolKind.WORKSPACE_WRITE.recordName().equals(String.valueOf(tool.get("TOOL_NAME")))
          || tool.get("RESOURCE_VERSION") == null) continue;
      produced.addAll(
          ResourceVersion.decode(String.valueOf(tool.get("RESOURCE_VERSION"))).keySet());
    }
    return spec.requiredFiles().stream().filter(path -> !produced.contains(path)).toList();
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
    String recipientId = text(recipient);
    var inputs = new java.util.ArrayList<SequencedInput>();
    for (var row : records.findAgentResults(runId, recipientId)) {
      var tools =
          row.workspaceRead()
              ? records.findToolEvidence(row.sourceRun(), row.agentId(), row.workspaceWrite())
              : List.<ToolEvidence>of();
      inputs.add(
          new SequencedInput(
              row.sequence(),
              new WorkflowInput(
                  PlanNodeKind.AGENT,
                  row.key(),
                  UUID.fromString(row.sourceNode()),
                  UUID.fromString(row.dependencyId()),
                  UUID.fromString(row.sourceRun()),
                  row.label(),
                  UUID.fromString(row.attemptId()),
                  UUID.fromString(row.agentId()),
                  null,
                  null,
                  row.result(),
                  null,
                  tools)));
    }
    for (var row : records.findCheckResults(runId, recipientId)) {
      var dependencyId = UUID.fromString(row.dependencyId());
      inputs.add(
          new SequencedInput(
              row.sequence(),
              new WorkflowInput(
                  PlanNodeKind.SYSTEM_CHECK,
                  row.key(),
                  dependencyId,
                  dependencyId,
                  UUID.fromString(row.runId()),
                  row.label(),
                  null,
                  null,
                  UUID.fromString(row.checkId()),
                  row.status(),
                  row.summary(),
                  row.evidence(),
                  List.of())));
    }
    inputs.sort(java.util.Comparator.comparingInt(SequencedInput::sequence));
    return inputs.stream().map(SequencedInput::input).toList();
  }

  private record SequencedInput(int sequence, WorkflowInput input) {}

  private static String text(UUID value) {
    return value == null ? null : value.toString();
  }

  @Transactional
  public void approveWorkflow(String runId, UUID version) {
    if (!runRows.lockForUpdate(runId)) throw new IllegalArgumentException("运行不存在。");
    var plan = plans.workflowPlan(runId);
    if (runs.runSnapshot(runId).status() != RunStatus.PENDING
        || plan == null
        || !plan.id().equals(version)
        || !plan.reviewRequired()
        || plan.approvedAt() != null) throw new IllegalStateException("当前运行没有待确认的该计划版本。");
    plans.checkedWorkflowNodes(runId, plan);
    plan.requireTaskAuthorization(runs.taskDefinition(runs.runSnapshot(runId).taskId().toString()));
    int changed = planRows.approveIfPending(version.toString(), runId, dev.workbench.domain.WorkbenchTime.now());
    if (changed != 1) throw new IllegalStateException("当前运行没有待确认的该计划版本。");
    runs.event(
        runId, EventType.PLAN_APPROVED, "用户确认了工作流计划 " + version + "。", EventRef.plan(version));
  }

  public List<WorkflowAttempt> workflowAttempts(String runId) {
    return records.findAttempts(runId).stream()
        .map(
            row ->
                new WorkflowAttempt(
                    UUID.fromString(row.id()),
                    UUID.fromString(row.nodeId()),
                    row.number(),
                    UUID.fromString(row.agentId()),
                    row.status(),
                    Instant.parse(row.createdAt()),
                    Instant.parse(row.updatedAt()),
                    row.cause()))
        .toList();
  }

  /** 按计划顺序返回系统检查尝试。 */
  public List<WorkflowCheck> workflowChecks(String runId) {
    var inputsByCheck = new java.util.LinkedHashMap<UUID, List<WorkflowCheck.Input>>();
    for (var row : records.findCheckInputs(runId)) {
      var kind = PlanNodeKind.valueOf(row.kind());
      String body = kind == PlanNodeKind.SYSTEM_CHECK ? row.checkSummary() : row.agentResult();
      boolean sourceMatches =
          body != null && AgentAssemblySnapshot.sha256(body).equals(row.sha256());
      inputsByCheck
          .computeIfAbsent(UUID.fromString(row.checkId()), key -> new java.util.ArrayList<>())
          .add(
              new WorkflowCheck.Input(
                  UUID.fromString(row.dependencyId()),
                  kind,
                  nullableUuid(row.sourceAttempt()),
                  nullableUuid(row.sourceCheck()),
                  UUID.fromString(row.sourceRun()),
                  row.sha256(),
                  sourceMatches ? body : null));
    }
    var task = runs.taskDefinition(runs.runSnapshot(runId).taskId().toString());
    String fingerprint = currentWorkspaceFingerprint(task.workspaceId());
    return records.findChecks(runId).stream()
        .map(
            row -> {
              UUID id = UUID.fromString(row.id());
              return new WorkflowCheck(
                  id,
                  UUID.fromString(row.nodeId()),
                  row.key(),
                  row.attemptNo(),
                  new SystemCheckRef(row.referenceId(), row.referenceVersion()),
                  row.status(),
                  row.summary(),
                  row.evidence(),
                  row.externalId(),
                  row.externalId() == null
                      ? null
                      : row.workspaceFingerprint() != null
                          && row.workspaceFingerprint().equals(fingerprint),
                  row.inputsSha256(),
                  Instant.parse(row.startedAt()),
                  row.finishedAt() == null ? null : Instant.parse(row.finishedAt()),
                  inputsByCheck.getOrDefault(id, List.of()));
            })
        .toList();
  }

  public List<CheckReceipt> workflowCheckReceipts(
      String runId, UUID nodeId) {
    return records.findCheckReceipts(runId, nodeId.toString());
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
    return records.findHandoffs(runId).stream()
        .map(
            row ->
                new WorkflowHandoff(
                    row.schemaVersion(),
                    UUID.fromString(row.senderAttempt()),
                    UUID.fromString(row.senderNode()),
                    UUID.fromString(row.senderAgent()),
                    UUID.fromString(row.dependencyId()),
                    UUID.fromString(row.receiverAttempt()),
                    UUID.fromString(row.receiverNode()),
                    UUID.fromString(row.receiverAgent()),
                    Instant.parse(row.acceptedAt()),
                    row.sourceRunId() == null ? null : UUID.fromString(row.sourceRunId()),
                    row.sourceResult(),
                    records.findAcceptedToolEvidence(row.receiverAttempt(), row.dependencyId())))
        .toList();
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
            if (!records.isRunningAttempt(runId, agentId.toString()))
              throw new IllegalStateException("工作流没有可结算的该尝试。");

            runs.finishAgent(
                agentId.toString(), InvocationStatus.fromRunStatus(cause.status()), null);
            recordAttemptStop(agentId.toString(), cause);
          }
          // 停止发生在检查结算前时，保留检查记录并标明结果未知，不能当作通过。
          records.interruptChecks(runId, dev.workbench.domain.WorkbenchTime.now());

          runs.settleStoppedRun(runId, cause, reason, rounds, tools, elapsed);
        });
  }

  private void recordAttemptStop(String agentId, StopCause cause) {
    if (!records.recordStopCause(agentId, cause)) throw new IllegalStateException("无法登记节点尝试停止原因。");
  }
}
