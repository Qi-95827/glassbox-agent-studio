package dev.workbench.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.EventType;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.agent.execution.AgentExecutor;
import dev.workbench.framework.context.AgentInput;
import dev.workbench.framework.control.RunControl;
import dev.workbench.framework.control.StopExecution;
import dev.workbench.framework.model.ModelRateLimitException;
import dev.workbench.framework.tool.UncertainToolEffectException;
import dev.workbench.framework.workflow.check.StaleEvidenceException;
import dev.workbench.framework.workflow.check.SystemCheckRegistry;
import dev.workbench.framework.workflow.check.SystemCheckRequest;
import dev.workbench.framework.workflow.check.SystemCheckResult;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.planning.PlanService;
import dev.workbench.planning.SourceArtifactService;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.task.WorkflowExecutionService;
import dev.workbench.task.WorkflowRecoveryService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 按持久化依赖顺序执行受控计划；整个运行共享一个控制器。 */
@Component
class WorkflowScheduler {
  private final RunEvidenceRecordService evidence;
  private final AgentAssemblyRecordService agents;
  private final RunStateStore runs;
  private final PlanService plans;
  private final WorkflowExecutionService workflowRuns;
  private final WorkflowRecoveryService recovery;
  private final AgentExecutor agent;
  private final ObjectMapper json;
  private final Map<String, WorkflowTemplate> templates;
  private final SystemCheckRegistry checks;
  private final dev.workbench.task.CostService costs;
  private final SourceArtifactService sourceArtifacts;
  private final WorkspaceAuthorizationService workspaceAuthorization;
  private final dev.workbench.ownership.OwnershipService ownership;
  private final dev.workbench.privateagent.PrivateAgentCatalog privateAgents;
  private final dev.workbench.store.GlassboxTraceStore traces;
  private final ExecutorService nodeExecutor;
  private final int maxParallelNodes;
  private final boolean parallelAllPlans;
  private final ReflectionCoordinator reflection;
  private final WorkflowMemoryContext memoryContext;
  private final dev.workbench.store.RunIterationRecordService iterations;
  private final dev.workbench.tool.ToolApprovalService approvals;
  private final org.springframework.context.ApplicationEventPublisher publisher;

  /** 每个运行已放行的断点节点；重入调度后仍有效，运行终结时清理。 */
  private final Map<String, Set<String>> releasedBreakpoints =
      new java.util.concurrent.ConcurrentHashMap<>();

  WorkflowScheduler(
      RunEvidenceRecordService evidence,
      AgentAssemblyRecordService agents,
      RunStateStore runs,
      PlanService plans,
      WorkflowExecutionService workflowRuns,
      WorkflowRecoveryService recovery,
      AgentExecutor agent,
      ObjectMapper json,
      List<WorkflowTemplate> templates,
      SystemCheckRegistry checks,
      dev.workbench.task.CostService costs,
      SourceArtifactService sourceArtifacts,
      WorkspaceAuthorizationService workspaceAuthorization,
      dev.workbench.ownership.OwnershipService ownership,
      dev.workbench.privateagent.PrivateAgentCatalog privateAgents,
      dev.workbench.store.GlassboxTraceStore traces,
      @Qualifier("workflowNodeExecutor") ExecutorService nodeExecutor,
      @Value("${workbench.orchestration.max-parallel-nodes:3}") int maxParallelNodes,
      @Value("${workbench.orchestration.parallel-all-plans:false}") boolean parallelAllPlans,
      ReflectionCoordinator reflection,
      WorkflowMemoryContext memoryContext,
      dev.workbench.store.RunIterationRecordService iterations,
      dev.workbench.tool.ToolApprovalService approvals,
      org.springframework.context.ApplicationEventPublisher publisher) {
    this.evidence = evidence;
    this.agents = agents;
    this.runs = runs;
    this.plans = plans;
    this.workflowRuns = workflowRuns;
    this.recovery = recovery;
    this.agent = agent;
    this.json = json;
    this.templates =
        templates.stream()
            .collect(Collectors.toUnmodifiableMap(WorkflowTemplate::id, Function.identity()));
    this.checks = checks;
    this.costs = costs;
    this.sourceArtifacts = sourceArtifacts;
    this.workspaceAuthorization = workspaceAuthorization;
    this.ownership = ownership;
    this.privateAgents = privateAgents;
    this.traces = traces;
    this.nodeExecutor = java.util.Objects.requireNonNull(nodeExecutor, "节点执行器不能为空");
    this.maxParallelNodes = Math.max(1, maxParallelNodes);
    this.parallelAllPlans = parallelAllPlans;
    this.reflection = java.util.Objects.requireNonNull(reflection, "反思协调器不能为空");
    this.memoryContext = java.util.Objects.requireNonNull(memoryContext, "项目记忆上下文组件不能为空");
    this.iterations = iterations;
    this.approvals = approvals;
    this.publisher = publisher;
  }

  WorkflowTemplate template(String id) {
    var template = templates.get(id);
    if (template == null) throw new IllegalArgumentException("不支持该工作流模板。");
    return template;
  }

  /** 清理指定运行已放行的断点缓存，供断点取消终结时调用。 */
  void clearReleasedBreakpoints(String runId) {
    if (runId != null) releasedBreakpoints.remove(runId);
  }

  WorkflowPlan plan(String runId) {
    return plans.workflowPlan(runId);
  }

  void approvePlan(String runId, UUID planVersionId) {
    workflowRuns.approveWorkflow(runId, planVersionId);
  }

  void recover(String runId, UUID checkpointId, UUID nextRunId) {
    recovery.recover(runId, checkpointId, nextRunId);
  }

  /** 认领与提交使用同一进程的控制边界，模型执行期间释放运行控制锁。 */
  void execute(
      String runId, WorkflowPlan plan, TaskDefinition task, RunControl control, Object stateLock) {
    execute(runId, plan, task, control, stateLock, false);
  }

  /** 支持断点恢复重入执行：{@code resuming=true} 时跳过重复启动事件与首节点检查点落库。 */
  void execute(
      String runId,
      WorkflowPlan plan,
      TaskDefinition task,
      RunControl control,
      Object stateLock,
      boolean resuming) {
    var activeAgents = java.util.concurrent.ConcurrentHashMap.<UUID>newKeySet();
    try {
      plan.requireTaskAuthorization(task);
      // 执行前复核私有 Agent 归属、版本和归档状态；模型建议不能替代这一步。
      String ownerScope =
          ownership
              .owner(dev.workbench.ownership.OwnershipService.TASK, task.id().toString())
              .orElse(null);
      for (var node : plans.find(runId))
        if (node.definitionRef() != null
            && dev.workbench.privateagent.PrivateAgentCatalog.isPrivate(node.definitionRef()))
          privateAgents.requireRunUse(ownerScope, node.definitionRef());
      workspaceAuthorization.requireCurrent(task.id().toString());
      sourceArtifacts.requireCurrent(task.id().toString());
      if (!resuming) {
        evidence.event(runId, EventType.RUN_STARTED, "已保存的工作流开始执行。");
        control.atBoundary(
            stateLock,
            () -> {
              workflowRuns.checkpointBeforeFirstNode(runId, plan.id(), control.elapsed());
              return null;
            });
      }
      executeDag(runId, plan, task, control, stateLock, activeAgents);
    } catch (dev.workbench.framework.control.PauseYield pause) {
      // 暂停发生在未认领节点的安全边界：运行保持 PAUSED，工作线程立即归还执行器，等待 /resume 重新派发。
      return;
    } catch (StopExecution e) {
      stop(runId, activeAgents, control, e.stopCause(), e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      stop(runId, activeAgents, control, StopCause.INTERRUPTED, "进程中断，未结束的模型结果未知。");
    } catch (ModelRateLimitException e) {
      stop(runId, activeAgents, control, StopCause.MODEL_RATE_LIMIT, "模型请求被限流拒绝，当前节点无法继续自动重试。");
    } catch (UncertainToolEffectException e) {
      stop(runId, activeAgents, control, StopCause.TOOL_EFFECT_UNKNOWN, "工具写入结果未知，请先核对工作区状态。");
    } catch (StaleEvidenceException e) {
      stop(runId, activeAgents, control, StopCause.CHECK_FAILED, e.getMessage());
    } catch (IllegalArgumentException | IllegalStateException e) {
      // 装配、版本、授权或上下文约束拒绝：保留项目生成的安全原因，便于从运行详情定位。
      String detail = e.getMessage() == null ? "未提供原因。" : e.getMessage();
      if (detail.length() > 400) detail = detail.substring(0, 399) + "…";
      stop(runId, activeAgents, control, StopCause.UNCLASSIFIED_FAILURE, "工作流执行被拒绝：" + detail);
    } catch (Exception e) {
      stop(runId, activeAgents, control, StopCause.UNCLASSIFIED_FAILURE, "工作流执行失败，请查看节点与调用记录。");
    } finally {
      // 终态运行不再需要断点放行记录；暂停中的运行保留，供 /resume 重入后越过已放行断点。
      if (!runs.runSnapshot(runId).status().canCancel()) releasedBreakpoints.remove(runId);
    }
  }

  /**
   * 受控并行执行主循环：每次最多派发配置数量的就绪节点，任一节点结算后立即补充。
   *
   * <p>认领在数据库事务内再次校验依赖与写集互斥；系统检查作为屏障独占执行，避免检查期间工作区被并行写入。
   */
  private void executeDag(
      String runId,
      WorkflowPlan plan,
      TaskDefinition task,
      RunControl control,
      Object stateLock,
      Set<UUID> activeAgents)
      throws InterruptedException, com.fasterxml.jackson.core.JsonProcessingException {
    CompletionService<NodeOutcome> completion = new ExecutorCompletionService<>(nodeExecutor);
    int effectiveMax =
        parallelAllPlans || !plans.revisionEdges(runId).isEmpty() ? maxParallelNodes : 1;
    var inFlight = new java.util.LinkedHashMap<UUID, Future<NodeOutcome>>();
    // 已放行断点必须按 runId 托管在进程级：/,resume 会重新进入本方法，栈内局部集合会被重置而无限重中断点。
    var releasedBreakpoints =
        this.releasedBreakpoints.computeIfAbsent(
            runId, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet());
    boolean checkInFlight = false;
    boolean draining = false;
    boolean fanOut = false;
    Throwable failure = null;
    try {
      while (true) {
        if (failure == null && !draining) {
          var nodes = plans.checkedWorkflowNodes(runId, plan);
          if (settled(nodes)) return;
          boolean submitted = false;
          if (!checkInFlight) {
            for (var node : plans.readyNodes(nodes)) {
              if (inFlight.containsKey(node.id())) continue;
              if (node.systemCheck() && !inFlight.isEmpty()) break;
              if (!node.systemCheck() && inFlight.size() >= effectiveMax) break;
              if (!inFlight.isEmpty() && (effectiveMax <= 1 || !fanOut)) continue;
              if (!inFlight.isEmpty() && !parallelSafe(runId, node, inFlight.keySet())) continue;
              if (inFlight.isEmpty()
                  && traces != null
                  && !releasedBreakpoints.contains(node.id().toString())
                  && traces.planControl(runId).breakpoints().contains(node.id().toString())) {
                releasedBreakpoints.add(node.id().toString());
                control.pauseAtBreakpoint(
                    stateLock, "命中节点断点：" + node.label() + "；请检查上游交接与上下文后点击「继续」放行。");
              }
              try {
                if (node.systemCheck()) {
                  var claimed =
                      control.atBoundary(
                          stateLock,
                          () -> workflowRuns.claimWorkflowCheck(runId, plan.id(), node.id()));
                  workflowRuns.requireWorkspaceCurrent(claimed.workspaceGuard());
                  checkInFlight = true;
                  inFlight.put(
                      node.id(),
                      completion.submit(() -> runCheck(runId, task, claimed, control, stateLock)));
                } else {
                  var attempt =
                      control.atBoundary(
                          stateLock,
                          () ->
                              workflowRuns.claimWorkflowNode(
                                  runId, plan.id(), node.id(), task.goal(), effectiveMax));
                  activeAgents.add(UUID.fromString(attempt.agentId()));
                  workflowRuns.requireWorkspaceCurrent(attempt.workspaceGuard());
                  inFlight.put(
                      node.id(),
                      completion.submit(() -> runAgent(runId, task, attempt, control, stateLock)));
                }
                submitted = true;
                if (checkInFlight) break;
              } catch (IllegalStateException conflict) {
                // 仅跳过可重试的调度竞争；证据失效、授权或计划损坏必须带原始原因终止。
                String message = conflict.getMessage() == null ? "" : conflict.getMessage();
                if (!(message.contains("写集")
                    || message.contains("就绪步骤")
                    || message.contains("并行节点数量")
                    || message.contains("不再是计划中的"))) {
                  throw conflict;
                }
              }
            }
          }
          if (!submitted && inFlight.isEmpty()) {
            throw new IllegalStateException("工作流没有可继续执行的节点；可能存在未结算状态或不可调度的计划。");
          }
        }

        if (inFlight.isEmpty()) break;
        NodeOutcome outcome = await(completion);
        inFlight.remove(outcome.nodeId());
        if (outcome.systemCheck()) checkInFlight = false;
        if (draining) {
          if (outcome.agentId() != null) activeAgents.remove(outcome.agentId());
          continue;
        }
        if (failure != null) continue;

        if (outcome.error() != null) {
          if (outcome.error() instanceof ModelRateLimitException rejected) {
            control.finishModel(
                outcome.agentId().toString(),
                InvocationStatus.FAILED,
                null,
                null,
                rejected.getMessage());
            boolean requeued =
                control.atBoundary(
                    stateLock,
                    () ->
                        control.hasModelRetryBudget(outcome.nodePolicy())
                            && workflowRuns.requeueAfterRateLimit(
                                runId, plan.id(), outcome.agentId().toString()));
            if (!requeued) {
              failure = rejected;
              cancelAll(completion, inFlight);
            } else {
              activeAgents.remove(outcome.agentId());
              control.waitForModelRetry();
            }
            continue;
          }
          if (outcome.error() instanceof RuntimeException runtime) {
            failure = runtime;
          } else {
            failure = new IllegalStateException("节点执行失败，请查看节点与调用记录。", outcome.error());
          }
          cancelAll(completion, inFlight);
          continue;
        }

        if (outcome.agentId() != null) activeAgents.remove(outcome.agentId());
        fanOut = true;
        if (outcome.systemCheck()) {
          boolean ended =
              control.atBoundary(
                  stateLock,
                  () ->
                      workflowRuns.finishWorkflowCheck(
                          runId,
                          plan.id(),
                          outcome.checkId(),
                          outcome.checkResult(),
                          control.rounds(),
                          control.tools(),
                          control.elapsed()));
          if (ended) {
            cancelAll(completion, inFlight);
            return;
          }
          continue;
        }

        String verdictJson = null;
        var decision = reflection.decide(runId, outcome.claimed().node(), outcome.result());
        // 达到修订上限（EXHAUSTED）同样携带裁决：结算时据此转入 BLOCKED/HUMAN_REVIEW_REQUIRED，
        // 绝不因为「不再重试」而把运行错结为 COMPLETED。
        if (carriesCriticVerdict(decision))
          verdictJson = json.writeValueAsString(decision.verdict());
        final String criticVerdict = verdictJson;
        var settlement =
            control.atBoundary(
                stateLock,
                () ->
                    workflowRuns.settleWorkflowNode(
                        runId,
                        plan.id(),
                        outcome.agentId().toString(),
                        outcome.result(),
                        control.rounds(),
                        control.tools(),
                        control.elapsed(),
                        criticVerdict));
        if (settlement.runEnded()) {
          if (!inFlight.isEmpty()) {
            // 运行已终止（完成/阻塞/取消）：立即取消在途并行节点，绝不留下 RUNNING 孤儿等待自然结束。
            draining = true;
            cancelAll(completion, inFlight);
            inFlight.clear();
          }
          return;
        }
      }
    } finally {
      cancelAll(completion, inFlight);
    }
    if (failure != null) {
      if (failure instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException("节点执行失败。", failure);
    }
  }

  /**
   * 真并行只对“写集非空且互不重叠”的就绪节点开放。
   *
   * <p>只读/纯文本节点按顺序执行，避免同时消耗共享预算或让两个读取分支争抢同一观察窗口； 独立写分支仍可并发，且数据库认领会再次验证写集互斥。
   */
  private boolean parallelSafe(
      String runId, dev.workbench.framework.workflow.plan.PlanNode candidate, Set<UUID> inFlight) {
    var specs = plans.specsByNode(runId);
    var candidateSpec = specs.get(candidate.id());
    if (candidateSpec == null || candidateSpec.writeFiles().isEmpty()) return false;
    var writes = new java.util.HashSet<>(candidateSpec.writeFiles());
    for (UUID nodeId : inFlight) {
      var spec = specs.get(nodeId);
      if (spec == null || spec.writeFiles().isEmpty()) return false;
      if (spec.writeFiles().stream().anyMatch(writes::contains)) return false;
    }
    return true;
  }

  private static boolean settled(List<dev.workbench.framework.workflow.plan.PlanNode> nodes) {
    return nodes.stream()
        .allMatch(
            node ->
                node.status() == dev.workbench.framework.workflow.plan.PlanNodeStatus.COMPLETED
                    || node.status()
                        == dev.workbench.framework.workflow.plan.PlanNodeStatus.REUSED);
  }

  private static NodeOutcome await(CompletionService<NodeOutcome> completion)
      throws InterruptedException {
    try {
      return completion.take().get();
    } catch (java.util.concurrent.ExecutionException e) {
      var cause = e.getCause();
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException("节点执行失败。", cause);
    }
  }

  /** 取消在途任务并等待其结束，保证停止结算不会与迟到节点写入交错。 */
  private static void cancelAll(
      CompletionService<NodeOutcome> completion,
      java.util.Map<UUID, Future<NodeOutcome>> inFlight) {
    for (var future : inFlight.values()) future.cancel(true);
    for (int index = 0; index < inFlight.size(); index++) {
      try {
        completion.take().get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (java.util.concurrent.ExecutionException
          | java.util.concurrent.CancellationException ignored) {
        // 已取消或失败的节点不再影响已终止运行。
      }
    }
    inFlight.clear();
  }

  private NodeOutcome runAgent(
      String runId,
      TaskDefinition task,
      WorkflowExecutionService.ClaimedNode attempt,
      RunControl control,
      Object stateLock) {
    try {
      String result = executeAttempt(runId, task, attempt, control, stateLock);
      return NodeOutcome.agent(attempt, result, null);
    } catch (Throwable error) {
      return NodeOutcome.agent(attempt, null, error);
    }
  }

  private NodeOutcome runCheck(
      String runId,
      TaskDefinition task,
      WorkflowExecutionService.ClaimedCheck claimed,
      RunControl control,
      Object stateLock) {
    SystemCheckResult result;
    try {
      result =
          checks
              .resolve(claimed.node().checkRef())
              .run(
                  new SystemCheckRequest(
                      UUID.fromString(runId),
                      task,
                      claimed.node().id(),
                      claimed.node().key(),
                      claimed.node().label(),
                      claimed.node().checkRef(),
                      claimed.inputs(),
                      control::check));
    } catch (StopExecution stop) {
      return NodeOutcome.check(claimed, null, stop);
    } catch (RuntimeException failure) {
      result = SystemCheckResult.blocked("系统检查执行失败，不能确认检查条件。", failure.getClass().getSimpleName());
    }
    return NodeOutcome.check(claimed, result, null);
  }

  private record NodeOutcome(
      UUID nodeId,
      UUID agentId,
      WorkflowExecutionService.ClaimedNode claimed,
      String checkId,
      SystemCheckResult checkResult,
      String result,
      Throwable error,
      RunControl.NodePolicy nodePolicy) {
    static NodeOutcome agent(
        WorkflowExecutionService.ClaimedNode claimed, String result, Throwable error) {
      var node = claimed.node();
      var policy =
          node.budget() == null ? null : new RunControl.NodePolicy(node.id(), node.budget());
      return new NodeOutcome(
          node.id(),
          UUID.fromString(claimed.agentId()),
          claimed,
          null,
          null,
          result,
          error,
          policy);
    }

    static NodeOutcome check(
        WorkflowExecutionService.ClaimedCheck claimed, SystemCheckResult result, Throwable error) {
      return new NodeOutcome(
          claimed.node().id(), null, null, claimed.checkId(), result, null, error, null);
    }

    boolean systemCheck() {
      return checkId != null;
    }
  }

  private String executeAttempt(
      String runId,
      TaskDefinition task,
      WorkflowExecutionService.ClaimedNode attempt,
      RunControl control,
      Object stateLock)
      throws InterruptedException, com.fasterxml.jackson.core.JsonProcessingException {
    var node = attempt.node();
    var nodePolicy =
        node.budget() == null ? null : new RunControl.NodePolicy(node.id(), node.budget());
    // 候选计划使用节点规格的读写子集；历史模板继续使用任务级文件范围。
    var spec = plans.requireNodeSpec(runId, node.id(), task);
    var files =
        spec != null
            ? spec.readFiles()
            : node.workspaceRead() ? task.fileScopes() : List.<String>of();
    var writeFiles =
        spec != null ? spec.writeFiles() : node.workspaceWrite() ? files : List.<String>of();
    String nodeWorkspaceId = files.isEmpty() ? null : task.workspaceId();
    String criticFeedback = reflection.criticFeedback(runId, node.id());
    // 动态工作流此前完全忽略 memoryEnabled；这里与静态运行入口对齐，按混合评分召回并落库引用。
    String memorySection = memoryContext.load(task, runId);
    // 同任务迭代：本轮调改要求与上一轮交付摘要必须进入节点提示词，否则第二轮仍会重复首轮行为。
    String iterationSection = iterationSection(runId);
    var input =
        new StepInput(
            task.goal(),
            node.label(),
            task.entryKind().guidance(),
            task.constraints(),
            task.acceptanceCriteria(),
            files,
            sourceArtifacts.constraints(task.id().toString()),
            attempt.dependencies(),
            attempt.attemptNumber(),
            attempt.revisionIndex(),
            criticFeedback,
            memorySection,
            spec == null ? List.of() : spec.acceptanceCriteria(),
            iterationSection);
    String id = attempt.agentId();
    var request =
        new AgentExecutionRequest(
                node.role(),
                id,
                stepInput(runId, task, attempt, json.writeValueAsString(input)),
                nodeWorkspaceId,
                files,
                !writeFiles.isEmpty(),
                task.scenario(),
                task.modelMode(),
                CheckpointStage.BEFORE_TOOL,
                null,
                new ControlledAgentRuntime(
                    runId,
                    id,
                    control,
                    agents,
                    runs,
                    evidence,
                    nodePolicy,
                    costs,
                    workspaceAuthorization,
                    traces,
                    task.locale(),
                    approvals,
                    publisher,
                    node.id().toString()),
                node.definitionRef(),
                node.definitionSha256(),
                writeFiles)
            .withModelConnection(task.modelConnectionId())
            .withLocale(task.locale());
    return limited(agent.execute(request), 1900);
  }

  private void stop(
      String runId, Set<UUID> agentIds, RunControl control, StopCause cause, String reason) {
    // 检查阻塞等运行状态没有对应的调用终态；此时只结算运行与节点，不伪造调用状态。
    InvocationStatus status =
        switch (cause.status()) {
          case COMPLETED, FAILED, CANCELLED, LIMITED, INTERRUPTED ->
              InvocationStatus.fromRunStatus(cause.status());
          default -> null;
        };
    if (status != null) {
      if (agentIds == null || agentIds.isEmpty()) {
        control.finishModel(status, null, null, reason);
        control.finishTool(status, null, reason);
      } else {
        for (UUID agentId : agentIds) {
          control.finishModel(agentId.toString(), status, null, null, reason);
          control.finishTool(agentId.toString(), status, null, reason, null);
        }
      }
    }
    workflowRuns.finishParallelStoppedRun(
        runId,
        agentIds == null ? List.of() : List.copyOf(agentIds),
        cause,
        reason,
        control.rounds(),
        control.tools(),
        control.elapsed());
  }

  /**
   * 反思裁决是否必须随节点结算落库。
   *
   * <p>{@code EXHAUSTED}（达到修订上限）与 {@code REVISE} 都携带裁决，{@code APPROVED} 同样需要留下证据；
   * 只有「不是审查节点」才没有裁决。此前只按 {@code revisionRequired()} 判断，导致 EXHAUSTED 的裁决被丢弃， 结算时看不到修订上限，运行被错结为
   * COMPLETED。
   */
  static boolean carriesCriticVerdict(ReflectionCoordinator.Decision decision) {
    return decision != null && decision.verdict() != null;
  }

  private static String limited(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }

  /**
   * 本轮迭代的上下文段落。
   *
   * <p>只有当前运行确实由调改迭代创建时才追加；首轮运行返回 {@code null}，提示词结构与历史完全一致。
   */
  private String iterationSection(String runId) {
    if (iterations == null) return null;
    return iterations
        .note(runId)
        .map(
            note ->
                "同任务第 "
                    + note.roundIndex()
                    + " 轮调改要求（用户在上轮交付后提出，必须逐条满足）："
                    + note.feedback()
                    + (note.targetNodeKey() == null ? "" : "\n本轮重点修订节点：" + note.targetNodeKey())
                    + (note.previousSummary() == null
                        ? ""
                        : "\n上一轮交付结论摘要（只作为背景，不能当作已通过验证）：" + note.previousSummary()))
        .orElse(null);
  }

  private static AgentInput stepInput(
      String runId,
      TaskDefinition task,
      WorkflowExecutionService.ClaimedNode attempt,
      String text) {
    var sources = new java.util.ArrayList<AgentInput.Source>();
    sources.add(new AgentInput.Source(AgentInput.Kind.TASK, task.id().toString(), null));
    sources.add(
        new AgentInput.Source(AgentInput.Kind.WORKFLOW_NODE, attempt.node().id().toString(), null));
    for (var dependency : attempt.dependencies()) {
      if (dependency.kind() == dev.workbench.framework.workflow.plan.PlanNodeKind.SYSTEM_CHECK) {
        sources.add(
            new AgentInput.Source(
                AgentInput.Kind.SYSTEM_CHECK, dependency.checkId().toString(), null));
        continue;
      }
      sources.add(
          new AgentInput.Source(
              AgentInput.Kind.WORKFLOW_ATTEMPT, dependency.attemptId().toString(), null));
      for (var evidence : dependency.toolEvidence())
        sources.add(
            new AgentInput.Source(
                AgentInput.Kind.TOOL_CALL, evidence.toolCallId().toString(), evidence.version()));
    }
    return new AgentInput(runId, text, sources);
  }

  /** 节点输入：任务契约、节点规格验收条件与本轮迭代要求分开列出，避免把未验证内容当作已通过。 */
  private record StepInput(
      String goal,
      String step,
      String entryGuidance,
      List<String> constraints,
      List<String> acceptanceCriteria,
      List<String> authorizedFiles,
      List<String> sourceArtifacts,
      List<WorkflowExecutionService.WorkflowInput> dependencies,
      int attemptNumber,
      int revisionIndex,
      String criticFeedback,
      String memoryContext,
      List<String> nodeAcceptanceCriteria,
      String iterationRequirement) {}
}
