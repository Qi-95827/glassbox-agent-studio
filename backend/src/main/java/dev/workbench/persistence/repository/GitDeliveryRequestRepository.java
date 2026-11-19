package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.GitDeliveryRequestEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 推送和合并交付请求；批准绑定的字段只读，状态与结果单独更新。 */
public interface GitDeliveryRequestRepository
    extends JpaRepository<GitDeliveryRequestEntity, String> {
  List<GitDeliveryRequestEntity> findByWorkspaceIdOrderByCreatedAtDescIdDesc(String workspaceId);

  @Transactional
  @Modifying
  @Query(
      "update GitDeliveryRequestEntity r set r.status = :status, r.updatedAt = :updatedAt"
          + " where r.id = :id")
  int updateStatus(
      @Param("id") String id,
      @Param("status") String status,
      @Param("updatedAt") String updatedAt);

  @Transactional
  @Modifying
  @Query(
      "update GitDeliveryRequestEntity r set r.status = :status,"
          + " r.resultDetail = :resultDetail, r.updatedAt = :updatedAt where r.id = :id")
  int updateResult(
      @Param("id") String id,
      @Param("status") String status,
      @Param("resultDetail") String resultDetail,
      @Param("updatedAt") String updatedAt);
}
