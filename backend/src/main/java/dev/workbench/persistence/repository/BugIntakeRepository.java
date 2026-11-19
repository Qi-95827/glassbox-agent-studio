package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.BugIntakeEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bug 原始描述、提交序号、重复链和分诊/回归结论。 */
public interface BugIntakeRepository extends JpaRepository<BugIntakeEntity, String> {
  /** 同来源、同版本、同工作区的最新一条；空工作区与空串按原 SQL 视为相同。 */
  @Query(
      """
      select i from BugIntakeEntity i
      where i.bugId = :bugId and i.source = :source and i.sourceVersion = :sourceVersion
        and coalesce(i.workspaceId, '') = :workspaceKey
      order by i.createdAt desc, i.id desc
      """)
  List<BugIntakeEntity> findPrevious(
      @Param("bugId") String bugId,
      @Param("source") String source,
      @Param("sourceVersion") int sourceVersion,
      @Param("workspaceKey") String workspaceKey,
      Pageable pageable);

  /** 同来源内内容摘要相同的第一条，作为重复提交目标。 */
  @Query(
      """
      select i from BugIntakeEntity i
      where i.bugId = :bugId and i.source = :source and i.sourceVersion = :sourceVersion
        and coalesce(i.workspaceId, '') = :workspaceKey and i.contentSha256 = :contentSha256
      order by i.createdAt, i.id
      """)
  List<BugIntakeEntity> findSameContent(
      @Param("bugId") String bugId,
      @Param("source") String source,
      @Param("sourceVersion") int sourceVersion,
      @Param("workspaceKey") String workspaceKey,
      @Param("contentSha256") String contentSha256,
      Pageable pageable);

  Optional<BugIntakeEntity> findFirstByRunId(String runId);

  List<BugIntakeEntity> findByWorkspaceIdOrderByCreatedAtDescIdDesc(String workspaceId);

  @Query(
      """
      select new dev.workbench.persistence.repository.BugRunSummary(r.status, r.reason, r.stopCause)
      from RunEntity r where r.id = :runId
      """)
  Optional<BugRunSummary> findRunSummary(@Param("runId") String runId);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      """
      update BugIntakeEntity i set i.triageStatus = :status, i.triageReason = :reason,
        i.triageCheckId = :checkId, i.triageUpdatedAt = :updatedAt
      where i.runId = :runId
      """)
  int updateTriage(
      @Param("runId") String runId,
      @Param("status") String status,
      @Param("reason") String reason,
      @Param("checkId") String checkId,
      @Param("updatedAt") String updatedAt);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      """
      update BugIntakeEntity i set i.regressionStatus = :status,
        i.regressionCheckId = :checkId, i.regressionUpdatedAt = :updatedAt
      where i.runId = :runId
      """)
  int updateRegression(
      @Param("runId") String runId,
      @Param("status") String status,
      @Param("checkId") String checkId,
      @Param("updatedAt") String updatedAt);
}
