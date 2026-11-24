package dev.workbench.workspace;

import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.entity.ApprovalEntity;
import dev.workbench.persistence.repository.ApprovalRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

/** 本地审查决定只记录对具体差异的意见，不触发提交、推送或合并。 */
@Service
public class ApprovalService {
  private final ApprovalRepository rows;
  private final WorkspaceService workspaces;
  private final OwnershipService ownership;

  public ApprovalService(
      ApprovalRepository rows, WorkspaceService workspaces, OwnershipService ownership) {
    this.rows = rows;
    this.workspaces = workspaces;
    this.ownership = ownership;
  }

  public synchronized List<Map<String, Object>> list(String workspaceId) {
    var approvals = rows.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    for (var row : approvals) {
      if (!"PENDING".equals(row.getStatus()) && !"APPROVED".equals(row.getStatus())) continue;
      boolean valid;
      try {
        valid = workspaces.approvalFingerprint(workspaceId).equals(row.getFingerprint());
      } catch (RuntimeException e) {
        valid = false;
      }
      if (!valid) rows.updateStatus(row.getId(), "STALE", dev.workbench.domain.WorkbenchTime.now());
    }
    return rows.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
        .map(ApprovalService::map)
        .toList();
  }

  public Map<String, Object> propose(String workspaceId) {
    String fingerprint = workspaces.approvalFingerprint(workspaceId),
        id = dev.workbench.domain.WorkbenchIds.newId(),
        now = dev.workbench.domain.WorkbenchTime.now();
    rows.save(new ApprovalEntity(id, workspaceId, fingerprint, "PENDING", now, now));
    return map(rows.findById(id).orElseThrow(() -> new EmptyResultDataAccessException(1)));
  }

  public synchronized Map<String, Object> decide(String id, Decision decision) {
    ApprovalEntity row = rows.findById(id).orElseThrow(() -> new EmptyResultDataAccessException(1));
    // 审批单标识本身不是授权凭证：先按审批所属工作区校验当前操作人的归属，再处理状态。
    ownership.requireWorkspace(row.getWorkspaceId());
    if (!"PENDING".equals(row.getStatus())) throw new IllegalStateException("该审查已处理，请重新发起。");
    if (decision == Decision.APPROVE) {
      String current;
      try {
        current = workspaces.approvalFingerprint(row.getWorkspaceId());
      } catch (RuntimeException e) {
        rows.updateStatus(id, "STALE", dev.workbench.domain.WorkbenchTime.now());
        throw new IllegalStateException("工作区状态已改变，原审查已失效。");
      }
      if (!current.equals(row.getFingerprint())) {
        rows.updateStatus(id, "STALE", dev.workbench.domain.WorkbenchTime.now());
        throw new IllegalStateException("代码或目标分支已变化，原审查已失效。");
      }
    }
    rows.updateStatus(
        id,
        decision == Decision.APPROVE ? "APPROVED" : "REJECTED",
        dev.workbench.domain.WorkbenchTime.now());
    return map(rows.findById(id).orElseThrow(() -> new EmptyResultDataAccessException(1)));
  }

  /** 保持原查询返回的大写列名和字符串值，HTTP 与报告继续按原字段读取。 */
  private static Map<String, Object> map(ApprovalEntity row) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("ID", row.getId());
    values.put("WORKSPACE_ID", row.getWorkspaceId());
    values.put("FINGERPRINT", row.getFingerprint());
    values.put("STATUS", row.getStatus());
    values.put("CREATED_AT", row.getCreatedAt());
    values.put("UPDATED_AT", row.getUpdatedAt());
    return values;
  }

  public enum Decision {
    APPROVE,
    REJECT
  }
}
