package dev.workbench.framework.control;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.EventType;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.RoleBudget;
import dev.workbench.domain.RunLimits;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.workflow.plan.WorkflowNodeBudget;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 单次运行的计数器与调用状态；执行时长仅计算进程运行期间。 */
public final class RunControl {
  private static final long MODEL_RETRY_DELAY_MS = 1000;
  private final RunControlStore repository;
  private final Set<String> paused, cancelled;

  /** 因断点让出线程的运行：恢复时必须重新派发，普通暂停只唤醒等待线程。 */
  private final Set<String> yielded;

  private final String runId;
  private final long previousMs;
  private final long startedNanos = System.nanoTime();
  private final Object pauseMonitor;
  private long pausedNanos, pauseStartedNanos;

  /** 人工审批等待时长：不计入运行执行时间，避免等待审批触发 TIME_LIMIT。 */
  private long approvalNanos, approvalStartedNanos;

  /** 最近一次落库的运行状态：只在状态真正迁移时写库，消除高频检查造成的写风暴与 PAUSED 覆写。 */
  private volatile RunStatus lastSavedStatus;

  private final Integer maxRounds, maxTools, maxPerTool, maxSeconds;
  private final Long maxReportedTokens;
  private final Integer maxIdenticalTools;
  private final Integer maxAgentRounds, maxAgentTools;
  private final Map<AgentRole, RoleBudget> roleBudgets;
  private final Map<ToolKind, Integer> toolBudgets;
  private final AtomicInteger rounds = new AtomicInteger();
  private final AtomicInteger tools = new AtomicInteger();

  /** 并行节点各自持有模型/工具调用槽，避免共享单个 callId 互相覆盖。 */
  private final Map<String, CallSlot> modelSlots = new ConcurrentHashMap<>();

  private final Map<String, CallSlot> toolSlots = new ConcurrentHashMap<>();

  public RunControl(
      String runId,
      RunSnapshot run,
      RunControlStore repository,
      Object pauseMonitor,
      Set<String> paused,
      Set<String> cancelled,
      Set<String> yielded,
      Map<AgentRole, RoleBudget> roleBudgets,
      Map<ToolKind, Integer> toolBudgets) {
    this.yielded = yielded == null ? ConcurrentHashMap.newKeySet() : yielded;
    this.runId = runId;
    this.lastSavedStatus = run.status();
    this.repository = repository;
    this.pauseMonitor = pauseMonitor;
    this.paused = paused;
    this.cancelled = cancelled;
    this.rounds.set(run.rounds());
    this.tools.set(run.tools());
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

  /** 兼容未共享让出标记的装配。 */
  public RunControl(
      String runId,
      RunSnapshot run,
      RunControlStore repository,
      Object pauseMonitor,
      Set<String> paused,
      Set<String> cancelled,
      Map<AgentRole, RoleBudget> roleBudgets,
      Map<ToolKind, Integer> toolBudgets) {
    this(
        runId,
        run,
        repository,
        pauseMonitor,
        paused,
        cancelled,
        ConcurrentHashMap.newKeySet(),
        roleBudgets,
        toolBudgets);
  }

  /** 本运行已累计的模型轮次，包含恢复时继承的消耗。 */
  public int rounds() {
    return rounds.get();
  }

  /**
   * 在节点断点处主动挂起运行：落库暂停状态与断点原因后**立即让出调度线程**。
   *
   * <p>旧实现先标记暂停再进入协作式等待，断点会长期占用固定大小的执行线程池，且通用 PAUSED 事件 先于断点原因落库。现在：状态与原因在同一状态边界内落库，随后抛出 {@link
   * PauseYield} 让调度器退出， 运行保持 {@code PAUSED}；用户点「继续」时由恢复路径重新派发。
   */
  public void pauseAtBreakpoint(Object stateLock, String reason) {
    atBoundary(
        stateLock,
        () -> {
          paused.add(runId);
          if (pauseStartedNanos == 0) pauseStartedNanos = System.nanoTime();
          save(RunStatus.PAUSED, null, null);
          lastSavedStatus = RunStatus.PAUSED;
          repository.event(runId, EventType.PAUSED, reason);
          yielded.add(runId);
          return null;
        });
    throw new PauseYield(runId);
  }

  /** 进入人工审批等待：暂停计时累积到审批等待，不计入运行执行时间。 */
  public void beginApprovalWait() {
    synchronized (pauseMonitor) {
      if (approvalStartedNanos == 0) approvalStartedNanos = System.nanoTime();
    }
  }

  /** 结束人工审批等待：多次嵌套调用只按最外层计量。 */
  public void endApprovalWait() {
    synchronized (pauseMonitor) {
      if (approvalStartedNanos == 0) return;
      approvalNanos += System.nanoTime() - approvalStartedNanos;
      approvalStartedNanos = 0;
    }
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
    return tools.get();
  }

  /** 使用单调时钟计算运行期间的累计时长，暂停时间不计入。 */
  public long elapsed() {
    synchronized (pauseMonitor) {
      long current = System.nanoTime();
      long totalPaused = pausedNanos + (pauseStartedNanos == 0 ? 0 : current - pauseStartedNanos);
      long totalApproval =
          approvalNanos + (approvalStartedNanos == 0 ? 0 : current - approvalStartedNanos);
      totalPaused += totalApproval;
      return previousMs + TimeUnit.NANOSECONDS.toMillis(current - startedNanos - totalPaused);
    }
  }

  public void save(RunStatus status, String reason, String result) {
    repository.update(runId, status, reason, result, rounds.get(), tools.get(), elapsed());
  }

  /** 保存终态时同时写入机器可读停止原因，供阻塞分类使用。 */
  public void save(RunStatus status, String reason, String result, StopCause cause) {
    repository.update(runId, status, reason, result, rounds.get(), tools.get(), elapsed(), cause);
  }

  /** 合作式检查暂停、取消与时间限额；不强制撤销已发出的外部请求。 */
  public void check() {
    synchronized (pauseMonitor) {
      if (paused.contains(runId) && pauseStartedNanos == 0) {
        pauseStartedNanos = System.nanoTime();
        save(RunStatus.PAUSED, null, null);
        lastSavedStatus = RunStatus.PAUSED;
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
    if (maxSeconds != null && elapsed() >= maxSeconds * 1000L)
      throw new StopExecution(StopCause.TIME_LIMIT, "运行时间达到上限。");
    // 仅当状态确实从暂停/终态回到运行中时才写库。
    if (lastSavedStatus != RunStatus.RUNNING) {
      save(RunStatus.RUNNING, null, null);
      lastSavedStatus = RunStatus.RUNNING;
    }
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

  /**
   * 原子占用一次模型轮次：判限与递增在同一 CAS 循环内完成。
   *
   * <p>旧实现先 `get()` 判限再 `incrementAndGet()`，并行节点可同时通过检查后一起递增，击穿硬限额。
   */
  private void incrementRounds() {
    while (true) {
      int current = rounds.get();
      if (maxRounds != null && current >= maxRounds)
        throw new StopExecution(StopCause.MODEL_ROUND_LIMIT, "Agent 轮次达到上限。");
      if (rounds.compareAndSet(current, current + 1)) return;
    }
  }

  /** 原子占用一次工具调用：判限与递增在同一 CAS 循环内完成，避免并行击穿上限。 */
  private void incrementTools() {
    while (true) {
      int current = tools.get();
      if (maxTools != null && current >= maxTools)
        throw new StopExecution(StopCause.TOOL_CALL_LIMIT, "工具调用次数达到上限。");
      if (tools.compareAndSet(current, current + 1)) return;
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
    incrementRounds();
    // 仅当状态确实从暂停/终态回到运行中时才写库。
    if (lastSavedStatus != RunStatus.RUNNING) {
      save(RunStatus.RUNNING, null, null);
      lastSavedStatus = RunStatus.RUNNING;
    }
  }

  private void checkModelBudget(NodePolicy node) {
    checkTokenBudget();
    if (maxRounds != null && rounds.get() >= maxRounds)
      throw new StopExecution(StopCause.MODEL_ROUND_LIMIT, "Agent 轮次达到上限。");
    if (node != null
        && node.budget().maxModelCalls() != null
        && repository.countNodeModelCalls(runId, node.nodeId()) >= node.budget().maxModelCalls())
      throw new StopExecution(StopCause.NODE_MODEL_LIMIT, "当前节点模型调用次数达到上限。");
  }

  /** 创建新尝试前先确认原节点和运行预算还允许一次模型调用。 */
  public boolean hasModelRetryBudget(NodePolicy node) {
    if (maxSeconds != null && elapsed() + MODEL_RETRY_DELAY_MS >= maxSeconds * 1000L) return false;
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
      dev.workbench.framework.concurrent.BoundedWait.pauseInterruptibly(Math.min(100, remaining));
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
    Integer specificLimit = toolBudgets.get(kind);
    if (maxPerTool != null || specificLimit != null) {
      int usedForTool = repository.countToolCalls(runId, kind);
      if (maxPerTool != null && usedForTool >= maxPerTool)
        throw new StopExecution(StopCause.PER_TOOL_LIMIT, "单工具调用次数达到上限：" + kind.recordName());
      if (specificLimit != null && usedForTool >= specificLimit)
        throw new StopExecution(
            StopCause.SPECIFIC_TOOL_LIMIT, "该工具的专属调用次数达到上限：" + kind.recordName());
    }
    if (maxIdenticalTools != null
        && repository.consecutiveToolAttempts(runId, kind, fingerprint, maxIdenticalTools)
            >= maxIdenticalTools)
      throw new StopExecution(StopCause.REPEATED_TOOL_LIMIT, "连续相同工具调用达到上限：" + kind.recordName());
    incrementTools();
    // 仅当状态确实从暂停/终态回到运行中时才写库。
    if (lastSavedStatus != RunStatus.RUNNING) {
      save(RunStatus.RUNNING, null, null);
      lastSavedStatus = RunStatus.RUNNING;
    }
  }

  /** 角色来自已保存的实例，不接受模型提供的角色名称。 */
  private RoleBudget roleBudget(String agentId) {
    return roleBudgets.isEmpty() ? null : roleBudgets.get(repository.agentRole(runId, agentId));
  }

  private UUID startTool(String agentId, ToolKind kind, int version, String fingerprint) {
    var slot = new CallSlot(UUID.randomUUID().toString(), System.nanoTime());
    toolSlots.put(slotKey(agentId), slot);
    if (maxIdenticalTools == null)
      repository.startTool(slot.id, runId, agentId, kind.recordName(), version);
    else repository.startTool(slot.id, runId, agentId, kind, version, fingerprint);
    return UUID.fromString(slot.id);
  }

  public void finishTool(InvocationStatus status, String result, String error) {
    finishTool((String) null, status, result, error, null);
  }

  /** 完成指定 Agent 的工具调用，避免并行节点结算彼此的 callId。 */
  public void finishTool(String agentId, InvocationStatus status, String result, String error) {
    finishTool(agentId, status, result, error, null);
  }

  /** 完成工具调用时一并保存只读工具观察到的资源版本。 */
  public void finishTool(
      InvocationStatus status, String result, String error, String resourceVersion) {
    finishTool((String) null, status, result, error, resourceVersion);
  }

  public void finishTool(
      String agentId,
      InvocationStatus status,
      String result,
      String error,
      String resourceVersion) {
    var slot = toolSlots.get(slotKey(agentId));
    if (slot == null || slot.id == null) return;
    repository.finishTool(
        slot.id,
        status,
        result,
        error,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - slot.startedNanos),
        resourceVersion);
    slot.id = null;
  }

  private void startModel(String agentId, ModelKind model) {
    var slot = new CallSlot(UUID.randomUUID().toString(), System.nanoTime());
    modelSlots.put(slotKey(agentId), slot);
    repository.startModel(slot.id, runId, agentId, model);
  }

  /** 当前在途模型调用；事件写入时用于结构化关联，没有则为空。 */
  public UUID currentModelCallId() {
    return currentModelCallId(null);
  }

  public UUID currentModelCallId(String agentId) {
    var slot = modelSlots.get(slotKey(agentId));
    return slot == null || slot.id == null ? null : UUID.fromString(slot.id);
  }

  /** 最近一次模型调用；用于调用已结算后到达的返回事件。 */
  public UUID lastModelCallId() {
    return lastModelCallId(null);
  }

  public UUID lastModelCallId(String agentId) {
    var slot = modelSlots.get(slotKey(agentId));
    String id = slot == null ? null : slot.lastId != null ? slot.lastId : slot.id;
    return id == null ? null : UUID.fromString(id);
  }

  /** 当前在途工具调用；事件写入时用于结构化关联，没有则为空。 */
  public UUID currentToolCallId() {
    return currentToolCallId(null);
  }

  public UUID currentToolCallId(String agentId) {
    var slot = toolSlots.get(slotKey(agentId));
    return slot == null || slot.id == null ? null : UUID.fromString(slot.id);
  }

  public void finishModel(InvocationStatus status, Integer input, Integer output, String error) {
    finishModel(null, status, input, output, error);
  }

  public void finishModel(
      String agentId, InvocationStatus status, Integer input, Integer output, String error) {
    var slot = modelSlots.get(slotKey(agentId));
    if (slot == null || slot.id == null) return;
    repository.finishModel(
        slot.id,
        status,
        input,
        output,
        error,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - slot.startedNanos));
    slot.id = null;
  }

  private static String slotKey(String agentId) {
    return agentId == null ? "" : agentId;
  }

  /** 单个 Agent 的调用槽；同一 Agent 的模型/工具调用仍按 ReAct 顺序串行。 */
  private static final class CallSlot {
    private String id;
    private final long startedNanos;
    private final String lastId;

    private CallSlot(String id, long startedNanos) {
      this(id, startedNanos, id);
    }

    private CallSlot(String id, long startedNanos, String lastId) {
      this.id = id;
      this.startedNanos = startedNanos;
      this.lastId = lastId;
    }
  }
}
