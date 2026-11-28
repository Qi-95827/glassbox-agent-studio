package dev.workbench.task;

import dev.workbench.domain.EventRef;
import dev.workbench.domain.EventType;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.model.ModelRateLimitException;
import dev.workbench.framework.tool.ResourceVersion;
import dev.workbench.framework.workflow.check.SystemCheckResult;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.persistence.repository.SupplementRepository;
import dev.workbench.persistence.repository.WorkflowExecutionRepository;
import dev.workbench.planning.PlanService;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunCountStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 节点结算：成功、失败、有界反思、限流重排与系统检查结算都在同一事务内收口。
 *
 * <p>最后一个节点与运行终态一起提交；超过反思回边上限时阻塞运行并等待人工审核，绝不继续无界循环。
 */
final class WorkflowNodeSettler {
  private final RunStateStore runs;
  private final TaskRecordService tasks;
  private final AgentAssemblyRecordService agents;
  private final RunEvidenceRecordService evidence;
  private final RunCountStore counts;
  private final WorkflowExecutionRepository records;
  private final TransactionTemplate tx;
  private final PlanService plans;
  private final WorkflowRecoveryService recovery;
  private final SupplementRepository supplements;
  private final WorkflowRunGuard guard;
  private final WorkflowInputProvider inputs;

  WorkflowNodeSettler(
      RunStateStore runs,
      TaskRecordService tasks,
      AgentAssemblyRecordService agents,
      RunEvidenceRecordService evidence,
      RunCountStore counts,
      WorkflowExecutionRepository records,
      TransactionTemplate tx,
      PlanService plans,
      WorkflowRecoveryService recovery,
      SupplementRepository supplements,
      WorkflowRunGuard guard,
      WorkflowInputProvider inputs) {
    this.runs = runs;
    this.tasks = tasks;
    this.agents = agents;
    this.evidence = evidence;
    this.counts = counts;
    this.records = records;
    this.tx = tx;
    this.plans = plans;
    this.recovery = recovery;
    this.supplements = supplements;
    this.guard = guard;
    this.inputs = inputs;
  }

  /** 结算节点与后续安全边界；最后一个节点与运行终态一起提交。 */
  boolean finishNode(
      String runId,
      UUID version,
      String agentId,
      String result,
      int rounds,
      int tools,
      long elapsed) {
    return settleNode(runId, version, agentId, result, rounds, tools, elapsed, null).runEnded();
  }

  /**
   * 结算节点并处理有界反思。
   *
   * <p>{@code criticVerdictJson} 非空且审查判定需要修改时，在同一事务内把目标与审查节点退回待执行； 超过回边上限则阻塞运行并等待人工审核，绝不继续无界循环。返回的
   * {@code runEnded} 表示运行已经终止。
   */
  WorkflowExecutionService.NodeSettlement settleNode(
      String runId,
      UUID version,
      String agentId,
      String result,
      int rounds,
      int tools,
      long elapsed,
      String criticVerdictJson) {
    return tx.execute(
        ignored -> {
          var nodes = guard.lock(runId, version);
          if (nodes.stream()
              .noneMatch(
                  node ->
                      (node.status() == PlanNodeStatus.RUNNING
                              || node.status() == PlanNodeStatus.WAITING_TOOL_APPROVAL)
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
            agents.finishAgent(agentId, InvocationStatus.FAILED, result);
            recordAttemptStop(agentId, StopCause.OUTPUT_MISSING);
            String reason =
                running.label() + " 缺少必需产出：" + String.join("、", missing) + "；已保存的变更和证据保留，等待人工核对。";
            runs.update(
                runId,
                RunStatus.BLOCKED,
                reason,
                result,
                rounds,
                tools,
                elapsed,
                StopCause.OUTPUT_MISSING);
            evidence.event(runId, EventType.BLOCKED, reason);
            return new WorkflowExecutionService.NodeSettlement(true, false, reason);
          }
          agents.finishAgent(agentId, InvocationStatus.COMPLETED, result);
          if (criticVerdictJson != null && !criticVerdictJson.isBlank())
            records.recordCriticVerdict(agentId, criticVerdictJson);
          var verdict =
              criticVerdictJson == null || criticVerdictJson.isBlank()
                  ? null
                  : dev.workbench.framework.workflow.plan.ReviewVerdictParser.parse(result);
          if (verdict != null && verdict.reviseRequired()) {
            var edge = plans.revisionEdgeForReviewer(runId, running.id());
            if (edge != null) {
              if (edge.revisionCount() < edge.maxRevisions()) {
                plans.triggerRevision(
                    runId,
                    running.id(),
                    edge.targetNodeId(),
                    criticVerdictJson,
                    dev.workbench.domain.WorkbenchTime.now());
                String message =
                    running.label()
                        + " 要求修订第 "
                        + (edge.revisionCount() + 1)
                        + "/"
                        + edge.maxRevisions()
                        + " 轮：已重新排队目标节点并注入批评反馈。";
                evidence.event(
                    runId,
                    EventType.REVISION_TRIGGERED,
                    message,
                    evidence.eventRefForAgent(runId, agentId));
                return new WorkflowExecutionService.NodeSettlement(false, true, message);
              }
              String reason =
                  running.label() + " 已达到 " + edge.maxRevisions() + " 次修订上限，自动反思停止，等待人工审核。";
              runs.update(
                  runId,
                  RunStatus.BLOCKED,
                  reason,
                  result,
                  rounds,
                  tools,
                  elapsed,
                  StopCause.HUMAN_REVIEW_REQUIRED);
              evidence.event(
                  runId,
                  EventType.WAITING_HUMAN_REVIEW,
                  reason,
                  evidence.eventRefForAgent(runId, agentId));
              return new WorkflowExecutionService.NodeSettlement(true, false, reason);
            }
          }
          var updated = plans.find(runId);
          if (updated.stream()
              .allMatch(
                  node ->
                      node.status() == PlanNodeStatus.COMPLETED
                          || node.status() == PlanNodeStatus.REUSED)) {
            completeRun(runId, updated.size(), rounds, tools, elapsed);
            return new WorkflowExecutionService.NodeSettlement(true, false, null);
          }
          recovery.saveBoundary(runId, plans.workflowPlan(runId), updated, elapsed);
          return new WorkflowExecutionService.NodeSettlement(false, false, null);
        });
  }

  /**
   * 结算系统检查。
   *
   * @return 运行是否已经结束（完成或因检查失败阻塞）
   */
  boolean finishCheck(
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
              var nodes = guard.lock(runId, version);
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
              evidence.event(
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
                evidence.event(runId, EventType.BLOCKED, "系统检查未通过：" + result.summary(), checkRef);
                if (dev.workbench.domain.BlockReason.REPRODUCTION_MISSING_INFO.equals(
                    result.reasonCode()))
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

  /** 仅接受已结算的限流拒绝；带有工具调用历史的尝试原样结束。 */
  boolean requeueAfterRateLimit(String runId, UUID version, String agentId) {
    return Boolean.TRUE.equals(
        tx.execute(
            ignored -> {
              var node =
                  guard.lock(runId, version).stream()
                      .filter(
                          candidate ->
                              candidate.status() == PlanNodeStatus.RUNNING
                                  && UUID.fromString(agentId).equals(candidate.agentId()))
                      .findFirst()
                      .orElseThrow(() -> new IllegalStateException("找不到可结算的节点尝试。"));
              var attempt = records.findAttemptIdentity(agentId);
              if (attempt.number() >= plans.workflowPlan(runId).maxAttempts()
                  || counts.countAgentToolCalls(agentId) != 0
                  || !records.canRetryRateLimit(
                      runId, agentId, ModelRateLimitException.SAFE_MESSAGE)) return false;

              agents.finishAgent(agentId, InvocationStatus.FAILED, null);
              recordAttemptStop(agentId, StopCause.MODEL_RATE_LIMIT);
              plans.requeueFailedNode(runId, node.id(), agentId);
              String attemptId = attempt.id();

              evidence.event(
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

  /**
   * 对比节点规格的必需文件与该实例成功写入回执的路径。
   *
   * <p>只认已完成的 WORKSPACE_WRITE 调用；缺失时节点失败、运行阻塞，已写入的文件和回执保留。
   */
  private List<String> missingRequiredFiles(String runId, UUID nodeId, String agentId) {
    var spec = plans.specsByNode(runId).get(nodeId);
    if (spec == null || spec.requiredFiles().isEmpty()) return List.of();
    var produced = new java.util.HashSet<String>();
    for (var tool : agents.tools(runId)) {
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
    var outputs = inputs.results(runId, null);
    if (outputs.size() != nodeCount) throw new IllegalStateException("工作流缺少已结算的节点结果。");
    StringBuilder result = new StringBuilder();
    for (var output : outputs) {
      result.append(output.label()).append('\n');
      result.append(output.result(), 0, Math.min(550, output.result().length())).append("\n\n");
    }
    if (result.length() > 1900) result.setLength(1900);
    var scope = plans.workflowPlan(runId).scope();
    String reason = null;
    StopCause cause = null;
    if (scope == WorkflowTemplate.Scope.WORKSPACE_BUG_FIX) {
      reason = "回归检查通过，仍需用户核对代码差异、原问题与验收条件；系统不会自动合并或关闭 Bug。";
      cause = StopCause.USER_REVIEW;
    } else if (scope.allowsWrite()) {
      boolean confirmedWrite =
          outputs.stream()
              .flatMap(output -> output.toolEvidence().stream())
              .anyMatch(evidence -> evidence.tool() == ToolKind.WORKSPACE_WRITE);
      if (confirmedWrite) {
        reason = "已保存写入记录，等待核对工作区差异和验收条件。";
        cause = StopCause.USER_REVIEW;
      } else {
        reason = "尚无成功写入记录，请核对修改结果和验收条件。";
        cause = StopCause.OUTPUT_MISSING;
      }
    } else if (scope.hasWorkspace()
        && outputs.stream().allMatch(output -> output.toolEvidence().isEmpty())) {
      reason = "尚无成功的授权文件读取或搜索记录，分析结果需要核实。";
      cause = StopCause.OUTPUT_MISSING;
    }
    runs.update(
        runId,
        cause != null ? RunStatus.BLOCKED : RunStatus.COMPLETED,
        reason,
        result.toString().stripTrailing(),
        rounds,
        tools,
        elapsed,
        cause);
    boolean blocked = cause != null;
    evidence.event(
        runId,
        blocked ? EventType.BLOCKED : EventType.RUN_COMPLETED,
        blocked ? reason : "工作流已完成，已保存各步骤的模型结果。");
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
        tasks.taskVersion(taskId),
        dev.workbench.domain.WorkbenchTime.now());

    evidence.event(
        runId,
        EventType.SUPPLEMENT_REQUESTED,
        "复现检查缺少可执行测试，已保存待补充事项；补充前不会启动任何下游节点。",
        EventRef.empty().withPlan(node.planVersionId()).withNode(node.id()));
  }

  /** 登记节点尝试的停止原因；无法登记说明尝试状态已被并发改写。 */
  void recordAttemptStop(String agentId, StopCause cause) {
    if (!records.recordStopCause(agentId, cause)) throw new IllegalStateException("无法登记节点尝试停止原因。");
  }
}
