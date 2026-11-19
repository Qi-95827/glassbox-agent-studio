package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.BugCheckEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Bug 检测记录；按工作区和运行读取，保留原有排序。 */
public interface BugCheckRepository extends JpaRepository<BugCheckEntity, String> {
  List<BugCheckEntity> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

  List<BugCheckEntity> findByRunIdOrderByCreatedAtAscIdAsc(String runId);

  /** 只返回明确记录属于该运行的检测前，先核对运行存在。 */
  @Query("select count(r) from RunEntity r where r.id = :runId")
  long countRuns(@Param("runId") String runId);

  /** 任务验证必须同时匹配运行、Bug、工作区，且运行仍在执行。 */
  @Query(
      """
      select count(r) from RunEntity r join TaskEntity t on t.id = r.taskId
      where r.id = :runId and r.status = :running
        and t.bugId = :bugId and t.workspaceId = :workspaceId
      """)
  long countRunningRunForTask(
      @Param("runId") String runId,
      @Param("running") String running,
      @Param("bugId") String bugId,
      @Param("workspaceId") String workspaceId);

  /** 证据判断读取运行归属的 Bug 与工作区，不复制检测正文。 */
  @Query(
      """
      select new dev.workbench.persistence.repository.BugTaskOwner(t.bugId, t.workspaceId)
      from RunEntity r join TaskEntity t on t.id = r.taskId
      where r.id = :runId
      """)
  Optional<BugTaskOwner> findTaskOwner(@Param("runId") String runId);
}
