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
import dev.workbench.planning.PlanService;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.planning.SourceArtifactService;
import dev.workbench.task.WorkflowExecutionService;
import dev.workbench.task.WorkflowRecoveryService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import dev.workbench.store.HandoffValidator;
import dev.workbench.store.ReviewCompletion;
import dev.workbench.store.StopCompletion;

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
      dev.workbench.store.GlassboxTraceStore traces) {
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
  }

  WorkflowTemplate template(String id) {
    var template = templates.get(id);
    if (template == null) throw new IllegalArgumentException("不支持该工作流模板。");
    return template;
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
    UUID activeAgent = null;
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
      evidence.event(runId, EventType.RUN_STARTED, "已保存的工作流开始执行。");
      control.atBoundary(
          stateLock,
          () -> {
            workflowRuns.checkpointBeforeFirstNode(runId, plan.id(), control.elapsed());
            return null;
          });
      var releasedBreakpoints = new java.util.HashSet<String>();
      while (true) {
        var node = plans.nextReady(plans.checkedWorkflowNodes(runId, plan));
        if (node == null) throw new IllegalStateException("工作流没有可继续执行的节点。");
        String nodeIdText = node.id().toString();
        if (traces != null
            && !releasedBreakpoints.contains(nodeIdText)
            && traces.planControl(runId).breakpoints().contains(nodeIdText)) {
          releasedBreakpoints.add(nodeIdText);
          control.pauseAtBreakpoint(
              stateLock, "命中节点断点：" + node.label() + "；请检查上游交接与上下文后点击「继续」放行。");
        }
        if (node.systemCheck()) {
          // 检查节点不创建 Agent 实例，也不消耗模型或工具预算。
          var claimed =
              control.atBoundary(
                  stateLock, () -> workflowRuns.claimWorkflowCheck(runId, plan.id(), node.id()));
          workflowRuns.requireWorkspaceCurrent(claimed.workspaceGuard());
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
            throw stop;
          } catch (RuntimeException failure) {
            result =
                SystemCheckResult.blocked("系统检查执行失败，不能确认检查条件。", failure.getClass().getSimpleName());
          }
          SystemCheckResult settled = result;
          boolean ended =
              control.atBoundary(
                  stateLock,
                  () ->
                      workflowRuns.finishWorkflowCheck(
                          runId,
                          plan.id(),
                          claimed.checkId(),
                          settled,
                          control.rounds(),
                          control.tools(),
                          control.elapsed()));
          if (ended) return;
          continue;
        }
        // 认领返回的输入来自同一事务内已验证的交接，读取不到任一上游会整体回滚。
        var attempt =
            control.atBoundary(
                stateLock,
                () -> workflowRuns.claimWorkflowNode(runId, plan.id(), node.id(), task.goal()));
        String id = attempt.agentId();
        // 认领已提交 Agent、尝试和节点；守卫拒绝时也必须由同一结算事务结束它们。
        activeAgent = UUID.fromString(id);
        workflowRuns.requireWorkspaceCurrent(attempt.workspaceGuard());
        var nodePolicy =
            attempt.node().budget() == null
                ? null
                : new RunControl.NodePolicy(attempt.node().id(), attempt.node().budget());
        String result;
        try {
          result = executeAttempt(runId, task, attempt, control, nodePolicy);
        } catch (ModelRateLimitException rejected) {
          control.finishModel(InvocationStatus.FAILED, null, null, rejected.getMessage());
          boolean requeued =
              control.atBoundary(
                  stateLock,
                  () ->
                      control.hasModelRetryBudget(nodePolicy)
                          && workflowRuns.requeueAfterRateLimit(runId, plan.id(), id));
          if (!requeued) throw rejected;
          activeAgent = null;
          control.waitForModelRetry();
          continue;
        }
        boolean completed =
            control.atBoundary(
                stateLock,
                () ->
                    workflowRuns.finishWorkflowNode(
                        runId,
                        plan.id(),
                        id,
                        result,
                        control.rounds(),
                        control.tools(),
                        control.elapsed()));
        activeAgent = null;
        if (completed) return;
      }
    } catch (StopExecution e) {
      stop(runId, activeAgent, control, e.stopCause(), e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      stop(runId, activeAgent, control, StopCause.INTERRUPTED, "进程中断，未结束的模型结果未知。");
    } catch (ModelRateLimitException e) {
      stop(runId, activeAgent, control, StopCause.MODEL_RATE_LIMIT, "模型请求被限流拒绝，当前节点无法继续自动重试。");
    } catch (UncertainToolEffectException e) {
      stop(runId, activeAgent, control, StopCause.TOOL_EFFECT_UNKNOWN, "工具写入结果未知，请先核对工作区状态。");
    } catch (StaleEvidenceException e) {
      stop(runId, activeAgent, control, StopCause.CHECK_FAILED, e.getMessage());
    } catch (IllegalArgumentException | IllegalStateException e) {
      // 装配、版本、授权或上下文约束拒绝：保留项目生成的安全原因，便于从运行详情定位。
      String detail = e.getMessage() == null ? "未提供原因。" : e.getMessage();
      if (detail.length() > 400) detail = detail.substring(0, 399) + "…";
      stop(runId, activeAgent, control, StopCause.UNCLASSIFIED_FAILURE, "工作流执行被拒绝：" + detail);
    } catch (Exception e) {
      stop(runId, activeAgent, control, StopCause.UNCLASSIFIED_FAILURE, "工作流执行失败，请查看节点与调用记录。");
    }
  }

  private String executeAttempt(
      String runId,
      TaskDefinition task,
      WorkflowExecutionService.ClaimedNode attempt,
      RunControl control,
      RunControl.NodePolicy nodePolicy)
      throws InterruptedException, com.fasterxml.jackson.core.JsonProcessingException {
    var node = attempt.node();
    // 候选计划使用节点规格的读写子集；历史模板继续使用任务级文件范围。
    var spec = plans.requireNodeSpec(runId, node.id(), task);
    var files =
        spec != null
            ? spec.readFiles()
            : node.workspaceRead() ? task.fileScopes() : List.<String>of();
    var writeFiles =
        spec != null ? spec.writeFiles() : node.workspaceWrite() ? files : List.<String>of();
    String nodeWorkspaceId = files.isEmpty() ? null : task.workspaceId();
    var input =
        new StepInput(
            task.goal(),
            node.label(),
            task.entryKind().guidance(),
            task.constraints(),
            task.acceptanceCriteria(),
            files,
            sourceArtifacts.constraints(task.id().toString()),
            attempt.dependencies());
    String id = attempt.agentId();
    var request =
        new AgentExecutionRequest(
            node.role(),
            id,
            stepInput(task, attempt, json.writeValueAsString(input)),
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
                traces),
            node.definitionRef(),
            node.definitionSha256(),
            writeFiles)
        .withModelConnection(task.modelConnectionId())
        .withLocale(task.locale());
    return limited(agent.execute(request), 1900);
  }

  private void stop(
      String runId, UUID agentId, RunControl control, StopCause cause, String reason) {
    // 检查阻塞等运行状态没有对应的调用终态；此时只结算运行与节点，不伪造调用状态。
    InvocationStatus status =
        switch (cause.status()) {
          case COMPLETED, FAILED, CANCELLED, LIMITED, INTERRUPTED ->
              InvocationStatus.fromRunStatus(cause.status());
          default -> null;
        };
    if (status != null) {
      control.finishModel(status, null, null, reason);
      control.finishTool(status, null, reason);
    }
    workflowRuns.finishWorkflowStoppedRun(
        runId, agentId, cause, reason, control.rounds(), control.tools(), control.elapsed());
  }

  private static String limited(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }

  private static AgentInput stepInput(
      TaskDefinition task, WorkflowExecutionService.ClaimedNode attempt, String text) {
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
    return new AgentInput(text, sources);
  }

  private record StepInput(
      String goal,
      String step,
      String entryGuidance,
      List<String> constraints,
      List<String> acceptanceCriteria,
      List<String> authorizedFiles,
      List<String> sourceArtifacts,
      List<WorkflowExecutionService.WorkflowInput> dependencies) {}
}
