package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ApprovalEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 工作区人工审查记录；过期判定仍由服务按当前指纹完成。 */
public interface ApprovalRepository extends JpaRepository<ApprovalEntity, String> {
  List<ApprovalEntity> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

  @Transactional
  @Modifying
  @Query(
      "update ApprovalEntity a set a.status = :status, a.updatedAt = :updatedAt"
          + " where a.id = :id")
  int updateStatus(
      @Param("id") String id,
      @Param("status") String status,
      @Param("updatedAt") String updatedAt);
}
