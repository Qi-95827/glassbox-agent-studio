package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.GitActionEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 本地 Git 动作历史与结算更新。 */
public interface GitActionRepository extends JpaRepository<GitActionEntity, String> {
  List<GitActionEntity> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

  @Query("select distinct a.workspaceId from GitActionEntity a where a.status = 'CONFIRMED'")
  List<String> findDeliveredWorkspaceIds();

  @Transactional
  @Modifying
  @Query(
      "update GitActionEntity a set a.status = :status, a.commitId = :commitId,"
          + " a.detail = :detail, a.updatedAt = :updatedAt where a.id = :id")
  int updateOutcome(
      @Param("id") String id,
      @Param("status") String status,
      @Param("commitId") String commitId,
      @Param("detail") String detail,
      @Param("updatedAt") String updatedAt);

  /** 启动时把上次未结算的动作标记为未知，保留提交 ID 和详情供人工核对。 */
  @Transactional
  @Modifying
  @Query(
      "update GitActionEntity a set a.status = :unknown, a.updatedAt = :updatedAt"
          + " where a.status = :started")
  int markStartedUnknown(
      @Param("started") String started,
      @Param("unknown") String unknown,
      @Param("updatedAt") String updatedAt);
}
