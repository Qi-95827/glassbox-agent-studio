package dev.workbench.persistence.repository;

import dev.workbench.domain.RecoveryDispatchStatus;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.persistence.entity.RunEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 运行读模型、行锁和带状态前置条件的写入；复杂查询和条件更新集中在此，H2 回归验证。 */
public interface RunRepository extends JpaRepository<RunEntity, String> {
  List<String> ACTIVE_STATUSES =
      List.of(RunStatus.PENDING.name(), RunStatus.RUNNING.name(), RunStatus.PAUSED.name());
  List<String> RETRYABLE_DISPATCH =
      List.of(
          RecoveryDispatchStatus.REJECTED.name(),
          RecoveryDispatchStatus.PRECONDITION_FAILED.name());
  String FAILED = RunStatus.FAILED.name();
  String PENDING = RunStatus.PENDING.name();
  String RUNNING = RunStatus.RUNNING.name();
  String BLOCKED = RunStatus.BLOCKED.name();
  String INTERRUPTED = RunStatus.INTERRUPTED.name();
  String COMPLETED = RunStatus.COMPLETED.name();
  String UNKNOWN = RecoveryDispatchStatus.UNKNOWN.name();

  @Transactional(propagation = Propagation.MANDATORY)
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RunEntity r where r.id = :id")
  Optional<RunEntity> lockById(@Param("id") String id);

  @Query(
      """
      select new dev.workbench.persistence.repository.RunData(
        r.id, r.taskId, r.parentId, r.status, r.reason, r.result, r.rounds, r.tools,
        r.maxRounds, r.maxTools, r.maxSeconds, r.elapsedMs, r.createdAt, r.updatedAt,
        r.maxPerTool, r.maxReportedTokens, r.maxIdenticalTools, r.maxAgentRounds,
        r.maxAgentTools, r.recoveryDispatch, r.stopCause)
      from RunEntity r where r.id = :id
      """)
  Optional<RunData> findRunById(@Param("id") String id);

  @Query(
      """
      select new dev.workbench.persistence.repository.RunData(
        r.id, r.taskId, r.parentId, r.status, r.reason, r.result, r.rounds, r.tools,
        r.maxRounds, r.maxTools, r.maxSeconds, r.elapsedMs, r.createdAt, r.updatedAt,
        r.maxPerTool, r.maxReportedTokens, r.maxIdenticalTools, r.maxAgentRounds,
        r.maxAgentTools, r.recoveryDispatch, r.stopCause)
      from RunEntity r where r.taskId = :taskId order by r.createdAt desc
      """)
  List<RunData> findRunsByTaskId(@Param("taskId") String taskId);

  @Query("select new dev.workbench.persistence.repository.RunOwner(r.taskId, r.parentId)"
      + " from RunEntity r where r.id = :id")
  Optional<RunOwner> findOwner(@Param("id") String id);

  @Query(
      """
      select count(r) from RunEntity r where r.parentId = :parentId
        and not (r.recoveryDispatch in :retryable and r.status = :failed)
      """)
  long countRecovery(
      @Param("parentId") String parentId,
      @Param("retryable") List<String> retryable,
      @Param("failed") String failed);

  default boolean hasRecovery(String parentId) {
    return countRecovery(parentId, RETRYABLE_DISPATCH, FAILED) > 0;
  }

  default boolean lockForUpdate(String runId) {
    return lockById(runId).isPresent();
  }

  default List<RunData> findByTaskId(String taskId) {
    return findRunsByTaskId(taskId);
  }

  default boolean updateActive(
      String id,
      String status,
      String reason,
      String result,
      int rounds,
      int tools,
      long elapsed,
      String cause,
      String time) {
    return updateIfInStatus(
            id, status, reason, result, rounds, tools, elapsed, cause, time, ACTIVE_STATUSES)
        == 1;
  }

  default boolean finishReview(
      String id,
      String status,
      String reason,
      String result,
      int rounds,
      int tools,
      long elapsed,
      String time) {
    return updateIfInStatus(
            id, status, reason, result, rounds, tools, elapsed, null, time, List.of(RUNNING))
        == 1;
  }

  default boolean updateActive(
      String id,
      RunStatus status,
      String reason,
      String result,
      int rounds,
      int tools,
      long elapsed,
      StopCause cause,
      String time) {
    return updateActive(
        id,
        status.name(),
        reason,
        result,
        rounds,
        tools,
        elapsed,
        cause == null ? null : cause.name(),
        time);
  }

  default boolean finishReview(
      String id,
      RunStatus status,
      String reason,
      String result,
      int rounds,
      int tools,
      long elapsed,
      String time) {
    return finishReview(id, status.name(), reason, result, rounds, tools, elapsed, time);
  }

  default boolean rejectBeforeDispatch(String runId, String reason, String time) {
    return rejectBeforeDispatch(
            runId,
            FAILED,
            reason,
            RecoveryDispatchStatus.REJECTED.name(),
            time,
            PENDING,
            UNKNOWN)
        == 1;
  }

  default boolean rejectRecoveryPrecondition(
      String runId, int rounds, int tools, long elapsed, String reason, String time) {
    return rejectRecoveryPrecondition(
            runId,
            FAILED,
            reason,
            StopCause.RECOVERY_PRECONDITION_FAILED.name(),
            RecoveryDispatchStatus.PRECONDITION_FAILED.name(),
            rounds,
            tools,
            elapsed,
            time,
            RUNNING,
            UNKNOWN,
            PlanNodeStatus.PENDING)
        == 1;
  }

  default boolean requeueAfterSupplement(String id, String time) {
    return requeueAfterSupplement(id, PENDING, BLOCKED, time) == 1;
  }

  default List<StartupRun> findForStartupInterruption() {
    return findForStartupInterruption(ACTIVE_STATUSES, PENDING);
  }

  default void interrupt(String runId, String reason, String time) {
    interrupt(runId, INTERRUPTED, reason, time);
  }

  default RunCounts countByStatus() {
    return countByStatus(RUNNING, COMPLETED);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      """
      update RunEntity r set r.status = :status, r.reason = :reason, r.result = :result,
        r.rounds = :rounds, r.tools = :tools, r.elapsedMs = :elapsed,
        r.stopCause = :cause, r.updatedAt = :time
      where r.id = :id and r.status in :allowed
      """)
  int updateIfInStatus(
      @Param("id") String id,
      @Param("status") String status,
      @Param("reason") String reason,
      @Param("result") String result,
      @Param("rounds") int rounds,
      @Param("tools") int tools,
      @Param("elapsed") long elapsed,
      @Param("cause") String cause,
      @Param("time") String time,
      @Param("allowed") List<String> allowed);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      """
      update RunEntity r set r.status = :failed, r.reason = :reason,
        r.recoveryDispatch = :rejected, r.updatedAt = :time
      where r.id = :id and r.parentId is not null and r.status = :pending
        and r.recoveryDispatch = :unknown
      """)
  int rejectBeforeDispatch(
      @Param("id") String runId,
      @Param("failed") String failed,
      @Param("reason") String reason,
      @Param("rejected") String rejected,
      @Param("time") String time,
      @Param("pending") String pending,
      @Param("unknown") String unknown);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      """
      update RunEntity r set r.status = :failed, r.reason = :reason, r.stopCause = :cause,
        r.recoveryDispatch = :rejected, r.rounds = :rounds, r.tools = :tools,
        r.elapsedMs = :elapsed, r.updatedAt = :time
      where r.id = :id and r.parentId is not null and r.status = :running
        and r.recoveryDispatch = :unknown
        and not exists (select a.id from AgentInvocationEntity a where a.runId = :id)
        and not exists (select m.id from ModelInvocationEntity m where m.runId = :id)
        and not exists (select t.id from ToolInvocationEntity t where t.runId = :id)
        and not exists (
          select n.id from PlanNodeEntity n where n.runId = :id and n.status <> :pendingStatus)
      """)
  int rejectRecoveryPrecondition(
      @Param("id") String runId,
      @Param("failed") String failed,
      @Param("reason") String reason,
      @Param("cause") String cause,
      @Param("rejected") String rejected,
      @Param("rounds") int rounds,
      @Param("tools") int tools,
      @Param("elapsed") long elapsed,
      @Param("time") String time,
      @Param("running") String running,
      @Param("unknown") String unknown,
      @Param("pendingStatus") PlanNodeStatus pendingStatus);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      """
      update RunEntity r set r.status = :pending, r.reason = null, r.result = null,
        r.stopCause = null, r.updatedAt = :time
      where r.id = :id and r.status = :blocked
      """)
  int requeueAfterSupplement(
      @Param("id") String id,
      @Param("pending") String pending,
      @Param("blocked") String blocked,
      @Param("time") String time);

  @Query(
      """
      select new dev.workbench.persistence.repository.StartupRun(r.id, p.id)
      from RunEntity r left join WorkflowPlanEntity p on p.runId = r.id
      where r.status in :active
        and not (p.id is not null and p.approvedAt is null and r.status = :pending)
      """)
  List<StartupRun> findForStartupInterruption(
      @Param("active") List<String> active, @Param("pending") String pending);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      "update RunEntity r set r.status = :status, r.reason = :reason, r.updatedAt = :time"
          + " where r.id = :id")
  int interrupt(
      @Param("id") String runId,
      @Param("status") String status,
      @Param("reason") String reason,
      @Param("time") String time);

  @Query(
      """
      select new dev.workbench.persistence.repository.RunCounts(
        count(r.id),
        coalesce(sum(case when r.status = :running then 1 else 0 end), 0),
        coalesce(sum(case when r.status = :completed then 1 else 0 end), 0))
      from RunEntity r
      """)
  RunCounts countByStatus(
      @Param("running") String running, @Param("completed") String completed);
}