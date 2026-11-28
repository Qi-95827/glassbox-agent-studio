package dev.workbench.orchestration;

import dev.workbench.domain.EventType;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.control.RunControl;
import dev.workbench.framework.tool.ToolApprovalDecision;
import dev.workbench.framework.tool.ToolRiskAssessment;
import dev.workbench.framework.trace.GlassboxModels;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.GlassboxTraceStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.task.CostService;
import dev.workbench.tool.ToolApprovalService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;

/** 将单个 Agent 的调用接入项目的运行控制与持久化记录。 */
final class ControlledAgentRuntime implements AgentRuntime {
  private final String runId;
  private final String agentId;
  private final RunControl control;
  private final AgentAssemblyRecordService assemblyRecords;
  private final RunStateStore runs;
  private final RunEvidenceRecordService evidence;
  private final RunControl.NodePolicy nodePolicy;

  /** 与预算解耦的节点标识：HITL 审批只依赖它，默认模板不需要配置 nodePolicy。 */
  private final String hitlNodeId;

  private final CostService costs;
  private final WorkspaceAuthorizationService authorization;
  private final GlassboxTraceStore traces;
  private final String locale;
  private final ToolApprovalService approvals;
  private final ApplicationEventPublisher publisher;
  private AgentAssemblySnapshot.Components assembly;
  private CostService.Reservation costReservation = CostService.Reservation.NONE;
  /** 正文与思考链各持独立的有状态脱敏器：跨帧敏感串不会从两帧缝隙里漏出去。 */
  private dev.workbench.framework.trace.StreamingMasker contentMasker =
      new dev.workbench.framework.trace.StreamingMasker();

  private dev.workbench.framework.trace.StreamingMasker reasoningMasker =
      new dev.workbench.framework.trace.StreamingMasker();
  private String lastFinishReason;

  ControlledAgentRuntime(
      String runId,
      String agentId,
      RunControl control,
      AgentAssemblyRecordService assemblyRecords,
      RunStateStore runs,
      RunEvidenceRecordService evidence) {
    this(runId, agentId, control, assemblyRecords, runs, evidence, null, null, null);
  }

  ControlledAgentRuntime(
      String runId,
      String agentId,
      RunControl control,
      AgentAssemblyRecordService assemblyRecords,
      RunStateStore runs,
      RunEvidenceRecordService evidence,
      RunControl.NodePolicy nodePolicy) {
    this(runId, agentId, control, assemblyRecords, runs, evidence, nodePolicy, null, null);
  }

  ControlledAgentRuntime(
      String runId,
      String agentId,
      RunControl control,
      AgentAssemblyRecordService assemblyRecords,
      RunStateStore runs,
      RunEvidenceRecordService evidence,
      RunControl.NodePolicy nodePolicy,
      CostService costs) {
    this(runId, agentId, control, assemblyRecords, runs, evidence, nodePolicy, costs, null);
  }

  ControlledAgentRuntime(
      String runId,
      String agentId,
      RunControl control,
      AgentAssemblyRecordService assemblyRecords,
      RunStateStore runs,
      RunEvidenceRecordService evidence,
      RunControl.NodePolicy nodePolicy,
      CostService costs,
      WorkspaceAuthorizationService authorization) {
    this(
        runId,
        agentId,
        control,
        assemblyRecords,
        runs,
        evidence,
        nodePolicy,
        costs,
        authorization,
        null);
  }

  ControlledAgentRuntime(
      String runId,
      String agentId,
      RunControl control,
      AgentAssemblyRecordService assemblyRecords,
      RunStateStore runs,
      RunEvidenceRecordService evidence,
      RunControl.NodePolicy nodePolicy,
      CostService costs,
      WorkspaceAuthorizationService authorization,
      GlassboxTraceStore traces) {
    this(
        runId,
        agentId,
        control,
        assemblyRecords,
        runs,
        evidence,
        nodePolicy,
        costs,
        authorization,
        traces,
        null);
  }

  ControlledAgentRuntime(
      String runId,
      String agentId,
      RunControl control,
      AgentAssemblyRecordService assemblyRecords,
      RunStateStore runs,
      RunEvidenceRecordService evidence,
      RunControl.NodePolicy nodePolicy,
      CostService costs,
      WorkspaceAuthorizationService authorization,
      GlassboxTraceStore traces,
      String locale,
      ToolApprovalService approvals,
      ApplicationEventPublisher publisher) {
    this(
        runId,
        agentId,
        control,
        assemblyRecords,
        runs,
        evidence,
        nodePolicy,
        costs,
        authorization,
        traces,
        locale,
        approvals,
        publisher,
        null);
  }

  ControlledAgentRuntime(
      String runId,
      String agentId,
      RunControl control,
      AgentAssemblyRecordService assemblyRecords,
      RunStateStore runs,
      RunEvidenceRecordService evidence,
      RunControl.NodePolicy nodePolicy,
      CostService costs,
      WorkspaceAuthorizationService authorization,
      GlassboxTraceStore traces,
      String locale) {
    this(
        runId,
        agentId,
        control,
        assemblyRecords,
        runs,
        evidence,
        nodePolicy,
        costs,
        authorization,
        traces,
        locale,
        null,
        null);
  }

  ControlledAgentRuntime(
      String runId,
      String agentId,
      RunControl control,
      AgentAssemblyRecordService assemblyRecords,
      RunStateStore runs,
      RunEvidenceRecordService evidence,
      RunControl.NodePolicy nodePolicy,
      CostService costs,
      WorkspaceAuthorizationService authorization,
      GlassboxTraceStore traces,
      String locale,
      ToolApprovalService approvals,
      ApplicationEventPublisher publisher,
      String hitlNodeId) {
    this.runId = Objects.requireNonNull(runId);
    this.agentId = Objects.requireNonNull(agentId);
    this.control = Objects.requireNonNull(control);
    this.assemblyRecords = Objects.requireNonNull(assemblyRecords);
    this.runs = Objects.requireNonNull(runs);
    this.evidence = Objects.requireNonNull(evidence);
    this.nodePolicy = nodePolicy;
    this.costs = costs;
    this.authorization = authorization;
    this.traces = traces;
    this.locale = locale;
    this.approvals = approvals;
    this.publisher = publisher;
    this.hitlNodeId =
        hitlNodeId == null || hitlNodeId.isBlank()
            ? (nodePolicy == null ? null : nodePolicy.nodeId().toString())
            : hitlNodeId;
  }

  @Override
  public void beforeModel(ModelKind model) {
    // 每次模型调用都是新的流：清空上一轮的滞留窗口与结束原因，避免跨调用拼接出「拼错的」敏感串或错用上一轮截断标记。
    contentMasker = new dev.workbench.framework.trace.StreamingMasker();
    reasoningMasker = new dev.workbench.framework.trace.StreamingMasker();
    lastFinishReason = null;
    if (assembly == null || ModelKind.forMode(assembly.model().mode()) != model)
      throw new IllegalStateException("模型来源与本次 Agent 装配记录不一致。");
    if (costs != null) costReservation = costs.reserve(runId, model);
    try {
      control.beginModel(agentId, model, nodePolicy);
    } catch (RuntimeException rejected) {
      if (costs != null) {
        costs.cancel(costReservation);
        costReservation = CostService.Reservation.NONE;
      }
      throw rejected;
    }
  }

  @Override
  public synchronized void modelFinishReason(String finishReason) {
    this.lastFinishReason = finishReason;
  }

  /** 失败/取消时释放预留；先在同一把锁内重查，避免与正常结算并发造成双扣或漏放。 */
  @Override
  public synchronized void modelFailed() {
    // 失败/取消也要冲刷：已到达的尾部同样不能以半截敏感串的形式留在滞留窗口里。
    lastFinishReason = null;
    flushStreamMaskers(true);
    if (costs != null && costReservation != CostService.Reservation.NONE) {
      costs.cancel(costReservation);
      costReservation = CostService.Reservation.NONE;
    }
  }

  @Override
  public synchronized void modelResult(Integer input, Integer output) {
    boolean truncated =
        dev.workbench.framework.trace.SensitiveDataMasker.isTruncatedFinishReason(lastFinishReason);
    lastFinishReason = null;
    flushStreamMaskers(truncated);
    control.finishModel(agentId, InvocationStatus.COMPLETED, input, output, null);
    CostService.Settlement settlement = CostService.Settlement.NONE;
    if (costs != null) {
      settlement = costs.settle(costReservation, input, output);
      costReservation = CostService.Reservation.NONE;
    }
    if (settlement.overBudgetMicros() > 0)
      throw new dev.workbench.framework.control.StopExecution(
          dev.workbench.domain.StopCause.COST_LIMIT,
          "实际费用已超过硬预算：结算 "
              + settlement.settledMicros()
              + "，上限 "
              + settlement.maxCostMicros()
              + " 微单位；超支 "
              + settlement.overBudgetMicros()
              + " 已记账。");
    if (settlement.unknown())
      throw new dev.workbench.framework.control.StopExecution(
          dev.workbench.domain.StopCause.TOKEN_USAGE_UNKNOWN,
          "供应方未报告实际 Token 用量；费用硬预算保留原预留并停止后续调用。");
    control.checkTokenBudget();
  }

  @Override
  public Integer maxOutputTokens() {
    return costReservation.outputTokenUpperBound();
  }

  /**
   * 后台辅助调用（如 L2 上下文摘要）的用量计入当前运行的费用台账。
   *
   * <p>不复用 {@link #beforeModel}/{@link #modelResult}：那对方法维护当前 Agent 的调用开始/结算配对，
   * 摘要调用与主调用并不成对，复用会错位关闭主调用的用量。这里单独预留并结算，只影响费用账本。
   */
  @Override
  public void auxiliaryModelUsage(Integer input, Integer output) {
    if (costs == null) return;
    var reservation = costs.reserve(runId, auxiliaryModelKind());
    if (reservation == null || reservation.id() == null) return;
    var settlement = costs.settle(reservation, input, output);
    if (settlement.overBudgetMicros() > 0)
      throw new dev.workbench.framework.control.StopExecution(
          dev.workbench.domain.StopCause.COST_LIMIT, "上下文摘要调用的实际费用已超出硬预算，已停止后续调用。");
  }

  /** 辅助调用按当前装配的模型来源计价；装配尚未完成时按内置模拟模型处理。 */
  private ModelKind auxiliaryModelKind() {
    return assembly == null ? ModelKind.MOCK : ModelKind.forMode(assembly.model().mode());
  }

  @Override
  public void verifyModelInput(String input) {
    Integer bound = costReservation.inputTokenUpperBound();
    if (bound == null) return;
    int bytes = input == null ? 0 : input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    if (bytes > bound) {
      if (costs != null) costs.cancel(costReservation);
      costReservation = CostService.Reservation.NONE;
      throw new dev.workbench.framework.control.StopExecution(
          dev.workbench.domain.StopCause.COST_LIMIT,
          "模型输入可能超过价格版本的 Token 上界：" + bytes + " 字节 > " + bound + "；本次调用未派发。");
    }
  }

  @Override
  public void assemblyResolved(AgentAssemblySnapshot.Components components) {
    var snapshot =
        new AgentAssemblySnapshot(
            AgentAssemblySnapshot.VERSION,
            components,
            control.budgetPolicy(components.authorization().profile(), nodePolicy));
    assemblyRecords.recordAgentAssembly(runId, agentId, snapshot);
    assembly = components;
  }

  @Override
  public UUID beforeTool(ToolKind tool, int version, String fingerprint) {
    if (assembly == null || !Objects.equals(assembly.tools().get(tool), version))
      throw new IllegalStateException("工具版本与本次 Agent 装配记录不一致。");
    if (authorization != null)
      authorization.requireCurrent(runs.runSnapshot(runId).taskId().toString());
    return control.beginTool(agentId, tool, version, fingerprint, nodePolicy);
  }

  @Override
  public void check() {
    control.check();
  }

  @Override
  public void event(EventType type, String message) {
    var ref = evidence.eventRefForAgent(runId, agentId);
    // 调用标识只在服务端已创建对应记录后写入；旧调用或拒绝前事件保持空值。
    if (type == EventType.MODEL_STARTED)
      ref = ref.withModelCall(control.currentModelCallId(agentId));
    else if (type == EventType.MODEL_FINISHED)
      ref = ref.withModelCall(control.lastModelCallId(agentId));
    else if (type == EventType.TOOL_STARTED
        || type == EventType.TOOL_FINISHED
        || type == EventType.TOOL_REJECTED)
      ref = ref.withToolCall(control.currentToolCallId(agentId));
    evidence.event(runId, type, message, ref);
  }

  @Override
  public void toolResult(String result) {
    toolResult(result, null);
  }

  @Override
  public void toolResult(String result, String resourceVersion) {
    control.finishTool(agentId, InvocationStatus.COMPLETED, result, null, resourceVersion);
    // 工具结束时框架会话未必形成一致快照，不能视为系统检查点。
  }

  @Override
  public void modelTrace(GlassboxModels.ModelCallPayload payload) {
    if (traces == null || payload == null) return;
    UUID callId = control.lastModelCallId(agentId);
    if (callId == null) callId = control.currentModelCallId(agentId);
    if (callId != null) traces.recordModelPayload(runId, callId.toString(), payload);
  }

  @Override
  public void modelDelta(String text) {
    publishTransient("MODEL_DELTA", contentMasker.accept(text), java.util.Map.of());
  }

  @Override
  public void modelReasoningDelta(String text) {
    publishTransient("MODEL_REASONING_DELTA", reasoningMasker.accept(text), java.util.Map.of());
  }

  @Override
  public void toolGateProgress(
      String toolName, dev.workbench.framework.trace.GlassboxModels.GatewayCheck check) {
    if (check == null) return;
    var detail = new java.util.LinkedHashMap<String, Object>();
    detail.put("gate", check.gate());
    detail.put("label", check.label());
    detail.put("status", check.status());
    detail.put("detail", check.detail());
    publishTransient("TOOL_GATE_PROGRESS", toolName, detail);
  }

  /**
   * 推送一条瞬态 SSE 帧。
   *
   * <p>没有订阅者或未装配事件发布器时静默跳过：瞬态帧属于观感增强，绝不能因为推不出去
   * 就影响模型调用或安全检查本身。空的正文片段同样跳过，减少无意义帧。
   */
  /** 冲刷正文与思考链的滞留窗口；正常完成走完整脱敏，异常中断额外执行半截凭据兜底；重复调用安全。 */
  private void flushStreamMaskers(boolean aborted) {
    String content = aborted ? contentMasker.flushFinal() : contentMasker.flush();
    if (!content.isEmpty()) publishTransient("MODEL_DELTA", content, java.util.Map.of());
    String reasoning = aborted ? reasoningMasker.flushFinal() : reasoningMasker.flush();
    if (!reasoning.isEmpty())
      publishTransient("MODEL_REASONING_DELTA", reasoning, java.util.Map.of());
  }

  /** 实时帧外发前的统一清洗：SSE 与落库快照共用同一口径，避免「正文已脱敏、实时流却是原文」。 */
  static String maskTransientText(String text) {
    return dev.workbench.framework.trace.SensitiveDataMasker.mask(text);
  }

  private void publishTransient(
      String kind, String text, java.util.Map<String, Object> detail) {
    if (publisher == null || text == null || text.isEmpty()) return;
    UUID run;
    try {
      run = UUID.fromString(runId);
    } catch (IllegalArgumentException notARunId) {
      // 瞬态帧按运行标识路由；非 UUID 标识只可能来自测试桩或历史夹具。
      // 直接跳过推送——绝不因为「推不出去」打断真实的工具执行与预算结算。
      return;
    }
    var safeDetail = new java.util.LinkedHashMap<String, Object>();
    if (detail != null)
      detail.forEach(
          (key, value) -> {
            if (value == null) return;
            // 实时帧同样是外发通道：字符串型附加信息里的密钥/令牌必须与落库快照一致地脱敏。
            safeDetail.put(
                key,
                value instanceof String valueText ? maskTransientText(valueText) : value);
          });
    // 模型增量文本在进入 SSE 之前就脱敏；模型可能把 Key 原样吐进回答或思考链。
    String safeText = maskTransientText(text);
    publisher.publishEvent(
        new dev.workbench.framework.event.RunStreamTransient(
            run, kind, safeText, hitlNodeId, safeDetail, java.time.Instant.now()));
  }

  @Override
  public ToolApprovalDecision approvalDecision(
      String toolName, int version, String argsJson, ToolRiskAssessment risk) {
    if (approvals == null || hitlNodeId == null)
      return ToolApprovalDecision.reject("当前运行没有可用的动态审批通道，高危工具不会自动执行。");
    java.util.UUID node;
    try {
      node = java.util.UUID.fromString(hitlNodeId);
    } catch (IllegalArgumentException invalid) {
      return ToolApprovalDecision.reject("节点标识无效，高危工具不会自动执行。");
    }
    // 审批等待不属于运行执行时间：等待期间不推进 elapsed，避免人工审批触发 TIME_LIMIT。
    control.beginApprovalWait();
    try {
      return approvals.await(
          runId, node, agentId, toolName, version, argsJson, risk, control::check);
    } finally {
      control.endApprovalWait();
    }
  }

  @Override
  public void contextCompacted(dev.workbench.framework.context.ContextBreakdown breakdown) {
    if (breakdown == null || !breakdown.compacted()) return;
    boolean english = dev.workbench.framework.prompt.PromptResourceLoader.isEnglish(locale);
    String message =
        english
            ? "Context "
                + breakdown.appliedTier()
                + " applied: "
                + breakdown.beforeTokens()
                + " → "
                + breakdown.totalEstimatedTokens()
                + " tokens, saved "
                + breakdown.savedPercent()
                + "%."
            : "已执行 "
                + breakdown.appliedTier()
                + " 上下文压缩："
                + breakdown.beforeTokens()
                + " → "
                + breakdown.totalEstimatedTokens()
                + " tokens，节省 "
                + breakdown.savedPercent()
                + "%。";
    evidence.event(
        runId,
        EventType.CONTEXT_COMPACTED,
        message,
        evidence
            .eventRefForAgent(runId, agentId)
            .withModelCall(control.currentModelCallId(agentId)));
  }

  @Override
  public void toolTrace(UUID callId, GlassboxModels.ToolCallTrace trace) {
    if (traces == null || callId == null || trace == null) return;
    traces.recordToolTrace(runId, callId.toString(), trace);
  }

  @Override
  public void toolRejected(String safeMessage) {
    control.finishTool(agentId, InvocationStatus.FAILED, null, safeMessage);
  }
}
