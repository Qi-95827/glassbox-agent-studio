package dev.workbench.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventRef;
import dev.workbench.domain.EventType;
import dev.workbench.domain.StopCause;
import dev.workbench.framework.control.StopExecution;
import dev.workbench.framework.tool.ToolApprovalAction;
import dev.workbench.framework.tool.ToolApprovalDecision;
import dev.workbench.framework.tool.ToolApprovalStatus;
import dev.workbench.framework.tool.ToolRiskAssessment;
import dev.workbench.persistence.entity.ToolApprovalRequestEntity;
import dev.workbench.persistence.repository.PlanStateRepository;
import dev.workbench.persistence.repository.ToolApprovalRequestRepository;
import dev.workbench.store.RunEvidenceRecordService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Gate 5.5 工具级动态审批。
 *
 * <p>高危调用创建持久化审批单后，节点进入 {@code WAITING_TOOL_APPROVAL}。等待使用有界短轮询，
 * 不调用 {@code Thread.sleep} 死等，也不长期占用业务线程；超时后审批单过期并停止运行，等待人工审核。
 */
@Service
public class ToolApprovalService {
  private final ToolApprovalRequestRepository approvals;
  private final PlanStateRepository plans;
  private final RunEvidenceRecordService evidence;
  private final ObjectMapper json;
  private final long waitSeconds;

  public ToolApprovalService(
      ToolApprovalRequestRepository approvals,
      PlanStateRepository plans,
      RunEvidenceRecordService evidence,
      ObjectMapper json,
      @Value("${workbench.tool-approval.wait-seconds:300}") long waitSeconds) {
    this.approvals = approvals;
    this.plans = plans;
    this.evidence = evidence;
    this.json = json;
    this.waitSeconds = Math.max(1, waitSeconds);
  }

  /** 供用户界面列出审批单；历史记录没有审批单时为空。 */
  public List<ApprovalView> list(String runId) {
    return approvals.findByRunIdOrderByRequestedAtDesc(runId).stream()
        .map(ToolApprovalService::view)
        .toList();
  }

  /**
   * 等待一次高危工具决策；已有同节点同工具决策时直接复用，避免重试重复弹窗。
   *
   * @param check 运行取消/暂停/截止时间检查；等待期间周期性执行
   */
  public ToolApprovalDecision await(
      String runId,
      UUID nodeId,
      String agentId,
      String toolName,
      int toolVersion,
      String argsJson,
      ToolRiskAssessment risk,
      Runnable check) {
    var existing = latestReusable(runId, nodeId, toolName, argsJson);
    if (existing != null) return decision(existing);
    String id = UUID.randomUUID().toString();
    Instant now = Instant.now();
    var row =
        new ToolApprovalRequestEntity(
            id,
            runId,
            nodeId.toString(),
            agentId,
            toolName,
            toolVersion,
            argsJson,
            risk.level().name(),
            limited(risk.reason(), 500),
            now.toString(),
            now.plusSeconds(waitSeconds).toString());
    approvals.save(row);
    boolean waiting = plans.waitForToolApproval(runId, nodeId.toString(), agentId);
    evidence.event(
        runId,
        EventType.TOOL_APPROVAL_REQUESTED,
        localized(
            "高危工具 " + toolName + " 已挂起，等待用户审批。",
            "High-risk tool " + toolName + " is suspended for user approval."),
        EventRef.empty().withNode(nodeId).withAgent(UUID.fromString(agentId)));
    long deadline = System.nanoTime() + Duration.ofSeconds(waitSeconds).toNanos();
    while (System.nanoTime() < deadline) {
      check.run();
      var current = approvals.findByIdAndRunId(id, runId).orElse(null);
      if (current != null && current.getStatus() != ToolApprovalStatus.PENDING) {
        plans.resumeFromToolApproval(runId, nodeId.toString());
        evidence.event(
            runId,
            EventType.TOOL_APPROVAL_DECIDED,
            localized(
                "工具审批已决策：" + current.getStatus() + "。",
                "Tool approval decided: " + current.getStatus() + "."),
            EventRef.empty().withNode(nodeId).withAgent(UUID.fromString(agentId)));
        return decision(current);
      }
      try {
        dev.workbench.framework.concurrent.BoundedWait.pauseInterruptibly(250);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        plans.resumeFromToolApproval(runId, nodeId.toString());
        throw new StopExecution(StopCause.INTERRUPTED, "审批等待期间进程中断，工具未执行。");
      }
    }
    var expired = approvals.findById(id).orElse(null);
    if (expired != null && expired.getStatus() == ToolApprovalStatus.PENDING) {
      expired.expire(Instant.now().toString());
      approvals.save(expired);
    }
    plans.resumeFromToolApproval(runId, nodeId.toString());
    throw new StopExecution(
        StopCause.HUMAN_REVIEW_REQUIRED,
        localized(
            "高危工具审批超时，工具未执行；请人工核对后重新发起。",
            "High-risk tool approval timed out; the tool was not executed. Review and retry."));
  }

  /** 用户决策；只允许 PENDING 且未过期的审批单。 */
  public ApprovalView decide(
      String runId,
      String approvalId,
      ToolApprovalAction action,
      String editedArgsJson,
      String reason) {
    var row =
        approvals
            .findByIdAndRunId(approvalId, runId)
            .orElseThrow(() -> new IllegalArgumentException("审批单不存在。"));
    if (row.getStatus() != ToolApprovalStatus.PENDING)
      throw new IllegalStateException("审批单已经处理，不能重复决策。");
    if (Instant.parse(row.getExpiresAt()).isBefore(Instant.now())) {
      row.expire(Instant.now().toString());
      approvals.save(row);
      throw new IllegalStateException("审批单已过期，工具不会执行。");
    }
    if (action == null) throw new IllegalArgumentException("请选择批准、改参放行或拒绝。");
    if (action == ToolApprovalAction.APPROVE_WITH_EDITED_ARGS) {
      if (editedArgsJson == null || editedArgsJson.isBlank())
        throw new IllegalArgumentException("改参放行必须提供修改后的参数 JSON。");
      try {
        if (!json.readTree(editedArgsJson).isObject())
          throw new IllegalArgumentException("修改后的参数必须是 JSON 对象。");
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new IllegalArgumentException("修改后的参数不是有效 JSON。", e);
      }
      if (reason == null || reason.isBlank()) reason = "用户在审批抽屉中修改了参数。";
    }
    if (action == ToolApprovalAction.REJECT && (reason == null || reason.isBlank()))
      throw new IllegalArgumentException("拒绝执行时必须填写理由，理由会回传模型。");
    ToolApprovalStatus status =
        switch (action) {
          case APPROVE -> ToolApprovalStatus.APPROVED;
          case APPROVE_WITH_EDITED_ARGS -> ToolApprovalStatus.APPROVED_WITH_EDITED_ARGS;
          case REJECT -> ToolApprovalStatus.REJECTED;
        };
    row.decide(
        status,
        action.name(),
        action == ToolApprovalAction.APPROVE_WITH_EDITED_ARGS ? editedArgsJson : null,
        reason,
        Instant.now().toString());
    approvals.save(row);
    return view(row);
  }

  public ApprovalView find(String runId, String approvalId) {
    return approvals
        .findByIdAndRunId(approvalId, runId)
        .map(ToolApprovalService::view)
        .orElseThrow(() -> new IllegalArgumentException("审批单不存在。"));
  }

  private ToolApprovalRequestEntity latestReusable(
      String runId, UUID nodeId, String toolName, String argsJson) {
    return approvals.findByRunIdOrderByRequestedAtDesc(runId).stream()
        .filter(
            row ->
                nodeId.toString().equals(row.getNodeId())
                    && toolName.equals(row.getToolName())
                    && argsJson.equals(row.getOriginalArgsJson())
                    && row.getStatus() != ToolApprovalStatus.EXPIRED)
        .findFirst()
        .orElse(null);
  }

  private static ToolApprovalDecision decision(ToolApprovalRequestEntity row) {
    return switch (row.getStatus()) {
      case APPROVED -> ToolApprovalDecision.approve();
      case APPROVED_WITH_EDITED_ARGS ->
          new ToolApprovalDecision(
              ToolApprovalAction.APPROVE_WITH_EDITED_ARGS,
              row.getEditedArgsJson(),
              row.getDecisionReason());
      case REJECTED -> ToolApprovalDecision.reject(row.getDecisionReason());
      default -> ToolApprovalDecision.reject("审批状态无效，工具未执行。");
    };
  }

  private static ApprovalView view(ToolApprovalRequestEntity row) {
    return new ApprovalView(
        row.getId(),
        row.getRunId(),
        row.getNodeId(),
        row.getAgentId(),
        row.getToolName(),
        row.getToolVersion(),
        row.getOriginalArgsJson(),
        row.getEditedArgsJson(),
        row.getRiskLevel(),
        row.getRiskReason(),
        row.getStatus().name(),
        row.getDecisionAction(),
        row.getDecisionReason(),
        row.getRequestedAt(),
        row.getDecidedAt(),
        row.getExpiresAt());
  }

  private static String limited(String text, int max) {
    return text == null ? "" : text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }

  private static String localized(String zh, String en) {
    // 事件正文由任务语言驱动；这里保留中英双写，前端按当前语言渲染标题与状态。
    return zh + " / " + en;
  }

  public record ApprovalView(
      String id,
      String runId,
      String nodeId,
      String agentId,
      String toolName,
      int toolVersion,
      String originalArgsJson,
      String editedArgsJson,
      String riskLevel,
      String riskReason,
      String status,
      String decisionAction,
      String decisionReason,
      String requestedAt,
      String decidedAt,
      String expiresAt) {}
}
