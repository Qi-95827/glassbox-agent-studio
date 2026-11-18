package dev.workbench.framework.control;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.EventType;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.RoleBudget;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.workflow.plan.WorkflowNodeBudget;
import dev.workbench.task.RunLimits;
import dev.workbench.task.RunRepository;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 单次运行的计数器与调用状态；执行时长仅计算进程运行期间。 */
public final class RunControl {
  private static final long MODEL_RETRY_DELAY_MS = 1000;
  private final RunRepository repository;
  private final Set<String> paused, cancelled;
  private final String runId;
  private final long previousMs;
  private final long startedNanos = System.nanoTime();
  private final Object pauseMonitor;
  private long pausedNanos, pauseStartedNanos;
  private final int maxRounds, maxTools, maxPerTool, maxSeconds;
  private final Long maxReportedTokens;
  private final Integer maxIdenticalTools;
  private final Integer maxAgentRounds, maxAgentTools;
  private final Map<AgentRole, RoleBudget> roleBudgets;
  private final Map<ToolKind, Integer> toolBudgets;
  private int rounds, tools;
  private String toolId, modelId, lastModelId;
  private long toolStartedNanos, modelStartedNanos;

  public RunControl(
      String runId,
      RunSnapshot run,
      RunRepository repository,
      Object pauseMonitor,
      Set<String> paused,
      Set<String> cancelled,
      Map<AgentRole, RoleBudget> roleBudgets,
      Map<ToolKind, Integer> toolBudgets) {
    this.runId = runId;
    this.repository = repository;
    this.pauseMonitor = pauseMonitor;
    this.paused = paused;
    this.cancelled = cancelled;
    this.rounds = run.rounds();
    this.tools = run.tools();
    this.previousMs = run.elapsedMs();
    this.maxRounds = run.maxRounds();
    this.maxTools = run.maxTools();
    this.maxPerTool = run.maxPerTool();
    this.maxReportedTokens = run.maxReportedTokens();
    this.maxIdenticalTools = run.maxIdenticalTools();
    this.maxAgentRounds = run.maxAgentRounds();
    this.maxAgentTools = run.maxAgentTools();
    this.maxSeconds = run.maxSeconds();
    this.roleBudgets = Map.copyOf(roleBudgets);
    this.toolBudgets = Map.copyOf(toolBudgets);
  }

  /** 本运行已累计的模型轮次，包含恢复时继承的消耗。 */
  public int rounds() {
    return rounds;
  }

  /** 抓取此次装配对应的配置，不以剩余额度替代政策上限。 */
  public AgentBudgetPolicy budgetPolicy(AgentRole profile, NodePolicy node) {
    return new AgentBudgetPolicy(
        1,
        new RunLimits(
            maxRounds,
            maxTools,
            maxPerTool,
            maxReportedTokens,
            maxIdenticalTools,
            maxSeconds,
            maxAgentRounds,
            maxAgentTools),
        roleBudgets.get(profile),
        toolBudgets,
        node == null ? null : node.budget());
  }

  /** 调度器认领后绑定；由运行持久化的节点读取限额。 */
  public record NodePolicy(UUID nodeId, WorkflowNodeBudget budget) {}

  /** 本运行已累计的工具次数，包含恢复时继承的消耗。 */
  public int tools() {
    return tools;
  }

  /** 使用单调时钟计算运行期间的累计时长，暂停时间不计入。 */
  public long elapsed() {
    synchronized (pauseMonitor) {
      long current = System.nanoTime();
      long totalPaused = pausedNanos + (pauseStartedNanos == 0 ? 0 : current - pauseStartedNanos);
      return previousMs + TimeUnit.NANOSECONDS.toMillis(current - startedNanos - totalPaused);
    }
  }

  public void save(RunStatus status, String reason, String result) {
    repository.update(runId, status, reason, result, rounds, tools, elapsed());
  }

  /** 合作式检查暂停、取消与时间限额；不强制撤销已发出的外部请求。 */
  public void check() {
    synchronized (pauseMonitor) {
      if (paused.contains(runId) && pauseStartedNanos == 0) {
        pauseStartedNanos = System.nanoTime();
        save(RunStatus.PAUSED, null, null);
        repository.event(runId, EventType.PAUSED, "已暂停新增动作；在途外部请求可能仍在结束。");
      }
      while (paused.contains(runId) && !cancelled.contains(runId)) {
        try {
          pauseMonitor.wait(200);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new StopExecution(StopCause.INTERRUPTED, "暂停时进程中断。");
        }
      }
      if (pauseStartedNanos != 0) {
        pausedNanos += System.nanoTime() - pauseStartedNanos;
        pauseStartedNanos = 0;
      }
    }
    if (cancelled.contains(runId)) throw new StopExecution(StopCause.USER_CANCELLED, "用户已取消运行。");
    if (elapsed() >= maxSeconds * 1000L) throw new StopExecution(StopCause.TIME_LIMIT, "运行时间达到上限。");
    save(RunStatus.RUNNING, null, null);
  }

  /** 在与暂停、取消相同的锁内认领或提交状态，等待暂停时不持有这把锁。 */
  public <T> T atBoundary(Object stateLock, Supplier<T> write) {
    while (true) {
      check();
      synchronized (stateLock) {
        if (!paused.contains(runId) && !cancelled.contains(runId)) return write.get();
      }
    }
  }

  /** 先检查所有适用的预算，再记录一次已启动的模型调用。 */
  public void beginModel(String agentId, ModelKind model, NodePolicy node) {
    modelCall(agentId, node);
    startModel(agentId, model);
  }

  private void modelCall(String agentId, NodePolicy node) {
    check();
    checkModelBudget(node);
    RoleBudget roleBudget = roleBudget(agentId);
    Integer roleLimit = roleBudget == null ? null : roleBudget.maxModelCalls();
    if (maxAgentRounds != null || roleLimit != null) {
      int used = repository.countAgentModelCalls(agentId);
      if (maxAgentRounds != null && used >= maxAgentRounds)
        throw new StopExecution(StopCause.AGENT_MODEL_LIMIT, "单个 Agent 模型调用次数达到上限。");
      if (roleLimit != null && used >= roleLimit)
        throw new StopExecution(StopCause.ROLE_MODEL_LIMIT, "当前角色的单实例模型调用次数达到上限。");
    }
    rounds++;
    save(RunStatus.RUNNING, null, null);
  }

  private void checkModelBudget(NodePolicy node) {
    checkTokenBudget();
    if (rounds >= maxRounds) throw new StopExecution(StopCause.MODEL_ROUND_LIMIT, "Agent 轮次达到上限。");
    if (node != null
        && node.budget().maxModelCalls() != null
        && repository.countNodeModelCalls(runId, node.nodeId()) >= node.budget().maxModelCalls())
      throw new StopExecution(StopCause.NODE_MODEL_LIMIT, "当前节点模型调用次数达到上限。");
  }

  /** 创建新尝试前先确认原节点和运行预算还允许一次模型调用。 */
  public boolean hasModelRetryBudget(NodePolicy node) {
    if (elapsed() + MODEL_RETRY_DELAY_MS >= maxSeconds * 1000L) return false;
    try {
      checkModelBudget(node);
      return true;
    } catch (StopExecution exhausted) {
      return false;
    }
  }

  /** 退避占用运行时间，且暂停、取消和截止时间仍会生效。 */
  public void waitForModelRetry() throws InterruptedException {
    long until = elapsed() + MODEL_RETRY_DELAY_MS;
    while (true) {
      check();
      long remaining = until - elapsed();
      if (remaining <= 0) return;
      Thread.sleep(Math.min(100, remaining));
    }
  }

  /** 仅依据供应商已经报告的用量；未知时保守停止，不能保证单次调用不超额。 */
  public void checkTokenBudget() {
    if (maxReportedTokens == null) return;
    var usage = repository.reportedTokens(runId);
    if (usage.unknownCalls() > 0)
      throw new StopExecution(StopCause.TOKEN_USAGE_UNKNOWN, "模型用量未知，无法继续执行已报告 Token 预算。");
    if (usage.knownTokens() >= maxReportedTokens)
      throw new StopExecution(StopCause.TOKEN_LIMIT, "已报告 Token 达到上限；单次调用可能超额。");
  }

  /** 先检查预算和重复调用策略，再记录并返回新调用的标识。 */
  public UUID beginTool(String agentId, ToolKind kind, int version, String fingerprint) {
    return beginTool(agentId, kind, version, fingerprint, null);
  }

  public UUID beginTool(
      String agentId, ToolKind kind, int version, String fingerprint, NodePolicy node) {
    toolCall(agentId, kind, fingerprint, node);
    return startTool(agentId, kind, version, fingerprint);
  }

  private void toolCall(String agentId, ToolKind kind, String fingerprint, NodePolicy node) {
    check();
    if (tools >= maxTools) throw new StopExecution(StopCause.TOOL_CALL_LIMIT, "工具调用次数达到上限。");
    if (node != null
        && node.budget().maxToolCalls() != null
        && repository.countNodeToolCalls(runId, node.nodeId()) >= node.budget().maxToolCalls())
      throw new StopExecution(StopCause.NODE_TOOL_LIMIT, "当前节点工具调用次数达到上限。");
    RoleBudget roleBudget = roleBudget(agentId);
    Integer roleLimit = roleBudget == null ? null : roleBudget.maxToolCalls();
    if (maxAgentTools != null || roleLimit != null) {
      int used = repository.countAgentToolCalls(agentId);
      if (maxAgentTools != null && used >= maxAgentTools)
        throw new StopExecution(StopCause.AGENT_TOOL_LIMIT, "单个 Agent 工具调用次数达到上限。");
      if (roleLimit != null && used >= roleLimit)
        throw new StopExecution(StopCause.ROLE_TOOL_LIMIT, "当前角色的单实例工具调用次数达到上限。");
    }
    int usedForTool = repository.countToolCalls(runId, kind);
    if (usedForTool >= maxPerTool)
      throw new StopExecution(StopCause.PER_TOOL_LIMIT, "单工具调用次数达到上限：" + kind.recordName());
    Integer specificLimit = toolBudgets.get(kind);
    if (specificLimit != null && usedForTool >= specificLimit)
      throw new StopExecution(StopCause.SPECIFIC_TOOL_LIMIT, "该工具的专属调用次数达到上限：" + kind.recordName());
    if (maxIdenticalTools != null
        && repository.consecutiveToolAttempts(runId, kind, fingerprint, maxIdenticalTools)
            >= maxIdenticalTools)
      throw new StopExecution(StopCause.REPEATED_TOOL_LIMIT, "连续相同工具调用达到上限：" + kind.recordName());
    tools++;
    save(RunStatus.RUNNING, null, null);
  }

  /** 角色来自已保存的实例，不接受模型提供的角色名称。 */
  private RoleBudget roleBudget(String agentId) {
    return roleBudgets.isEmpty() ? null : roleBudgets.get(repository.agentRole(runId, agentId));
  }

  private synchronized UUID startTool(
      String agentId, ToolKind kind, int version, String fingerprint) {
    toolId = RunRepository.id();
    toolStartedNanos = System.nanoTime();
    if (maxIdenticalTools == null)
      repository.startTool(toolId, runId, agentId, kind.recordName(), version);
    else repository.startTool(toolId, runId, agentId, kind, version, fingerprint);
    return UUID.fromString(toolId);
  }

  public synchronized void finishTool(InvocationStatus status, String result, String error) {
    finishTool(status, result, error, null);
  }

  /** 完成工具调用时一并保存只读工具观察到的资源版本。 */
  public synchronized void finishTool(
      InvocationStatus status, String result, String error, String resourceVersion) {
    if (toolId != null) {
      repository.finishTool(
          toolId,
          status,
          result,
          error,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - toolStartedNanos),
          resourceVersion);
      toolId = null;
    }
  }

  private synchronized void startModel(String agentId, ModelKind model) {
    modelId = RunRepository.id();
    lastModelId = modelId;
    modelStartedNanos = System.nanoTime();
    repository.startModel(modelId, runId, agentId, model);
  }

  /** 当前在途模型调用；事件写入时用于结构化关联，没有则为空。 */
  public synchronized UUID currentModelCallId() {
    return modelId == null ? null : UUID.fromString(modelId);
  }

  /** 最近一次模型调用；用于调用已结算后到达的返回事件。 */
  public synchronized UUID lastModelCallId() {
    return lastModelId == null ? null : UUID.fromString(lastModelId);
  }

  /** 当前在途工具调用；事件写入时用于结构化关联，没有则为空。 */
  public synchronized UUID currentToolCallId() {
    return toolId == null ? null : UUID.fromString(toolId);
  }

  public synchronized void finishModel(
      InvocationStatus status, Integer input, Integer output, String error) {
    if (modelId != null) {
      repository.finishModel(
          modelId,
          status,
          input,
          output,
          error,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - modelStartedNanos));
      modelId = null;
    }
  }
}
