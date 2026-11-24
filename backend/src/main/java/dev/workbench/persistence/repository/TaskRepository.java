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
          t.modelConnectionId, t.locale),
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
        t.modelConnectionId, t.locale)
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

  /**
   * 只取给定编号集合中真实存在的任务编号。
   *
   * <p>任务列表的可见性过滤此前用 {@code findAll()} 拉全表再逐条比对归属，任务一多就是平方级扫表； 这里改为按归属集合精确取号。
   */
  @Query("select t.id from TaskEntity t where t.id in :ids")
  List<String> findExistingIds(@Param("ids") java.util.Collection<String> ids);

  /**
   * 没有归属登记的历史任务编号（旧单机数据）。
   *
   * <p>仅在单机模式或管理员已领取历史时可见，避免把他人数据混入列表。
   */
  @Query(
      "select t.id from TaskEntity t where t.id not in (select o.resourceId from"
          + " ResourceOwnershipEntity o where o.resourceType = :resourceType)")
  List<String> findIdsWithoutOwnership(@Param("resourceType") String resourceType);
}
