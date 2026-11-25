package dev.workbench.persistence.repository;

import dev.workbench.framework.tool.ToolApprovalStatus;
import dev.workbench.persistence.entity.ToolApprovalRequestEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolApprovalRequestRepository
    extends JpaRepository<ToolApprovalRequestEntity, String> {
  List<ToolApprovalRequestEntity> findByRunIdOrderByRequestedAtDesc(String runId);

  List<ToolApprovalRequestEntity> findByRunIdAndStatusOrderByRequestedAtDesc(
      String runId, ToolApprovalStatus status);

  Optional<ToolApprovalRequestEntity> findByIdAndRunId(String id, String runId);

  /**
   * 原子决策：只有仍为当日状态且未过期的审批单能被改写。
   *
   * <p>不新增数据库列（历史迁移脚本禁止改动）：用条件更新 + 影响行数替代乐观锁版本号， 并发决策、超时到期与重启清理互相不会覆写。
   *
   * @return 影响行数；1 表示本次决策生效，0 表示已被其它路径处理
   */
  @org.springframework.data.jpa.repository.Modifying(
      flushAutomatically = true,
      clearAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @org.springframework.data.jpa.repository.Query(
      "update ToolApprovalRequestEntity r set r.status = :next, r.decisionAction = :action,"
          + " r.editedArgsJson = :editedArgs, r.decisionReason = :reason, r.decidedAt = :decidedAt"
          + " where r.id = :id and r.runId = :runId and r.status = :expected")
  int decideIfPending(
      @org.springframework.data.repository.query.Param("id") String id,
      @org.springframework.data.repository.query.Param("runId") String runId,
      @org.springframework.data.repository.query.Param("expected") ToolApprovalStatus expected,
      @org.springframework.data.repository.query.Param("next") ToolApprovalStatus next,
      @org.springframework.data.repository.query.Param("action") String action,
      @org.springframework.data.repository.query.Param("editedArgs") String editedArgs,
      @org.springframework.data.repository.query.Param("reason") String reason,
      @org.springframework.data.repository.query.Param("decidedAt") String decidedAt);

  /** 级联作废某运行的遗留待审批单（取消、超时、重启清理）。 */
  @org.springframework.data.jpa.repository.Modifying(
      flushAutomatically = true,
      clearAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @org.springframework.data.jpa.repository.Query(
      "update ToolApprovalRequestEntity r set r.status = :next, r.decidedAt = :decidedAt"
          + " where r.runId = :runId and r.status = :expected")
  int expirePendingForRun(
      @org.springframework.data.repository.query.Param("runId") String runId,
      @org.springframework.data.repository.query.Param("expected") ToolApprovalStatus expected,
      @org.springframework.data.repository.query.Param("next") ToolApprovalStatus next,
      @org.springframework.data.repository.query.Param("decidedAt") String decidedAt);

  Optional<ToolApprovalRequestEntity>
      findFirstByRunIdAndNodeIdAndToolNameAndStatusOrderByRequestedAtDesc(
          String runId, String nodeId, String toolName, ToolApprovalStatus status);
}
