package dev.workbench.external;

import dev.workbench.audit.AuditService;
import dev.workbench.persistence.entity.ExternalActionCheckEntity;
import dev.workbench.persistence.entity.ExternalActionEntity;
import dev.workbench.persistence.repository.ExternalActionCheckRepository;
import dev.workbench.persistence.repository.ExternalActionRepository;
import dev.workbench.task.RunRecordService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 外部动作对账。
 *
 * <p>动作标识、审批引用、目标版本和请求摘要必须一起保存。执行结果未知时停在待人工核对；查询确认成功只补记证据，不重复执行；同一动作已确认后再次核对返回原结果。
 */
@Service
public class ExternalActionService {
  public static final String NEEDS_MANUAL_REVIEW = "NEEDS_MANUAL_REVIEW";
  private final ExternalActionRepository actions;
  private final ExternalActionCheckRepository checks;
  private final ExternalActionAdapter adapter;
  private final AuditService audit;

  public ExternalActionService(
      ExternalActionRepository actions,
      ExternalActionCheckRepository checks,
      ExternalActionAdapter adapter,
      AuditService audit) {
    this.actions = actions;
    this.checks = checks;
    this.adapter = adapter;
    this.audit = audit;
  }

  @Transactional
  public Action prepare(
      String runId,
      String actionKey,
      String kind,
      String approvalRef,
      String target,
      String targetVersion,
      String payload) {
    if (actionKey == null || !actionKey.matches("[A-Za-z0-9._:-]{1,120}"))
      throw new IllegalArgumentException("外部动作标识无效。");
    if (runId != null && runId.length() > 36)
      throw new IllegalArgumentException("运行标识超过允许长度。");
    if (approvalRef != null && approvalRef.length() > 80)
      throw new IllegalArgumentException("审批标识超过允许长度。");
    if (targetVersion != null && targetVersion.length() > 100)
      throw new IllegalArgumentException("目标版本超过允许长度。");
    if (kind == null || kind.isBlank() || kind.length() > 40)
      throw new IllegalArgumentException("外部动作类型无效。");
    if (target == null || target.isBlank() || target.length() > 300)
      throw new IllegalArgumentException("外部动作目标无效。");
    if (payload == null) throw new IllegalArgumentException("外部动作请求内容不能为空。");
    if (actions.findByActionKey(actionKey).isPresent())
      throw new IllegalStateException("该外部动作标识已经登记，不能改写原目标或审批。");
    String time = Instant.now().toString();
    var saved =
        actions.saveAndFlush(
            new ExternalActionEntity(
                RunRecordService.id(),
                runId,
                actionKey,
                kind,
                approvalRef == null ? "PREPARED" : "APPROVED",
                approvalRef,
                target,
                targetVersion,
                sha(payload),
                time,
                time));
    audit.record("EXTERNAL_ACTION_PREPARE", "EXTERNAL_ACTION", actionKey, saved.getStatus(), "目标版本 " + targetVersion);
    return toAction(saved);
  }

  /** 调用外部动作后立即标记派发；返回结果未知时由查询对账补证。 */
  @Transactional
  public Action markDispatched(String actionKey) {
    var action = find(actionKey);
    if (!"APPROVED".equals(action.getStatus()) && !"PREPARED".equals(action.getStatus()))
      throw new IllegalStateException("只有未派发的外部动作可以执行。");
    action.markDispatched(Instant.now().toString());
    actions.save(action);
    return toAction(action);
  }

  /** 只查询、不重放；确认成功补记证据，无法确认转人工核对。 */
  @Transactional
  public Action reconcile(String actionKey) {
    var action = find(actionKey);
    if ("SUCCEEDED".equals(action.getStatus()) || "FAILED".equals(action.getStatus()))
      return withChecks(action);
    ExternalActionAdapter.Probe probe = adapter.probe(action);
    String time = Instant.now().toString();
    checks.save(
        new ExternalActionCheckEntity(
            RunRecordService.id(),
            action.getId(),
            probe.outcome().name(),
            safe(probe.evidence()),
            time));
    switch (probe.outcome()) {
      case SUCCEEDED -> action.applyReconciliation("SUCCEEDED", safe(probe.evidence()), time);
      case FAILED -> action.applyReconciliation("FAILED", safe(probe.evidence()), time);
      case NOT_FOUND -> action.applyReconciliation("NOT_FOUND", safe(probe.evidence()), time);
      case UNKNOWN -> action.markUnknown(safe(probe.evidence()), time);
    }
    actions.save(action);
    audit.record("EXTERNAL_ACTION_RECONCILE", "EXTERNAL_ACTION", actionKey, action.getStatus(), "查询结果 " + probe.outcome());
    return withChecks(action);
  }

  public List<Action> findByRun(String runId) {
    return actions.findByRunIdOrderByCreatedAtAsc(runId).stream()
        .map(this::withChecks)
        .toList();
  }

  public List<Action> list() {
    return actions.findAll().stream().map(this::withChecks).toList();
  }

  public Action get(String id) {
    return withChecks(
        actions.findById(id).orElseThrow(() -> new IllegalArgumentException("外部动作不存在。")));
  }

  private ExternalActionEntity find(String actionKey) {
    return actions
        .findByActionKey(actionKey)
        .orElseThrow(() -> new IllegalArgumentException("外部动作不存在。"));
  }

  private static String safe(String value) {
    if (value == null) return null;
    return value.length() <= 2000 ? value : value.substring(0, 1999) + "…";
  }

  private static Action toAction(ExternalActionEntity row) {
    return new Action(
        row.getId(),
        row.getRunId(),
        row.getActionKey(),
        row.getKind(),
        row.getStatus(),
        row.getApprovalRef(),
        row.getTarget(),
        row.getTargetVersion(),
        row.getPayloadSha256(),
        row.getResultEvidence(),
        row.getAttempts(),
        row.getLastCheckedAt(),
        row.getCreatedAt(),
        row.getUpdatedAt(),
        NEEDS_MANUAL_REVIEW.equals(row.getStatus()),
        List.of());
  }

  private Action withChecks(ExternalActionEntity row) {
    var history =
        checks.findByActionIdOrderByCheckedAtAsc(row.getId()).stream()
            .map(
                item ->
                    new Check(
                        item.getId(), item.getOutcome(), item.getEvidence(), item.getCheckedAt()))
            .toList();
    var base = toAction(row);
    return new Action(
        base.id(),
        base.runId(),
        base.actionKey(),
        base.kind(),
        base.status(),
        base.approvalRef(),
        base.target(),
        base.targetVersion(),
        base.payloadSha256(),
        base.resultEvidence(),
        base.attempts(),
        base.lastCheckedAt(),
        base.createdAt(),
        base.updatedAt(),
        base.needsManualReview(),
        history);
  }

  private static String sha(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public record Action(
      String id,
      String runId,
      String actionKey,
      String kind,
      String status,
      String approvalRef,
      String target,
      String targetVersion,
      String payloadSha256,
      String resultEvidence,
      int attempts,
      String lastCheckedAt,
      String createdAt,
      String updatedAt,
      boolean needsManualReview,
      List<Check> checks) {}

  public record Check(String id, String outcome, String evidence, String checkedAt) {}
}
