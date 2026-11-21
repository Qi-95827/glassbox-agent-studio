package dev.workbench.workspace;

import dev.workbench.audit.AuditService;
import dev.workbench.domain.EventType;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.persistence.entity.RunHandoverEntity;
import dev.workbench.persistence.repository.RunHandoverRepository;
import dev.workbench.store.RunRecordService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 暂停后的人工接手与交还。
 *
 * <p>接手要求运行已暂停且没有在途模型、工具或系统检查；交还记录当前代码版本。代码变化时历史测试和审批不自动复用，运行保持暂停，由用户明确继续。
 */
@Service
public class RunHandoverService {
  private final RunHandoverRepository rows;
  private final RunRecordService runs;
  private final WorkspaceService workspaces;
  private final ApprovalService approvals;
  private final AuditService audit;

  public RunHandoverService(
      RunHandoverRepository rows,
      RunRecordService runs,
      WorkspaceService workspaces,
      ApprovalService approvals,
      AuditService audit) {
    this.rows = rows;
    this.runs = runs;
    this.workspaces = workspaces;
    this.approvals = approvals;
    this.audit = audit;
  }

  public synchronized Handover take(String runId, String note) {
    RunSnapshot run = runs.runSnapshot(runId);
    if (run.status() != RunStatus.PAUSED)
      throw new IllegalStateException("只有已暂停的运行可以人工接手。");
    var task = runs.taskDefinition(run.taskId().toString());
    if (task.workspaceId() == null)
      throw new IllegalStateException("没有工作区的任务不需要人工接手。");
    if (activeRow(runId) != null) throw new IllegalStateException("该运行已处于人工接手状态。");
    if (inFlight(runId) > 0)
      throw new IllegalStateException("仍有在途动作未结算，请等待停止边界后再接手。");
    String fingerprint = workspaces.snapshotFingerprint(task.workspaceId());
    String id = dev.workbench.domain.WorkbenchIds.newId(), now = dev.workbench.domain.WorkbenchTime.now();
    rows.save(
        new RunHandoverEntity(
            id,
            runId,
            task.workspaceId(),
            "ACTIVE",
            fingerprint,
            fingerprint,
            false,
            safeNote(note),
            now,
            null));
    runs.event(runId, EventType.HANDOVER_TAKEN, "用户已人工接手工作区；运行保持暂停。");
    audit.record("RUN_HANDOVER_TAKE", "RUN", runId, "ACTIVE", "工作区 " + task.workspaceId());
    return findByRun(runId);
  }

  public synchronized Handover returnControl(String runId, String note) {
    RunHandoverEntity active = activeRow(runId);
    if (active == null) throw new IllegalStateException("该运行没有待交还的人工接手。");
    String current = workspaces.snapshotFingerprint(active.getWorkspaceId());
    boolean changed = !active.getBaselineFingerprint().equals(current);
    active.markReturned(current, changed, safeNote(note), dev.workbench.domain.WorkbenchTime.now());
    rows.save(active);
    // 触发审批按当前版本重新校验；旧批准会转为 STALE，不会被继续复用。
    approvals.list(active.getWorkspaceId());
    runs.event(
        runId,
        EventType.HANDOVER_RETURNED,
        changed
            ? "用户已交还工作区；代码版本变化，旧测试与审批失效，需重新验证后继续。"
            : "用户已交还工作区；代码版本未变化，运行仍保持暂停。");
    audit.record(
        "RUN_HANDOVER_RETURN",
        "RUN",
        runId,
        changed ? "CHANGED" : "UNCHANGED",
        "工作区 " + active.getWorkspaceId());
    return findByRun(runId);
  }

  public Handover active(String runId) {
    RunHandoverEntity row = activeRow(runId);
    return row == null ? null : map(row);
  }

  /** 当前运行是否允许继续；接手期间和代码变化后的旧证据都不能直接恢复。 */
  public String resumeBlockReason(String runId) {
    Handover handover = findByRun(runId);
    if (handover != null && "ACTIVE".equals(handover.status()))
      return "人工接手期间不能继续，请先交还工作区。";
    if (requiresRevalidation(runId))
      return "人工修改后代码版本已变化，旧测试与审批失效；请重新验证后再继续。";
    return null;
  }

  /** 只要历史中存在代码变化交还且尚未重新验证，就保持阻止，不能被后续无改动接手覆盖。 */
  public boolean requiresRevalidation(String runId) {
    return rows.existsByRunIdAndStatusAndEvidenceInvalidatedTrue(runId, "RETURNED");
  }

  public Handover findByRun(String runId) {
    return rows.findFirstByRunIdOrderByTakenAtDescIdDesc(runId).map(RunHandoverService::map).orElse(null);
  }

  private RunHandoverEntity activeRow(String runId) {
    return rows
        .findFirstByRunIdAndStatusOrderByTakenAtDescIdDesc(runId, "ACTIVE")
        .orElse(null);
  }

  private int inFlight(String runId) {
    return (int)
        (rows.countRunningModelInvocations(runId, InvocationStatus.RUNNING)
            + rows.countRunningToolInvocations(runId, InvocationStatus.RUNNING)
            + rows.countRunningChecks(runId, SystemCheckStatus.RUNNING));
  }

  private static Handover map(RunHandoverEntity row) {
    return new Handover(
        UUID.fromString(row.getId()),
        UUID.fromString(row.getRunId()),
        UUID.fromString(row.getWorkspaceId()),
        row.getStatus(),
        row.getBaselineFingerprint(),
        row.getCurrentFingerprint(),
        row.isEvidenceInvalidated(),
        row.getNote(),
        Instant.parse(row.getTakenAt()),
        row.getReturnedAt() == null ? null : Instant.parse(row.getReturnedAt()));
  }

  private static String safeNote(String note) {
    if (note == null || note.isBlank()) return null;
    String value = note.strip();
    if (value.length() > 1000) throw new IllegalArgumentException("接手说明不能超过 1000 字。");
    return value;
  }

  public record Handover(
      UUID id,
      UUID runId,
      UUID workspaceId,
      String status,
      String baselineFingerprint,
      String currentFingerprint,
      boolean evidenceInvalidated,
      String note,
      Instant takenAt,
      Instant returnedAt) {}
}
