package dev.workbench.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.bug.BugIntakeService;
import dev.workbench.bug.BugService;
import dev.workbench.domain.*;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.agent.execution.AgentExecutor;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.context.AgentContextAssembler;
import dev.workbench.framework.context.AgentContextInput;
import dev.workbench.framework.context.AgentInput;
import dev.workbench.framework.control.RunControl;
import dev.workbench.framework.control.StopExecution;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.task.RunLimits;
import dev.workbench.task.RunRecordService;
import dev.workbench.workspace.AuthorizedFileSet;
import dev.workbench.workspace.RunHandoverService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import dev.workbench.workspace.WorkspaceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

/** 负责 0.1 单进程任务的创建、调度、取消和检查点恢复。 安全和额度检查由执行器实施，Agent 本身不能授予工具权限。 */
@Service
public class RunService {
  private final RunRecordService repository;
  private final TaskCreationService taskCreations;
  private final AgentExecutor agent;
  private final ObjectMapper json;
  private final WorkspaceService workspaces;
  private final BugService bugs;
  private final BugIntakeService intakes;
  private final RunHandoverService handovers;
  private final EntryPolicyService entryPolicies;
  private final AgentContextAssembler contexts;
  private final WorkflowScheduler workflows;
  private final dev.workbench.task.CostService costs;
  private final EntryRouteService entryRoutes;
  private final WorkspaceAuthorizationService workspaceAuthorization;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
  private final Set<String> paused = ConcurrentHashMap.newKeySet();
  private final java.util.Map<String, Object> pauseMonitors = new ConcurrentHashMap<>();
  /** 诊断计数：只统计真正提交给执行队列的派发次数。 */
  volatile int dispatchInvocationCount;
  /** 测试注入点：在派发前同步观察数据库提交结果。 */
  volatile Runnable beforeDispatch = () -> {};

  @org.springframework.beans.factory.annotation.Autowired
  public RunService(
      RunRecordService repository,
      TaskCreationService taskCreations,
      AgentExecutor agent,
      ObjectMapper json,
      WorkspaceService workspaces,
      BugService bugs,
      BugIntakeService intakes,
      RunHandoverService handovers,
      EntryPolicyService entryPolicies,
      AgentContextAssembler contexts,
      WorkflowScheduler workflows,
      dev.workbench.task.CostService costs,
      EntryRouteService entryRoutes,
      WorkspaceAuthorizationService workspaceAuthorization) {
    this.repository = repository;
    this.taskCreations = taskCreations;
    this.agent = agent;
    this.json = json;
    this.workspaces = workspaces;
    this.bugs = bugs;
    this.intakes = intakes;
    this.handovers = handovers;
    this.entryPolicies = entryPolicies;
    this.contexts = contexts;
    this.workflows = workflows;
    this.costs = costs;
    this.entryRoutes = entryRoutes;
    this.workspaceAuthorization = workspaceAuthorization;
  }

  /** 兼容只构造队列控制边界的单元测试；入口路由、目录授权和费用在完整上下文中由 Spring 注入。 */
  public RunService(
      RunRecordService repository,
      TaskCreationService taskCreations,
      AgentExecutor agent,
      ObjectMapper json,
      WorkspaceService workspaces,
      BugService bugs,
      BugIntakeService intakes,
      RunHandoverService handovers,
      EntryPolicyService entryPolicies,
      AgentContextAssembler contexts,
      WorkflowScheduler workflows) {
    this(
        repository,
        taskCreations,
        agent,
        json,
        workspaces,
        bugs,
        intakes,
        handovers,
        entryPolicies,
        contexts,
        workflows,
        null,
        null,
        null);
  }

  /** 重启后不能假设此前未结束的调用已经成功。 */
  @PostConstruct
  void initialize() {
    repository.interruptRunning();
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  /** 创建一个个人示例任务并排队；不触发真实代码工具。 */
  public String create(CreateTaskRequest request) {
    String goal = request.goal() == null ? "" : request.goal().trim();
    if (goal.isEmpty() || goal.length() > 500) {
      throw new IllegalArgumentException("请输入 1 到 500 字的任务目标。");
    }
    Scenario scenario =
        Scenario.fromCode(request.scenario() == null ? "normal" : request.scenario());
    ModelMode mode = ModelMode.fromCode(request.modelMode() == null ? "mock" : request.modelMode());
    String workspaceId =
        request.workspaceId() == null || request.workspaceId().isBlank()
            ? null
            : request.workspaceId();
    String fileScope =
        request.fileScope() == null || request.fileScope().isBlank()
            ? null
            : request.fileScope().replace('\\', '/');
    boolean hasFileList = request.fileScopes() != null && !request.fileScopes().isEmpty();
    if (hasFileList && fileScope != null)
      throw new IllegalArgumentException("请使用文件列表或单文件字段，不能同时指定。");
    List<String> fileScopes = List.of();
    WorkspaceAuthorizationService.Prepared workspaceScope = null;
    boolean writeEnabled = Boolean.TRUE.equals(request.writeEnabled());
    boolean memoryEnabled = Boolean.TRUE.equals(request.memoryEnabled());
    String bugId = request.bugId() == null || request.bugId().isBlank() ? null : request.bugId();
    if (bugId != null
        && (workspaceId == null || bugs.list().stream().noneMatch(b -> b.id().equals(bugId))))
      throw new IllegalArgumentException("模拟 Bug 必须关联有效工作区。");
    List<String> directoryScopes =
        request.directoryScopes() == null ? List.of() : request.directoryScopes();
    List<String> excludedPaths =
        request.excludedPaths() == null ? List.of() : request.excludedPaths();
    Long maxCostMicros = request.maxCostMicros();
    if (!directoryScopes.isEmpty() && workspaceId == null)
      throw new IllegalArgumentException("目录授权必须选择工作区。");
    if (!excludedPaths.isEmpty() && directoryScopes.isEmpty())
      throw new IllegalArgumentException("排除规则必须配合目录授权使用。");
    if (workspaceId != null) {
      var requested =
          hasFileList
              ? request.fileScopes()
              : fileScope == null ? List.<String>of() : List.of(fileScope);
      if (workspaceAuthorization != null) {
        workspaceScope =
            workspaceAuthorization.prepare(workspaceId, requested, directoryScopes, excludedPaths);
        fileScopes = workspaceScope.effectiveFiles();
      } else {
        fileScopes = new AuthorizedFileSet(requested).paths();
      }
      if (fileScopes.isEmpty())
        throw new IllegalArgumentException("请选择至少一个授权文件或目录。");
      try {
        if (json.writeValueAsString(fileScopes).length() > 20_000)
          throw new IllegalArgumentException("授权文件路径总长度超过允许范围。");
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("授权文件路径无法处理。", e);
      }
      for (String path : fileScopes) workspaces.read(workspaceId, path);
      fileScope = fileScopes.getFirst();
    } else if (fileScope != null || hasFileList || writeEnabled || memoryEnabled)
      throw new IllegalArgumentException("请先选择工作区。");
    int rounds = bounded(request.maxRounds(), writeEnabled ? 8 : 4, 1, 20);
    int tools = bounded(request.maxTools(), writeEnabled ? 5 : 2, 0, 20);
    int perTool = bounded(request.maxPerTool(), tools, 0, 20);
    int seconds = bounded(request.maxSeconds(), 30, 1, 300);
    Long tokenLimit = request.maxReportedTokens();
    if (tokenLimit != null && (tokenLimit < 1 || tokenLimit > 1_000_000))
      throw new IllegalArgumentException("已报告 Token 上限须在 1 至 1000000 之间。");
    Integer identicalLimit = request.maxIdenticalTools();
    if (identicalLimit != null && (identicalLimit < 1 || identicalLimit > 20))
      throw new IllegalArgumentException("连续相同工具调用上限须在 1 至 20 之间。");
    Integer agentRounds = request.maxAgentRounds();
    Integer agentTools = request.maxAgentTools();
    if (agentRounds != null && (agentRounds < 1 || agentRounds > 20))
      throw new IllegalArgumentException("单个 Agent 模型调用上限须在 1 至 20 之间。");
    if (agentTools != null && (agentTools < 0 || agentTools > 20))
      throw new IllegalArgumentException("单个 Agent 工具调用上限须在 0 至 20 之间。");
    var roleBudgets =
        request.roleBudgets() == null
            ? java.util.Map.<AgentRole, RoleBudget>of()
            : request.roleBudgets();
    if (roleBudgets.entrySet().stream()
        .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null))
      throw new IllegalArgumentException("角色限额不能包含空角色或空配置。");
    roleBudgets = java.util.Map.copyOf(roleBudgets);
    var toolBudgets =
        request.toolBudgets() == null
            ? java.util.Map.<ToolKind, Integer>of()
            : request.toolBudgets();
    if (toolBudgets.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey() == null
                    || entry.getValue() == null
                    || entry.getValue() < 0
                    || entry.getValue() > 20))
      throw new IllegalArgumentException("工具限额必须使用已知工具和 0 至 20 的整数。");
    toolBudgets = java.util.Map.copyOf(toolBudgets);
    List<String> constraints = validatedItems(request.constraints(), "约束");
    List<String> acceptance = validatedItems(request.acceptanceCriteria(), "验收条件");
    TaskEntryKind entryKind =
        request.entryKind() == null ? TaskEntryKind.GENERAL : request.entryKind();
    EntryPolicyService.Validation entryValidation =
        switch (entryKind) {
          case REQUIREMENT, DESIGN, CODE, TEST ->
              entryPolicies.validate(
                  entryKind, goal, workspaceId, writeEnabled, fileScopes, acceptance);
          default -> null;
        };
    List<String> effectiveAcceptance =
        entryValidation == null ? acceptance : entryValidation.acceptance();
    EntryRouteService.Decision routeDecision =
        entryRoutes == null
            ? null
            : entryRoutes.route(
                entryKind,
                workspaceId,
                writeEnabled,
                fileScopes,
                directoryScopes,
                request.workflowTemplate());
    String selectedTemplate =
        request.workflowTemplate() != null
            ? request.workflowTemplate()
            : routeDecision == null ? null : routeDecision.templateId();
    String taskId = RunRecordService.id();
    String runId = RunRecordService.id();
    var task =
        new TaskDefinition(
            java.util.UUID.fromString(taskId),
            goal,
            scenario,
            mode,
            workspaceId,
            fileScope,
            writeEnabled,
            bugId,
            memoryEnabled,
            constraints,
            effectiveAcceptance,
            entryKind,
            fileScopes,
            roleBudgets,
            toolBudgets);
    var limits =
        new RunLimits(
            rounds, tools, perTool, tokenLimit, identicalLimit, seconds, agentRounds, agentTools);
    boolean reviewPlan = Boolean.TRUE.equals(request.reviewPlan());
    WorkflowTemplate template =
        selectedTemplate == null ? null : workflows.template(selectedTemplate);
    if (reviewPlan && template == null) throw new IllegalArgumentException("只有动态模板支持执行前确认。");
    if (template == null && request.nodeBudgets() != null && !request.nodeBudgets().isEmpty())
      throw new IllegalArgumentException("只有动态模板支持节点预算。");
    if (template != null) {
      template.scope().authorizationDigest(task);
      if (template.scope().requiresBug() && !bugs.supports(task.bugId()))
        throw new IllegalArgumentException("所选模拟 Bug 不存在或当前不受支持。");
      template = template.withNodeBudgets(request.nodeBudgets());
    }
    String createdMessage =
        mode == ModelMode.DEEPSEEK
            ? "任务已创建：AgentScope 使用 DeepSeek Flash，可调用模拟工具。"
            : "任务已创建：AgentScope 使用模拟模型和模拟工具。";
    if (template == null) {
      taskCreations.persist(
          task,
          java.util.UUID.fromString(runId),
          limits,
          createdMessage,
          null,
          false,
          entryValidation,
          routeDecision,
          workspaceScope,
          maxCostMicros);
    } else {
      taskCreations.persist(
          task,
          java.util.UUID.fromString(runId),
          limits,
          reviewPlan ? "工作流计划已保存，等待用户确认。" : "工作流计划已保存，等待执行。",
          template,
          reviewPlan,
          entryValidation,
          routeDecision,
          workspaceScope,
          maxCostMicros);
    }
    // 事务已提交；待确认计划仍等待用户批准，不触发派发。
    if (!reviewPlan) dispatchAfterCommit(runId);
    return runId;
  }

  /** 一个计划版本只接受一次确认。 */
  public synchronized void approvePlan(String runId, java.util.UUID planVersionId) {
    workflows.approvePlan(runId, planVersionId);
    dispatchAfterCommit(runId);
  }

  /** 补充接受后从安全边界重新排队；沿用与首次派发相同的单线程队列与拒绝语义。 */
  public void dispatchContinuation(String runId) {
    dispatchAfterCommit(runId);
  }

  /** 有外层事务时只在提交成功后派发；无活动事务时调用方已完成提交。 */
  private void dispatchAfterCommit(String runId) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
            .isSynchronizationActive()
        && org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(
              new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  dispatchInitial(runId);
                }
              });
      return;
    }
    dispatchInitial(runId);
  }

  private void dispatchInitial(String runId) {
    beforeDispatch.run();
    dispatchInvocationCount++;
    try {
      executor.submit(() -> execute(runId, CheckpointStage.BEFORE_TOOL, null, false));
    } catch (java.util.concurrent.RejectedExecutionException e) {
      String reason = "运行未能加入执行队列，尚未启动 Agent。";
      repository.finishStoppedRun(
          new dev.workbench.task.StopCompletion(
              java.util.UUID.fromString(runId),
              null,
              dev.workbench.domain.StopCause.UNCLASSIFIED_FAILURE,
              reason,
              0,
              0,
              0,
              null));
    }
  }

  /** 排队中的任务立即终止；运行中的任务通过信号协作取消。 */
  public synchronized void cancel(String id) {
    RunSnapshot run = repository.runSnapshot(id);
    if (!run.status().canCancel()) throw new IllegalStateException("运行已结束，不能取消。");
    repository.event(id, EventType.CANCEL_REQUESTED, "用户请求取消。");
    if (run.status() == RunStatus.PENDING) {
      repository.finishStoppedRun(
          new dev.workbench.task.StopCompletion(
              java.util.UUID.fromString(id),
              null,
              dev.workbench.domain.StopCause.USER_CANCELLED,
              "用户取消了排队中的运行，尚未启动 Agent。",
              run.rounds(),
              run.tools(),
              run.elapsedMs(),
              null));
    } else {
      cancelled.add(id);
      Object monitor = pauseMonitors.get(id);
      if (monitor != null)
        synchronized (monitor) {
          monitor.notifyAll();
        }
    }
  }

  /** 请求在下一个协作检查点暂停；在途模型请求可能仍会结束并产生费用。 */
  public synchronized void pause(String id) {
    RunSnapshot run = repository.runSnapshot(id);
    if (run.status() != RunStatus.RUNNING) throw new IllegalStateException("只有运行中的任务可以请求暂停。");
    if (paused.add(id))
      repository.event(id, EventType.PAUSE_REQUESTED, "已请求暂停，等待安全检查点；在途调用可能继续结束。");
  }

  /** 恢复当前进程内的暂停；进程重启后的恢复仍使用检查点。 */
  public synchronized void resume(String id) {
    RunSnapshot run = repository.runSnapshot(id);
    if (run.status() != RunStatus.PAUSED
        && !(run.status() == RunStatus.RUNNING && paused.contains(id)))
      throw new IllegalStateException("该任务没有可继续的暂停请求。");
    String handoverBlock = handovers.resumeBlockReason(id);
    if (handoverBlock != null) throw new IllegalStateException(handoverBlock);
    paused.remove(id);
    repository.event(id, EventType.RESUMED, "用户请求继续执行。");
    Object monitor = pauseMonitors.get(id);
    if (monitor != null)
      synchronized (monitor) {
        monitor.notifyAll();
      }
  }

  /** 从有效快照创建新运行，不覆盖历史。此处的同步只覆盖本进程； 外部副作用和分布式并发恢复尚未实现。 */
  public synchronized String recover(String oldId, String checkpointId) {
    RunSnapshot original = repository.runSnapshot(oldId);
    if (original.status() != RunStatus.INTERRUPTED) throw new IllegalStateException("只有中断的运行可以恢复。");
    if (workflows.plan(oldId) != null) {
      String nextId = RunRecordService.id();
      workflows.recover(
          oldId, java.util.UUID.fromString(checkpointId), java.util.UUID.fromString(nextId));
      dispatchRecovery(nextId, CheckpointStage.BEFORE_TOOL, null);
      return nextId;
    }
    TaskDefinition task = repository.taskDefinition(original.taskId().toString());
    CheckpointSnapshot checkpoint = repository.checkedCheckpoint(checkpointId);
    if (!checkpoint.runId().equals(original.id())) throw new IllegalArgumentException("检查点不属于该运行。");
    if (task.bugId() != null || task.writeEnabled())
      throw new IllegalStateException("写入或 Bug 任务尚不支持安全恢复，请先人工检查外部状态。");
    if (task.workspaceId() != null) {
      if (task.memoryEnabled()
          || !Integer.valueOf(6).equals(checkpoint.version())
          || checkpoint.stage() != CheckpointStage.BEFORE_REVIEW)
        throw new IllegalStateException("当前工作区任务或检查点不支持安全恢复。");
      requireUnchangedWorkspace(task, repository.workspaceReviewPayload(checkpoint.toolResult()));
    }
    if (repository.hasRecovery(oldId)) throw new IllegalStateException("此运行已经发起恢复。");

    String nextId = RunRecordService.id();
    repository.createRecoveryRun(java.util.UUID.fromString(nextId), original, checkpoint);
    if (costs != null) costs.inheritLimit(oldId, nextId);
    dispatchRecovery(nextId, checkpoint.stage(), checkpoint.toolResult());
    return nextId;
  }

  private void dispatchRecovery(String nextId, CheckpointStage stage, String previousResult) {
    try {
      executor.submit(() -> execute(nextId, stage, previousResult, true));
    } catch (java.util.concurrent.RejectedExecutionException e) {
      repository.rejectRecoveryBeforeDispatch(nextId);
    }
  }

  private void execute(String id, CheckpointStage stage, String previousResult, boolean recovery) {
    RunSnapshot run;
    TaskDefinition task;
    WorkflowPlan workflow;
    // 与排队取消共享同一把锁，保证已取消的任务不会再次被启动。
    synchronized (this) {
      run = repository.runSnapshot(id);
      if (run.status() != RunStatus.PENDING) return;
      try {
        task = repository.taskDefinition(run.taskId().toString());
        workflow = workflows.plan(id);
      } catch (RuntimeException invalid) {
        repository.finishStoppedRun(
            new dev.workbench.task.StopCompletion(
                java.util.UUID.fromString(id),
                null,
                StopCause.UNCLASSIFIED_FAILURE,
                "任务配置无法确认，未启动 Agent。",
                run.rounds(),
                run.tools(),
                run.elapsedMs(),
                null));
        return;
      }
      if (workflow != null && workflow.approvedAt() == null) return;
      repository.update(
          id, RunStatus.RUNNING, null, null, run.rounds(), run.tools(), run.elapsedMs());
    }
    RunControl control =
        new RunControl(
            id,
            run,
            repository,
            pauseMonitors.computeIfAbsent(id, key -> new Object()),
            paused,
            cancelled,
            task.roleBudgets(),
            task.toolBudgets());
    if (workflow != null) {
      try {
        workflows.execute(id, workflow, task, control, this);
      } finally {
        clearControl(id);
      }
      return;
    }
    String agentId = RunRecordService.id();
    boolean agentStarted = false;
    AgentRole activeRole = AgentRole.ANALYST;
    try {
      control.check();
      repository.event(id, EventType.RUN_STARTED, "运行开始。");
      BugService.Check initial = null;
      if (task.bugId() != null) {
        repository.event(id, EventType.TEST_STARTED, "正在容器中观察模拟 Bug 的测试状态。");
        initial =
            bugs.runForTask(
                id,
                task.bugId(),
                task.workspaceId(),
                BugService.CheckKind.REPRODUCE,
                control::check);
        intakes.triage(initial);
        repository.event(
            id,
            EventType.TEST_FINISHED,
            "检测记录 " + initial.id() + "：" + initial.status() + "。失败不自动证明特定 Bug 已复现。");
        if (initial.status() != BugService.CheckStatus.FAILURES_OBSERVED) {
          String reason = "未观察到测试失败或验证条件不足，暂停自动修改；检测结果：" + initial.status();
          control.save(RunStatus.BLOCKED, reason, null);
          repository.event(id, EventType.BLOCKED, reason);
          return;
        }
      }
      String answer;
      String evidenceRun;
      if (stage == CheckpointStage.BEFORE_REVIEW && recovery) {
        if (run.parentId() == null || task.bugId() != null || task.writeEnabled())
          throw new IllegalStateException("当前任务不支持从分析后检查点恢复。");
        dev.workbench.domain.WorkspaceReviewCheckpoint workspaceCheckpoint = null;
        String analysisReference = previousResult;
        if (task.workspaceId() != null) {
          workspaceCheckpoint = repository.workspaceReviewPayload(previousResult);
          requireUnchangedWorkspace(task, workspaceCheckpoint);
          analysisReference = workspaceCheckpoint.analysisReference();
        }
        var source =
            repository.checkpointAnalysisSource(run.parentId().toString(), analysisReference);
        evidenceRun = source.runId().toString();
        agentId = source.agentId().toString();
        answer = source.result();
        repository.reuseAnalysis(id, source.agentId());
        repository.event(id, EventType.HANDOFF, "已校验并复用历史分析结果；本次运行未重新调用分析 Agent。");
        String reference = repository.ancestorAnalysisReference(id, evidenceRun, source.agentId());
        if (workspaceCheckpoint == null) {
          repository.checkpoint(
              id,
              CheckpointStage.BEFORE_REVIEW,
              control.rounds(),
              control.tools(),
              control.elapsed(),
              reference);
        } else {
          repository.workspaceReviewCheckpoint(
              id,
              control.rounds(),
              control.tools(),
              control.elapsed(),
              new dev.workbench.domain.WorkspaceReviewCheckpoint(
                  workspaceCheckpoint.workspaceId(),
                  reference,
                  workspaceCheckpoint.workspaceFingerprint()));
        }
      } else {
        String beforeAnalysis = fingerprintIfEligible(task);
        Handoff delegation = buildDelegation(run, task, agentId);
        repository.startAgentWithDelegation(agentId, id, task.goal(), delegation);
        agentStarted = true;
        repository.event(
            id,
            EventType.DELEGATED,
            recovery ? "协调者从检查点重新委派 AgentScope 分析 Agent。" : "协调者将分析子任务委派给 AgentScope 分析 Agent。");
        repository.event(id, EventType.AGENT_STARTED, "AgentScope 分析 Agent 开始执行，实例：" + agentId);
        if (stage == CheckpointStage.BEFORE_TOOL && !recovery) {
          repository.checkpoint(
              id, stage, control.rounds(), control.tools(), control.elapsed(), null);
        }
        var analystDefinition = repository.plannedDefinitionForAgent(id, agentId);
        answer =
            agent.execute(
                new AgentExecutionRequest(
                    AgentRole.ANALYST,
                    agentId,
                    contexts.assembleInput(new AgentContextInput(task, id, initial)),
                    task.workspaceId(),
                    task.fileScopes(),
                    false,
                    task.scenario(),
                    task.modelMode(),
                    stage,
                    previousResult,
                    runtimeFor(id, agentId, control),
                    analystDefinition.reference(),
                    analystDefinition.sha256()));
        if (answer.length() > 2000) answer = answer.substring(0, 1990) + "…（结果已截断）";
        evidenceRun = id;
        Handoff analysisResult =
            buildAnalysisResult(run, task, agentId, answer, evidenceRun, initial);
        repository.finishAgentWithResult(agentId, answer, analysisResult);
        agentStarted = false;
        repository.event(id, EventType.HANDOFF, "分析 Agent 已提交结构化结果。准备交给审查 Agent。");
        if (task.workspaceId() == null && task.bugId() == null) {
          String reference = repository.analysisReference(id, agentId);
          repository.checkpoint(
              id,
              CheckpointStage.BEFORE_REVIEW,
              control.rounds(),
              control.tools(),
              control.elapsed(),
              reference);
        } else if (task.bugId() != null) {
          recordCheckpointSkipped(id, CheckpointSkipReason.BUG_TASK);
        } else if (task.writeEnabled()) {
          recordCheckpointSkipped(id, CheckpointSkipReason.WRITE_TASK);
        } else if (task.memoryEnabled()) {
          recordCheckpointSkipped(id, CheckpointSkipReason.MEMORY_CONTEXT);
        } else if (beforeAnalysis == null) {
          recordCheckpointSkipped(id, CheckpointSkipReason.WORKSPACE_BEFORE_UNAVAILABLE);
        } else {
          String afterAnalysis = fingerprintIfEligible(task);
          if (afterAnalysis == null) {
            recordCheckpointSkipped(id, CheckpointSkipReason.WORKSPACE_AFTER_UNAVAILABLE);
          } else if (!beforeAnalysis.equals(afterAnalysis)) {
            recordCheckpointSkipped(id, CheckpointSkipReason.WORKSPACE_CHANGED);
          } else {
            String reference = repository.analysisReference(id, agentId);
            repository.workspaceReviewCheckpoint(
                id,
                control.rounds(),
                control.tools(),
                control.elapsed(),
                new dev.workbench.domain.WorkspaceReviewCheckpoint(
                    java.util.UUID.fromString(task.workspaceId()), reference, beforeAnalysis));
          }
        }
      }
      control.check();
      String analysisAnswer = answer;
      HandoffParty upstreamParty = HandoffParty.ANALYSIS_AGENT;
      if (task.writeEnabled()) {
        String coderId = RunRecordService.id();
        Handoff codingInput =
            new Handoff(
                task.id().toString(),
                id,
                agentId,
                HandoffParty.ANALYSIS_AGENT,
                HandoffParty.CODING_AGENT,
                task.goal(),
                handoffConstraints(task, List.of("仅可修改用户授权的文件", "分析结论需要结合真实代码与测试验证")),
                HandoffStatus.COMPLETED,
                analysisAnswer,
                evidenceRun,
                repository.toolEvidence(evidenceRun),
                task.modelMode() == ModelMode.DEEPSEEK
                    ? HandoffSource.DEEPSEEK_FLASH
                    : HandoffSource.MOCK_MODEL,
                coderId);
        codingInput = attachTestChecks(codingInput, initial);
        AgentInput codingPrompt = handoffInput(codingInput);
        repository.handoff(HandoffDirection.AGENT_TRANSFER, codingInput);
        repository.event(id, EventType.DELEGATED, "分析交接已准备，计划接收实例：" + coderId);
        repository.startAgentFromHandoff(
            coderId, id, task.goal(), AgentRole.CODER, codingInput.handoffId());
        agentId = coderId;
        activeRole = AgentRole.CODER;
        agentStarted = true;
        repository.event(id, EventType.AGENT_STARTED, "AgentScope 开发 Agent 开始执行，实例：" + coderId);
        var coderDefinition = repository.plannedDefinitionForAgent(id, coderId);
        answer =
            agent.execute(
                new AgentExecutionRequest(
                    AgentRole.CODER,
                    coderId,
                    codingPrompt,
                    task.workspaceId(),
                    task.fileScopes(),
                    true,
                    task.scenario(),
                    task.modelMode(),
                    CheckpointStage.BEFORE_TOOL,
                    null,
                    runtimeFor(id, coderId, control),
                    coderDefinition.reference(),
                    coderDefinition.sha256()));
        if (answer.length() > 1900) answer = answer.substring(0, 1890) + "…（结果已截断）";
        Handoff codingResult =
            attachTestChecks(
                new Handoff(
                    task.id().toString(),
                    id,
                    coderId,
                    HandoffParty.CODING_AGENT,
                    HandoffParty.COORDINATOR,
                    task.goal(),
                    handoffConstraints(task, List.of("开发结果需由代码差异和测试证据核对")),
                    HandoffStatus.COMPLETED,
                    answer,
                    id,
                    repository.toolEvidence(id),
                    task.modelMode() == ModelMode.DEEPSEEK
                        ? HandoffSource.DEEPSEEK_FLASH
                        : HandoffSource.MOCK_MODEL,
                    null),
                initial);
        repository.finishAgentWithResult(coderId, answer, codingResult);
        agentStarted = false;
        repository.event(id, EventType.HANDOFF, "开发 Agent 已返回结果；是否修改和测试通过以实际记录为准。");
        upstreamParty = HandoffParty.CODING_AGENT;
        control.check();
      }
      BugService.Check regression = null;
      if (task.bugId() != null && task.writeEnabled()) {
        repository.event(id, EventType.TEST_STARTED, "正在容器中执行回归检查。");
        regression =
            bugs.runForTask(
                id,
                task.bugId(),
                task.workspaceId(),
                BugService.CheckKind.REGRESSION,
                control::check);
        intakes.regression(regression);
        repository.event(
            id,
            EventType.TEST_FINISHED,
            "回归检测记录 " + regression.id() + "：" + regression.status() + "；需结合具体测试与差异审查。");
      }
      String reviewerId = RunRecordService.id();
      String verification =
          regression == null
              ? "没有执行本次回归测试"
              : "本次回归检测记录 " + regression.id() + "，结果 " + regression.status();
      Handoff transfer =
          new Handoff(
              task.id().toString(),
              run.id().toString(),
              agentId,
              upstreamParty,
              HandoffParty.REVIEW_AGENT,
              task.goal(),
              handoffConstraints(task, List.of("模拟检查结果不能当作真实代码证据", "模型结论需要独立验证", verification)),
              HandoffStatus.COMPLETED,
              task.writeEnabled() ? "分析结论：\n" + analysisAnswer + "\n开发结论：\n" + answer : answer,
              evidenceRun,
              repository.toolEvidence(evidenceRun),
              task.modelMode() == ModelMode.DEEPSEEK
                  ? HandoffSource.DEEPSEEK_FLASH
                  : HandoffSource.MOCK_MODEL,
              reviewerId);
      transfer = attachTestChecks(transfer, initial, regression);
      AgentInput reviewInput = handoffInput(transfer);
      repository.handoff(HandoffDirection.AGENT_TRANSFER, transfer);
      repository.event(id, EventType.DELEGATED, "审查交接已准备，计划接收实例：" + reviewerId);
      repository.startAgentFromHandoff(
          reviewerId, id, task.goal(), AgentRole.REVIEWER, transfer.handoffId());
      agentId = reviewerId;
      activeRole = AgentRole.REVIEWER;
      agentStarted = true;
      repository.event(id, EventType.AGENT_STARTED, "AgentScope 审查 Agent 开始执行，实例：" + reviewerId);
      var reviewDefinition = repository.plannedDefinitionForAgent(id, reviewerId);
      String review =
          agent.execute(
              new AgentExecutionRequest(
                  AgentRole.REVIEWER,
                  reviewerId,
                  reviewInput,
                  null,
                  List.of(),
                  false,
                  task.scenario(),
                  task.modelMode(),
                  CheckpointStage.BEFORE_TOOL,
                  null,
                  runtimeFor(id, reviewerId, control),
                  reviewDefinition.reference(),
                  reviewDefinition.sha256()));
      if (review.length() > 1900) review = review.substring(0, 1890) + "…（结果已截断）";
      Handoff reviewResult =
          attachTestChecks(
              new Handoff(
                  task.id().toString(),
                  id,
                  reviewerId,
                  HandoffParty.REVIEW_AGENT,
                  HandoffParty.COORDINATOR,
                  task.goal(),
                  List.of("审查基于模型输出和模拟证据，不能代替真实测试"),
                  HandoffStatus.COMPLETED,
                  review,
                  evidenceRun,
                  repository.toolEvidence(evidenceRun),
                  task.modelMode() == ModelMode.DEEPSEEK
                      ? HandoffSource.DEEPSEEK_FLASH
                      : HandoffSource.MOCK_MODEL,
                  null),
              initial,
              regression);
      boolean verificationBlocked = task.bugId() != null || task.writeEnabled();
      while (true) {
        control.check();
        synchronized (this) {
          if (paused.contains(id) || cancelled.contains(id)) continue;
          String finalMessage =
              "分析结果：\n"
                  + analysisAnswer.substring(0, Math.min(650, analysisAnswer.length()))
                  + (task.writeEnabled()
                      ? "\n\n开发结果：\n" + answer.substring(0, Math.min(550, answer.length()))
                      : "")
                  + "\n\n审查结果：\n"
                  + review.substring(0, Math.min(700, review.length()));
          if (verificationBlocked) {
            String reason =
                task.bugId() == null
                    ? "开发流程已结束，仍需用户检查差异并验证验收条件。"
                    : regression == null
                        ? "仅完成分析，未执行本次回归；尚不能报告 Bug 已修复。"
                        : regression.status() == BugService.CheckStatus.PASSED
                            ? "回归测试通过，仍需用户结合差异与原问题审查。"
                            : "回归检查未通过或无法执行；不得报告 Bug 已修复。";
            repository.finishReviewedRun(
                new dev.workbench.task.ReviewCompletion(
                    java.util.UUID.fromString(id),
                    java.util.UUID.fromString(reviewerId),
                    review,
                    reviewResult,
                    RunStatus.BLOCKED,
                    reason,
                    finalMessage,
                    control.rounds(),
                    control.tools(),
                    control.elapsed()));
          } else {
            repository.finishReviewedRun(
                new dev.workbench.task.ReviewCompletion(
                    java.util.UUID.fromString(id),
                    java.util.UUID.fromString(reviewerId),
                    review,
                    reviewResult,
                    RunStatus.COMPLETED,
                    null,
                    finalMessage,
                    control.rounds(),
                    control.tools(),
                    control.elapsed()));
          }
          agentStarted = false;
          break;
        }
      }
    } catch (StopExecution e) {
      finishUnsuccessful(
          id, agentId, agentStarted, activeRole, task, control, e.stopCause(), e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      finishUnsuccessful(
          id,
          agentId,
          agentStarted,
          activeRole,
          task,
          control,
          dev.workbench.domain.StopCause.INTERRUPTED,
          "进程停止，调用结果可能未知。");
    } catch (WorkspaceRecoveryConflictException e) {
      if (recovery && !agentStarted && run.parentId() != null) {
        repository.rejectRecoveryPrecondition(
            id, control.rounds(), control.tools(), control.elapsed(), e.getMessage());
      } else {
        finishUnsuccessful(
            id,
            agentId,
            agentStarted,
            activeRole,
            task,
            control,
            dev.workbench.domain.StopCause.UNCLASSIFIED_FAILURE,
            e.getMessage());
      }
    } catch (dev.workbench.framework.tool.UncertainToolEffectException e) {
      finishUnsuccessful(
          id,
          agentId,
          agentStarted,
          activeRole,
          task,
          control,
          dev.workbench.domain.StopCause.TOOL_EFFECT_UNKNOWN,
          "工具写操作结果未知，请先检查工作区或外部状态，不要盲目重试。");
    } catch (Exception e) {
      finishUnsuccessful(
          id,
          agentId,
          agentStarted,
          activeRole,
          task,
          control,
          dev.workbench.domain.StopCause.UNCLASSIFIED_FAILURE,
          "执行失败，请结合运行记录检查模型、工具或状态。");
    } finally {
      clearControl(id);
    }
  }

  private void clearControl(String id) {
    cancelled.remove(id);
    paused.remove(id);
    pauseMonitors.remove(id);
  }

  /** 仅记录固定原因，不将路径、文件内容或异常详情写入用户轨迹。 */
  private void recordCheckpointSkipped(String runId, CheckpointSkipReason reason) {
    repository.checkpointSkipped(runId, reason);
  }

  /** 仅只读、无 Bug、未启用项目记忆的工作区可尝试形成检查点。 */
  private String fingerprintIfEligible(TaskDefinition task) {
    if (task.workspaceId() == null
        || task.writeEnabled()
        || task.bugId() != null
        || task.memoryEnabled()) return null;
    try {
      return workspaces.verificationFingerprint(task.workspaceId());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 用户请求与后台启动时都要验证，避免队列等待期间工作区发生变化。 */
  private void requireUnchangedWorkspace(
      TaskDefinition task, dev.workbench.domain.WorkspaceReviewCheckpoint checkpoint) {
    if (task.workspaceId() == null
        || task.writeEnabled()
        || task.bugId() != null
        || task.memoryEnabled()
        || !task.workspaceId().equals(checkpoint.workspaceId().toString()))
      throw new IllegalStateException("任务不满足只读工作区恢复条件。");
    String current = fingerprintIfEligible(task);
    if (current == null
        || !java.security.MessageDigest.isEqual(
            current.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            checkpoint.workspaceFingerprint().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
      throw new WorkspaceRecoveryConflictException("工作区状态无法确认或已变化，不能使用此检查点恢复。");
  }

  private List<String> handoffConstraints(TaskDefinition task, List<String> fixed) {
    var all = new java.util.ArrayList<>(fixed);
    all.add("用户选择的任务入口：" + task.entryKind().label() + "；入口类型不构成额外授权或已验证事实。");
    if (!task.fileScopes().isEmpty()) {
      try {
        all.add("用户授权文件路径 JSON：" + json.writeValueAsString(task.fileScopes()));
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("无法装配授权文件路径。", e);
      }
    }
    task.constraints().forEach(value -> all.add("用户约束：" + value));
    task.acceptanceCriteria().forEach(value -> all.add("待验证验收条件：" + value));
    return List.copyOf(all);
  }

  /** 发送给审查 Agent 的内容与持久化交接一致，便于追踪信息来源。 */
  private AgentInput handoffInput(Handoff handoff) {
    try {
      var sources = new java.util.ArrayList<AgentInput.Source>();
      sources.add(
          new AgentInput.Source(
              AgentInput.Kind.HANDOFF, handoff.handoffId().toString(), handoff.schemaVersion()));
      if (handoff.testCheckIds() != null) {
        for (var check : handoff.testCheckIds())
          sources.add(new AgentInput.Source(AgentInput.Kind.BUG_CHECK, check.toString(), null));
      }
      return new AgentInput(json.writeValueAsString(handoff), sources);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("无法序列化 Agent 交接信息。", e);
    }
  }

  private AgentRuntime runtimeFor(String runId, String agentId, RunControl control) {
    return new ControlledAgentRuntime(runId, agentId, control, repository, null, costs, workspaceAuthorization);
  }

  private Handoff buildDelegation(RunSnapshot run, TaskDefinition task, String agentId) {
    return new Handoff(
        task.id().toString(),
        run.id().toString(),
        agentId,
        HandoffParty.COORDINATOR,
        HandoffParty.ANALYSIS_AGENT,
        task.goal(),
        handoffConstraints(
            task,
            task.workspaceId() == null
                ? List.of("仅使用模拟工具", "遵守运行限制")
                : List.of("仅允许访问用户指定的工作区文件", "分析 Agent 仅允许只读", "遵守运行限制")),
        HandoffStatus.ASSIGNED,
        task.workspaceId() == null ? "分析模拟工具结果并交付建议" : "在授权范围内分析工作区文件并交付建议",
        null,
        List.of(),
        HandoffSource.COORDINATOR,
        agentId);
  }

  private Handoff buildAnalysisResult(
      RunSnapshot run,
      TaskDefinition task,
      String agentId,
      String answer,
      String evidenceRun,
      BugService.Check initial) {
    HandoffSource source =
        task.modelMode() == ModelMode.DEEPSEEK
            ? HandoffSource.DEEPSEEK_FLASH
            : HandoffSource.MOCK_MODEL;
    return attachTestChecks(
        new Handoff(
            task.id().toString(),
            run.id().toString(),
            agentId,
            HandoffParty.ANALYSIS_AGENT,
            HandoffParty.COORDINATOR,
            task.goal(),
            List.of("模拟工具结果不代表真实测试", "模型回答需要独立验证"),
            HandoffStatus.COMPLETED,
            answer,
            evidenceRun,
            repository.toolEvidence(evidenceRun),
            source,
            null),
        initial);
  }

  /** 仅将本次运行实际生成的检测记录加入版本化交接，写入时还需仓储校验归属。 */
  private Handoff attachTestChecks(Handoff handoff, BugService.Check... checks) {
    var ids =
        java.util.Arrays.stream(checks)
            .filter(java.util.Objects::nonNull)
            .map(check -> java.util.UUID.fromString(check.id()))
            .toList();
    return ids.isEmpty() ? handoff : handoff.withTestChecks(ids);
  }

  private void finishUnsuccessful(
      String runId,
      String agentId,
      boolean agentStarted,
      AgentRole activeRole,
      TaskDefinition task,
      RunControl control,
      dev.workbench.domain.StopCause stopCause,
      String reason) {
    RunStatus status = stopCause.status();
    InvocationStatus invocationStatus = InvocationStatus.fromRunStatus(status);
    control.finishModel(invocationStatus, null, null, reason);
    control.finishTool(invocationStatus, null, reason);
    Handoff systemHandoff = null;
    if (agentStarted) {
      HandoffParty sender =
          switch (activeRole) {
            case ANALYST -> HandoffParty.ANALYSIS_AGENT;
            case CODER -> HandoffParty.CODING_AGENT;
            case REVIEWER -> HandoffParty.REVIEW_AGENT;
          };
      systemHandoff =
          new Handoff(
              task.id().toString(),
              runId,
              agentId,
              sender,
              HandoffParty.COORDINATOR,
              task.goal(),
              handoffConstraints(task, List.of("该交接由系统记录，未完成或未知内容不能视为成功")),
              HandoffStatus.valueOf(status.name()),
              reason,
              runId,
              repository.toolEvidence(runId),
              HandoffSource.SYSTEM,
              null);
    }
    repository.finishStoppedRun(
        new dev.workbench.task.StopCompletion(
            java.util.UUID.fromString(runId),
            agentStarted ? java.util.UUID.fromString(agentId) : null,
            stopCause,
            reason,
            control.rounds(),
            control.tools(),
            control.elapsed(),
            systemHandoff));
  }

  private static int bounded(Integer value, int fallback, int minimum, int maximum) {
    int actual = value == null ? fallback : value;
    if (actual < minimum || actual > maximum) throw new IllegalArgumentException("限额超出允许范围。");
    return actual;
  }

  private static List<String> validatedItems(List<String> values, String label) {
    if (values == null) return List.of();
    if (values.size() > 10) throw new IllegalArgumentException(label + "最多 10 条。");
    var result = new java.util.ArrayList<String>();
    for (String value : values) {
      if (value == null || value.isBlank() || value.strip().length() > 300)
        throw new IllegalArgumentException(label + "每条需为 1 至 300 字。");
      result.add(value.strip());
    }
    return List.copyOf(result);
  }
}
