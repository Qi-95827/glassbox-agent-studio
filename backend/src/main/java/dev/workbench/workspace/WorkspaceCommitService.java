package dev.workbench.workspace;

import dev.workbench.audit.AuditService;
import dev.workbench.persistence.entity.GitActionEntity;
import dev.workbench.persistence.repository.GitActionRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 仅用户明确发起的本地提交；不暴露为模型工具。 */
@Service
public class WorkspaceCommitService {
  private final GitActionRepository actions;
  private final WorkspaceService workspaces;
  private final ApprovalService approvals;
  private final AuditService audit;

  public WorkspaceCommitService(
      GitActionRepository actions,
      WorkspaceService workspaces,
      ApprovalService approvals,
      AuditService audit) {
    this.actions = actions;
    this.workspaces = workspaces;
    this.approvals = approvals;
    this.audit = audit;
  }

  @PostConstruct
  void markUnfinishedUnknown() {
    actions.markStartedUnknown(
        GitActionStatus.STARTED.name(),
        GitActionStatus.UNKNOWN.name(),
        dev.workbench.domain.WorkbenchTime.now());
  }

  public List<GitAction> list(String workspaceId) {
    workspaces.get(workspaceId);
    return actions.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
        .map(WorkspaceCommitService::map)
        .toList();
  }

  /** 审批必须对应本次提交的全部真实差异；出错时不自动重试。 */
  public GitAction commit(String workspaceId, String approvalId, String message) {
    if (message == null
        || message.isBlank()
        || message.strip().length() > 200
        || message.contains("\n")
        || message.contains("\r")) throw new IllegalArgumentException("提交说明应为 1 至 200 字的单行文本。");
    synchronized (workspaces) {
      String fingerprint = workspaces.approvalFingerprint(workspaceId);
      boolean approved =
          approvals.list(workspaceId).stream()
              .anyMatch(
                  a ->
                      approvalId != null
                          && approvalId.equals(a.get("ID"))
                          && "APPROVED".equals(a.get("STATUS"))
                          && fingerprint.equals(a.get("FINGERPRINT")));
      if (!approved) throw new IllegalStateException("需要绑定当前代码状态的有效批准。");
      String actionId = dev.workbench.domain.WorkbenchIds.newId(), time = dev.workbench.domain.WorkbenchTime.now();
      actions.save(
          new GitActionEntity(
              actionId,
              workspaceId,
              GitAction.Kind.LOCAL_COMMIT.name(),
              GitActionStatus.STARTED.name(),
              approvalId,
              null,
              null,
              null,
              time,
              time));
      try {
        String commit = workspaces.commitApproved(workspaceId, fingerprint, message.strip());
        actions.updateOutcome(
            actionId,
            GitActionStatus.CONFIRMED.name(),
            commit,
            null,
            dev.workbench.domain.WorkbenchTime.now());
        audit.record(
            "GIT_LOCAL_COMMIT",
            "WORKSPACE",
            workspaceId,
            "CONFIRMED",
            "本地提交 " + commit + "；审批 " + approvalId);
      } catch (RuntimeException e) {
        actions.updateOutcome(
            actionId,
            GitActionStatus.UNKNOWN.name(),
            null,
            null,
            dev.workbench.domain.WorkbenchTime.now());
        audit.record(
            "GIT_LOCAL_COMMIT",
            "WORKSPACE",
            workspaceId,
            "UNKNOWN",
            "提交未确认；审批 " + approvalId);
        throw new IllegalStateException("提交未确认，暂存或提交可能已发生；请检查工作区 Git 状态后再操作。", e);
      }
      return actions.findById(actionId).map(WorkspaceCommitService::map).orElseThrow();
    }
  }

  private static GitAction map(GitActionEntity row) {
    return new GitAction(
        UUID.fromString(row.getId()),
        UUID.fromString(row.getWorkspaceId()),
        GitAction.Kind.valueOf(row.getKind()),
        GitActionStatus.valueOf(row.getStatus()),
        UUID.fromString(row.getApprovalId()),
        row.getCommitId(),
        row.getTarget(),
        row.getDetail(),
        Instant.parse(row.getCreatedAt()),
        Instant.parse(row.getUpdatedAt()));
  }
}
