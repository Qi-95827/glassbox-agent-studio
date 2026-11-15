package dev.workbench.workspace;

import dev.workbench.task.RunRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 仅用户明确发起的本地提交；不暴露为模型工具。 */
@Service
public class WorkspaceCommitService {
  private final JdbcTemplate db;
  private final WorkspaceService workspaces;
  private final ApprovalService approvals;

  public WorkspaceCommitService(
      JdbcTemplate db, WorkspaceService workspaces, ApprovalService approvals) {
    this.db = db;
    this.workspaces = workspaces;
    this.approvals = approvals;
  }

  @PostConstruct
  void markUnfinishedUnknown() {
    db.update(
        "UPDATE git_actions SET status=?,updated_at=? WHERE status=?",
        GitActionStatus.UNKNOWN.name(),
        RunRepository.now(),
        GitActionStatus.STARTED.name());
  }

  public List<GitAction> list(String workspaceId) {
    workspaces.get(workspaceId);
    return db.query(
        "SELECT * FROM git_actions WHERE workspace_id=? ORDER BY created_at DESC",
        (r, i) ->
            new GitAction(
                UUID.fromString(r.getString("id")),
                UUID.fromString(r.getString("workspace_id")),
                GitAction.Kind.valueOf(r.getString("kind")),
                GitActionStatus.valueOf(r.getString("status")),
                UUID.fromString(r.getString("approval_id")),
                r.getString("commit_id"),
                Instant.parse(r.getString("created_at")),
                Instant.parse(r.getString("updated_at"))),
        workspaceId);
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
      String actionId = RunRepository.id(), time = RunRepository.now();
      db.update(
          "INSERT INTO git_actions VALUES(?,?,?,?,?,?,?,?)",
          actionId,
          workspaceId,
          GitAction.Kind.LOCAL_COMMIT.name(),
          GitActionStatus.STARTED.name(),
          approvalId,
          null,
          time,
          time);
      try {
        String commit = workspaces.commitApproved(workspaceId, fingerprint, message.strip());
        db.update(
            "UPDATE git_actions SET status=?,commit_id=?,updated_at=? WHERE id=?",
            GitActionStatus.CONFIRMED.name(),
            commit,
            RunRepository.now(),
            actionId);
      } catch (RuntimeException e) {
        db.update(
            "UPDATE git_actions SET status=?,updated_at=? WHERE id=?",
            GitActionStatus.UNKNOWN.name(),
            RunRepository.now(),
            actionId);
        throw new IllegalStateException("提交未确认，暂存或提交可能已发生；请检查工作区 Git 状态后再操作。", e);
      }
      return list(workspaceId).stream()
          .filter(a -> a.id().toString().equals(actionId))
          .findFirst()
          .orElseThrow();
    }
  }
}
