package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.TaskEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 任务读模型、归档、版本和行锁；复杂投影与条件更新使用 JPQL，H2 回归验证。 */
public interface TaskRepository extends JpaRepository<TaskEntity, String> {
  int INITIAL_TASK_VERSION = 1;

  @Query(
      """
      select new dev.workbench.persistence.repository.TaskWithRun(
        new dev.workbench.persistence.repository.TaskData(
          t.id, t.goal, t.scenario, t.createdAt, t.archivedAt, t.modelMode,
          t.workspaceId, t.fileScope, t.writeEnabled, t.bugId, t.memoryEnabled,
          t.constraintsJson, t.acceptanceJson, t.entryKind, t.fileScopesJson, t.version,
          t.modelConnectionId),
        r.id, r.status,
        case when r.status = :pending and p.reviewRequired = true and p.approvedAt is null
          then true else false end)
      from TaskEntity t
      left join RunEntity r on r.taskId = t.id and not exists (
        select 1 from RunEntity newer where newer.taskId = r.taskId
          and (newer.createdAt > r.createdAt
            or (newer.createdAt = r.createdAt and newer.id > r.id)))
      left join WorkflowPlanEntity p on p.runId = r.id
      order by t.createdAt desc
      """)
  List<TaskWithRun> findAllWithLatestRun(@Param("pending") String pending);

  @Query(
      """
      select new dev.workbench.persistence.repository.TaskData(
        t.id, t.goal, t.scenario, t.createdAt, t.archivedAt, t.modelMode,
        t.workspaceId, t.fileScope, t.writeEnabled, t.bugId, t.memoryEnabled,
        t.constraintsJson, t.acceptanceJson, t.entryKind, t.fileScopesJson, t.version,
        t.modelConnectionId)
      from TaskEntity t where t.id = :id
      """)
  Optional<TaskData> findTaskById(@Param("id") String id);

  @Query(
      """
      select new dev.workbench.persistence.repository.RoleBudgetRow(
        b.id.roleCode, b.policyVersion, b.maxModelCalls, b.maxToolCalls)
      from TaskRoleBudgetEntity b where b.id.taskId = :id
      """)
  List<RoleBudgetRow> findRoleBudgets(@Param("id") String id);

  @Query(
      """
      select new dev.workbench.persistence.repository.ToolBudgetRow(
        b.id.toolKind, b.policyVersion, b.maxCalls)
      from TaskToolBudgetEntity b where b.id.taskId = :id
      """)
  List<ToolBudgetRow> findToolBudgets(@Param("id") String id);

  @Query("select coalesce(t.version, :initialVersion) from TaskEntity t where t.id = :id")
  Optional<Integer> findVersionById(
      @Param("id") String id, @Param("initialVersion") int initialVersion);

  default Optional<Integer> findVersionById(String id) {
    return findVersionById(id, INITIAL_TASK_VERSION);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query("update TaskEntity t set t.archivedAt = :archivedAt where t.id = :id")
  int setArchivedAt(@Param("id") String id, @Param("archivedAt") String archivedAt);

  @Transactional(propagation = Propagation.MANDATORY)
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from TaskEntity t where t.id = :id")
  Optional<TaskEntity> lockById(@Param("id") String id);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      "update TaskEntity t set t.version = t.version + 1"
          + " where t.id = :id and t.version = :expected")
  int incrementVersion(@Param("id") String id, @Param("expected") int expected);

  default boolean lockForUpdate(String taskId) {
    return lockById(taskId).isPresent();
  }
}