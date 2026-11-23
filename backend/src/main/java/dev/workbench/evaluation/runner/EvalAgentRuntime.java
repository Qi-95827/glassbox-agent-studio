package dev.workbench.evaluation.runner;

import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.tool.ToolApprovalDecision;
import dev.workbench.framework.tool.ToolRiskAssessment;
import dev.workbench.framework.trace.GlassboxModels;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评测专用轻量运行控制。
 *
 * <p>不持久化运行证据，但完整执行真实 ToolGateway 所需的预算、重复指纹与 Gate 5.5 决策语义：
 * 高危工具在评测沙箱中默认拒绝，重复指纹超过配置上限时立即熔断，供安全与工具评测观察真实拦截行为。
 */
final class EvalAgentRuntime implements AgentRuntime {
  private final int maxIdenticalTools;
  private final boolean approveHighRisk;
  private final Map<String, Integer> fingerprints = new HashMap<>();
  private final List<Map<String, Object>> gatewayChecks = new ArrayList<>();
  private int toolCalls;
  private boolean cancelled;

  EvalAgentRuntime(int maxIdenticalTools, boolean approveHighRisk) {
    this.maxIdenticalTools = Math.max(1, maxIdenticalTools);
    this.approveHighRisk = approveHighRisk;
  }

  List<Map<String, Object>> gatewayChecks() {
    synchronized (gatewayChecks) {
      return List.copyOf(gatewayChecks);
    }
  }

  int toolCalls() {
    return toolCalls;
  }

  void cancel() {
    cancelled = true;
  }

  @Override
  public void beforeModel(ModelKind modelName) {}

  @Override
  public void modelResult(Integer inputTokens, Integer outputTokens) {}

  @Override
  public void assemblyResolved(AgentAssemblySnapshot.Components components) {}

  @Override
  public UUID beforeTool(ToolKind tool, int version, String fingerprint) {
    if (cancelled) throw new IllegalStateException("评测已取消，工具调用未派发。");
    toolCalls++;
    String key = tool.name() + "|" + version + "|" + (fingerprint == null ? "" : fingerprint);
    int count = fingerprints.merge(key, 1, Integer::sum);
    if (count > maxIdenticalTools)
      throw new IllegalStateException(
          "重复工具指纹熔断：同一工具与参数已连续调用 " + count + " 次。");
    return UUID.randomUUID();
  }

  @Override
  public void check() {
    if (cancelled) throw new IllegalStateException("评测已取消。");
  }

  @Override
  public void event(EventType type, String message) {}

  @Override
  public void toolResult(String result) {}

  @Override
  public void toolRejected(String safeMessage) {}

  @Override
  public void toolTrace(UUID callId, GlassboxModels.ToolCallTrace trace) {
    synchronized (gatewayChecks) {
      var row = new java.util.LinkedHashMap<String, Object>();
      row.put("callId", callId == null ? "" : callId.toString());
      row.put("checks", trace == null ? List.of() : trace.gatewayChecks());
      row.put("result", trace == null ? "" : trace.fullResult());
      gatewayChecks.add(Map.copyOf(row));
    }
  }

  @Override
  public ToolApprovalDecision approvalDecision(
      String toolName, int version, String argsJson, ToolRiskAssessment risk) {
    if (!approveHighRisk)
      return ToolApprovalDecision.reject(
          "评测安全沙箱默认拒绝高危工具，等待人工在真实运行中审批。");
    return ToolApprovalDecision.approve();
  }
}
