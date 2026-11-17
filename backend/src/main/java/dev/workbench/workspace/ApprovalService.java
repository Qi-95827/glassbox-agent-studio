package dev.workbench.workspace;

import dev.workbench.task.RunRepository;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 本地审查决定只记录对具体差异的意见，不触发提交、推送或合并。 */
@Service
public class ApprovalService {
  private final JdbcTemplate db;
  private final WorkspaceService workspaces;

  public ApprovalService(JdbcTemplate db, WorkspaceService workspaces) {
    this.db = db;
    this.workspaces = workspaces;
  }

  public synchronized List<Map<String, Object>> list(String workspaceId) {
    var rows =
        db.queryForList(
            "SELECT * FROM approvals WHERE workspace_id=? ORDER BY created_at DESC", workspaceId);
    for (var row : rows) {
      if (!"PENDING".equals(row.get("STATUS")) && !"APPROVED".equals(row.get("STATUS"))) continue;
      boolean valid;
      try {
        valid = workspaces.approvalFingerprint(workspaceId).equals(row.get("FINGERPRINT"));
      } catch (RuntimeException e) {
        valid = false;
      }
      if (!valid)
        db.update(
            "UPDATE approvals SET status='STALE',updated_at=? WHERE id=?",
            RunRepository.now(),
            row.get("ID"));
    }
    return db.queryForList(
        "SELECT * FROM approvals WHERE workspace_id=? ORDER BY created_at DESC", workspaceId);
  }

  public Map<String, Object> propose(String workspaceId) {
    String fingerprint = workspaces.approvalFingerprint(workspaceId),
        id = RunRepository.id(),
        now = RunRepository.now();
    db.update(
        "INSERT INTO approvals VALUES(?,?,?,?,?,?)",
        id,
        workspaceId,
        fingerprint,
        "PENDING",
        now,
        now);
    return db.queryForMap("SELECT * FROM approvals WHERE id=?", id);
  }

  public synchronized Map<String, Object> decide(String id, Decision decision) {
    var row = db.queryForMap("SELECT * FROM approvals WHERE id=?", id);
    if (!"PENDING".equals(row.get("STATUS"))) throw new IllegalStateException("该审查已处理，请重新发起。");
    if (decision == Decision.APPROVE) {
      String current;
      try {
        current = workspaces.approvalFingerprint(row.get("WORKSPACE_ID").toString());
      } catch (RuntimeException e) {
        db.update(
            "UPDATE approvals SET status='STALE',updated_at=? WHERE id=?", RunRepository.now(), id);
        throw new IllegalStateException("工作区状态已改变，原审查已失效。");
      }
      if (!current.equals(row.get("FINGERPRINT"))) {
        db.update(
            "UPDATE approvals SET status='STALE',updated_at=? WHERE id=?", RunRepository.now(), id);
        throw new IllegalStateException("代码或目标分支已变化，原审查已失效。");
      }
    }
    db.update(
        "UPDATE approvals SET status=?,updated_at=? WHERE id=?",
        decision == Decision.APPROVE ? "APPROVED" : "REJECTED",
        RunRepository.now(),
        id);
    return db.queryForMap("SELECT * FROM approvals WHERE id=?", id);
  }

  public enum Decision {
    APPROVE,
    REJECT
  }
}
