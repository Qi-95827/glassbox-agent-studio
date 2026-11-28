package dev.workbench.task;

import dev.workbench.domain.EventRef;
import dev.workbench.domain.EventType;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.persistence.repository.WorkflowExecutionRepository;
import dev.workbench.planning.PlanService;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 节点认领：在写集互斥前提下把就绪节点或系统检查节点转为运行中，并落地尝试与输入收据。
 *
 * <p>认领仍使用数据库条件更新，重复派发会被拒绝；系统检查不创建 Agent 实例或尝试，因此模型与工具调用为零。
 */
final class WorkflowNodeClaimer {
  private final RunStateStore runs;
  private final TaskRecordService tasks;
  private final AgentAssemblyRecordService agents;
  private final RunEvidenceRecordService evidence;
  private final WorkflowExecutionRepository records;
  private final TransactionTemplate tx;
  private final PlanService plans;
  private final WorkflowRunGuard guard;
  private final WorkflowInputProvider inputs;

  WorkflowNodeClaimer(
      RunStateStore runs,
      TaskRecordService tasks,
      AgentAssemblyRecordService agents,
      RunEvidenceRecordService evidence,
      WorkflowExecutionRepository records,
      TransactionTemplate tx,
      PlanService plans,
      WorkflowRunGuard guard,
      WorkflowInputProvider inputs) {
    this.runs = runs;
    this.tasks = tasks;
    this.agents = agents;
    this.evidence = evidence;
    this.records = records;
    this.tx = tx;
    this.plans = plans;
    this.guard = guard;
    this.inputs = inputs;
  }

  /** 固定图上的认领、尝试、实例和多来源交接一起提交。 */
  WorkflowExecutionService.ClaimedNode claim(
      String runId, UUID version, UUID nodeId, String goal) {
    return claim(runId, version, nodeId, goal, 3);
  }

  /**
   * 在写集互斥前提下认领一个就绪节点。
   *
   * <p>同一运行允许最多 {@code maxParallelNodes} 个在途节点；候选计划使用节点规格的写入集合判断互斥，
   * 历史模板没有规格时对两个写节点保守地视为冲突。认领仍使用数据库条件更新，重复派发会被拒绝。
   */
  WorkflowExecutionService.ClaimedNode claim(
      String runId, UUID version, UUID nodeId, String goal, int maxParallelNodes) {
    WorkflowRunGuard.CheckEvidencePreflight preflight = guard.prepareCheckEvidence(runId, nodeId);
    return tx.execute(
        ignored -> {
          var nodes = guard.lock(runId, version);
          long running =
              nodes.stream()
                  .filter(
                      node ->
                          node.status() == PlanNodeStatus.RUNNING
                              || node.status() == PlanNodeStatus.WAITING_TOOL_APPROVAL)
                  .count();
          if (running >= Math.max(1, maxParallelNodes))
            throw new IllegalStateException("并行节点数量已经达到上限。");
          var node =
              plans.readyNodes(nodes).stream()
                  .filter(candidate -> candidate.id().equals(nodeId))
                  .findFirst()
                  .orElseThrow(() -> new IllegalStateException("节点不再是计划中的就绪步骤。"));
          if (node.systemCheck()) throw new IllegalStateException("系统检查节点必须由检查执行器认领。");
          requireDisjointWriteSet(runId, nodes, node);
          String time = dev.workbench.domain.WorkbenchTime.now();
          String agentId = dev.workbench.domain.WorkbenchIds.newId();
          String attemptId = dev.workbench.domain.WorkbenchIds.newId();
          int number = records.nextAttemptNumber(nodeId.toString());
          var plan = plans.workflowPlan(runId);
          if (number > plan.maxAttempts() && !plans.allowsReflectionAttempt(runId, nodeId, number))
            throw new IllegalStateException("节点尝试次数已经达到计划上限。");
          int revisionIndex = plans.revisionIndexFor(runId, nodeId);
          plans.startNode(runId, nodeId, agentId, time);
          agents.insertAgent(agentId, runId, goal, node.role(), time);
          records.insertAttempt(attemptId, nodeId.toString(), number, agentId, revisionIndex);
          var upstream = inputs.results(runId, nodeId);
          if (upstream.size() != node.dependencyIds().size()
              || upstream.stream().anyMatch(input -> input.result().isBlank()))
            throw new IllegalStateException("计划依赖缺少可用于交接的已完成结果。");
          guard.verifyCheckEvidence(preflight, upstream);
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
            evidence.event(
                runId,
                EventType.HANDOFF_ACCEPTED,
                node.label() + "已接收 " + upstream.size() + " 条前置结果。",
                startedRef);
          evidence.event(
              runId, EventType.AGENT_STARTED, node.label() + "已启动 Agent，实例：" + agentId, startedRef);
          return new WorkflowExecutionService.ClaimedNode(
              node,
              agentId,
              List.copyOf(upstream),
              preflight == null ? null : preflight.guard(),
              number,
              revisionIndex);
        });
  }

  /**
   * 认领系统检查节点：节点、检查记录和输入引用在同一事务提交。
   *
   * <p>不创建 Agent 实例或尝试，因此模型和工具调用为零；依赖结果仍从原权威表读取，检查冻结本次判定所见的来源版本。
   */
  WorkflowExecutionService.ClaimedCheck claimCheck(String runId, UUID version, UUID nodeId) {
    WorkflowRunGuard.CheckEvidencePreflight preflight = guard.prepareCheckEvidence(runId, nodeId);
    return tx.execute(
        ignored -> {
          var nodes = guard.lock(runId, version);
          if (nodes.stream()
              .anyMatch(
                  node ->
                      node.status() == PlanNodeStatus.RUNNING
                          || node.status() == PlanNodeStatus.WAITING_TOOL_APPROVAL))
            throw new IllegalStateException("运行已有在途步骤。");
          var node = plans.nextReady(nodes);
          if (node == null || !nodeId.equals(node.id()))
            throw new IllegalStateException("节点不再是计划中的下一个就绪步骤。");
          if (!node.systemCheck()) throw new IllegalStateException("该节点不是系统检查节点。");
          int number = records.nextCheckNumber(nodeId.toString());
          if (number > plans.workflowPlan(runId).maxAttempts())
            throw new IllegalStateException("节点尝试次数已经达到计划上限。");
          var upstream = inputs.results(runId, nodeId);
          if (upstream.size() != node.dependencyIds().size()
              || upstream.stream()
                  .anyMatch(input -> input.result() == null || input.result().isBlank()))
            throw new IllegalStateException("计划依赖缺少可用于交接的已完成结果。");
          guard.verifyCheckEvidence(preflight, upstream);
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
                WorkflowInputProvider.text(input.attemptId()),
                WorkflowInputProvider.text(input.checkId()),
                input.sourceRunId().toString(),
                AgentAssemblySnapshot.sha256(input.result()));
          }

          evidence.event(
              runId,
              EventType.CHECK_STARTED,
              node.label() + "开始执行系统检查 " + node.checkRef() + "。",
              EventRef.empty()
                  .withPlan(node.planVersionId())
                  .withNode(node.id())
                  .withCheck(UUID.fromString(checkId)));
          return new WorkflowExecutionService.ClaimedCheck(
              node,
              checkId,
              number,
              upstream.stream().map(WorkflowExecutionService.WorkflowInput::toCheckInput).toList(),
              preflight == null ? null : preflight.guard());
        });
  }

  /** 写集互斥：与任一在途节点共享写入路径时拒绝认领，读节点不受写集限制。 */
  private void requireDisjointWriteSet(String runId, List<PlanNode> nodes, PlanNode candidate) {
    var specs = plans.specsByNode(runId);
    var task = tasks.taskDefinition(runs.runSnapshot(runId).taskId().toString());
    var candidateWrites = writeSet(candidate, specs, task);
    if (candidateWrites.isEmpty()) return;
    for (var running : nodes) {
      if (running.status() != PlanNodeStatus.RUNNING
          && running.status() != PlanNodeStatus.WAITING_TOOL_APPROVAL) continue;
      var writes = writeSet(running, specs, task);
      if (writes.stream().anyMatch(candidateWrites::contains))
        throw new IllegalStateException(
            "并行节点写入范围冲突：" + candidate.label() + " 与 " + running.label() + "。");
    }
  }

  private static java.util.Set<String> writeSet(
      PlanNode node,
      java.util.Map<UUID, dev.workbench.framework.workflow.plan.PlanNodeSpec> specs,
      dev.workbench.domain.TaskDefinition task) {
    if (!node.workspaceWrite()) return java.util.Set.of();
    var spec = specs.get(node.id());
    if (spec != null) return java.util.Set.copyOf(spec.writeFiles());
    return task.fileScopes() == null ? java.util.Set.of() : java.util.Set.copyOf(task.fileScopes());
  }

  /** 输入摘要只包含来源标识与结果摘要，用于发现检查后来源被改写。 */
  private static String inputsFingerprint(
      PlanNode node, List<WorkflowExecutionService.WorkflowInput> inputs) {
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
}
