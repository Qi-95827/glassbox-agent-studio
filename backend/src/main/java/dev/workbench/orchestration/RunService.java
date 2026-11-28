package dev.workbench.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.bug.BugIntakeService;
import dev.workbench.bug.BugService;
import dev.workbench.framework.agent.execution.AgentExecutor;
import dev.workbench.framework.context.AgentContextAssembler;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunCheckpointStore;
import dev.workbench.store.RunCountStore;
import dev.workbench.store.RunCreationStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import dev.workbench.workspace.RunHandoverService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import dev.workbench.workspace.WorkspaceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;
import dev.workbench.planning.EntryPolicyService;
import dev.workbench.planning.EntryRouteService;

/** 兼容门面：保留既有运行 API，实际职责委派给创建、派发、命令与执行服务。 */
@Service
public class RunService implements dev.workbench.planning.CandidateRunDispatcher {
  private final RunStateStore runs;
  private final WorkflowScheduler workflows;
  private final ExecutorService executor;
  /** 按运行标识分段的进程内锁；同一运行恒定命中同一槽位，不同运行可真正并发。 */
  private final RunLockRegistry locks = new RunLockRegistry();
  private final RunCreationService creations;
  private final RunDispatchService dispatcher;
  private final RunCommandService commands;

  /** 诊断计数：只统计真正提交给执行队列的派发次数。 */
  volatile int dispatchInvocationCount;

  /** 测试注入点：在派发前同步观察数据库提交结果。 */
  volatile Runnable beforeDispatch = () -> {};

  /**
   * 生产装配。
   *
   * <p>收口包 G-2：遗留 {@code DirectRunExecutor}/{@code RunReviewCoordinator} 已下线，这里不再持有任何直跑专属协作对象；
   * 参数列表保持原样以兼容既有装配点与兼容构造器（部分参数仅由下游协作者消费）。
   */
  @org.springframework.beans.factory.annotation.Autowired
  public RunService(
      RunStateStore runs,
      TaskRecordService tasks,
      RunCreationStore creation,
      AgentAssemblyRecordService agents,
      RunEvidenceRecordService evidence,
      RunCheckpointStore checkpointStore,
      RunCountStore counts,
      dev.workbench.framework.control.RunControlStore controls,
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
      WorkspaceAuthorizationService workspaceAuthorization,
      dev.workbench.modelconnection.ModelConnectionService modelConnections,
      @org.springframework.beans.factory.annotation.Qualifier("workflowRunExecutor")
          ExecutorService executor,
      dev.workbench.tool.ToolApprovalService approvals,
      org.springframework.context.ApplicationEventPublisher publisher) {
    this.runs = runs;
    this.workflows = workflows;
    this.executor = executor;
    RunDispatchService dispatch = new RunDispatchService(runs, creation, executor, this);
    this.commands =
        new RunCommandService(locks, runs, evidence, handovers, workflows, dispatch);
    RunExecutionEngine engine =
        new RunExecutionEngine(runs, tasks, controls, workflows, commands, locks);
    dispatch.setEngine(engine);
    this.dispatcher = dispatch;
    this.creations =
        new RunCreationService(
            taskCreations,
            workspaces,
            bugs,
            json,
            entryPolicies,
            entryRoutes,
            workspaceAuthorization,
            workflows,
            dispatch,
            modelConnections);
  }

  /** 兼容只构造队列控制边界的单元测试；入口路由、目录授权和费用在完整上下文中由 Spring 注入。 */
  public RunService(
      RunStateStore runs,
      TaskRecordService tasks,
      RunCreationStore creation,
      AgentAssemblyRecordService agents,
      RunEvidenceRecordService evidence,
      RunCheckpointStore checkpointStore,
      RunCountStore counts,
      dev.workbench.framework.control.RunControlStore controls,
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
      ExecutorService executor) {
    this(
        runs,
        tasks,
        creation,
        agents,
        evidence,
        checkpointStore,
        counts,
        controls,
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
        null,
        null,
        executor,
        null,
        null);
  }

  /** 重启后不能假设此前未结束的调用已经成功。 */
  @PostConstruct
  void initialize() {
    runs.interruptRunning();
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  /** 创建一个个人示例任务并排队；不触发真实代码工具。 */
  public String create(CreateTaskRequest request) {
    return creations.create(request);
  }

  /** 一个计划版本只接受一次确认。 */
  public void approvePlan(String runId, java.util.UUID planVersionId) {
    synchronized (locks.lockFor(runId)) {
      workflows.approvePlan(runId, planVersionId);
      dispatcher.dispatchAfterCommit(runId);
    }
  }

  /** 补充接受后从安全边界重新排队；沿用与首次派发相同的单线程队列与拒绝语义。 */
  public void dispatchContinuation(String runId) {
    dispatcher.dispatchContinuation(runId);
  }

  /** 候选确认事务提交后派发；调用方不得在未提交事务中调用。 */
  @Override
  public void dispatchConfirmed(String runId) {
    dispatcher.dispatchConfirmed(runId);
  }

  /** 排队中的任务立即终止；运行中的任务通过信号协作取消。 */
  public void cancel(String id) {
    commands.cancel(id);
  }

  /** 请求在下一个协作检查点暂停；在途模型请求可能仍会结束并产生费用。 */
  public void pause(String id) {
    commands.pause(id);
  }

  /** 恢复当前进程内的暂停；进程重启后的恢复仍使用检查点。 */
  public void resume(String id) {
    commands.resume(id);
  }

  /** 从有效快照创建新运行，不覆盖历史。此处的同步只覆盖本进程； 外部副作用和分布式并发恢复尚未实现。 */
  public String recover(String oldId, String checkpointId) {
    return commands.recover(oldId, checkpointId);
  }
}
