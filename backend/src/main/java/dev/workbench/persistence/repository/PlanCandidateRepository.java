package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.PlanCandidateEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 候选计划的状态条件更新；晚到的模型结果不能覆盖取消或过期状态。 */
public interface PlanCandidateRepository extends JpaRepository<PlanCandidateEntity, String> {
  Optional<PlanCandidateEntity> findByOwnerScopeKeyAndRequestId(
      String ownerScopeKey, String requestId);

  Optional<PlanCandidateEntity> findByIdAndOwnerScopeKey(String id, String ownerScopeKey);

  List<PlanCandidateEntity> findByOwnerScopeKeyAndGroupIdOrderByInputVersionDesc(
      String ownerScopeKey, String groupId);

  boolean existsByGroupIdAndInputVersionGreaterThan(String groupId, int inputVersion);

  @Query(
      """
      select coalesce(max(c.inputVersion), 0) from PlanCandidateEntity c
      where c.groupId = :groupId
      """)
  int latestInputVersion(@Param("groupId") String groupId);

  /** 同组出现新候选时，旧的可确认候选过期；失败与取消记录保留原状态。 */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying(clearAutomatically = true)
  @Query(
      """
      update PlanCandidateEntity c set c.status = :stale
      where c.groupId = :groupId and c.status = :ready and c.id <> :exceptId
      """)
  int markGroupStale(
      @Param("groupId") String groupId,
      @Param("ready") String ready,
      @Param("stale") String stale,
      @Param("exceptId") String exceptId);

  /** 只有仍在生成中的候选可以结算。 */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying(clearAutomatically = true)
  @Query(
      """
      update PlanCandidateEntity c set c.status = :status, c.candidateSha256 = :sha256,
        c.candidateJson = :candidateJson, c.agentBindingsJson = :agentBindingsJson,
        c.errorsJson = :errorsJson, c.modelName = :modelName,
        c.inputTokens = :inputTokens, c.outputTokens = :outputTokens, c.settledAt = :settledAt
      where c.id = :id and c.status = :generating
      """)
  int settle(
      @Param("id") String id,
      @Param("status") String status,
      @Param("sha256") String sha256,
      @Param("candidateJson") String candidateJson,
      @Param("agentBindingsJson") String agentBindingsJson,
      @Param("errorsJson") String errorsJson,
      @Param("modelName") String modelName,
      @Param("inputTokens") Integer inputTokens,
      @Param("outputTokens") Integer outputTokens,
      @Param("settledAt") String settledAt,
      @Param("generating") String generating);

  /** 只取消还在生成中的候选。 */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying(clearAutomatically = true)
  @Query(
      """
      update PlanCandidateEntity c set c.status = :cancelled, c.settledAt = :settledAt
      where c.id = :id and c.status = :generating
      """)
  int cancel(
      @Param("id") String id,
      @Param("cancelled") String cancelled,
      @Param("settledAt") String settledAt,
      @Param("generating") String generating);

  /** 进程启动时把上一个进程遗留的生成中候选标为中断，等待用户重新生成。 */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying(clearAutomatically = true)
  @Query(
      """
      update PlanCandidateEntity c set c.status = :interrupted, c.settledAt = :settledAt
      where c.status = :generating
      """)
  int interruptGenerating(
      @Param("interrupted") String interrupted,
      @Param("settledAt") String settledAt,
      @Param("generating") String generating);

  /** 确认运行时锁住候选行，保证并发确认只创建一个运行。 */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select c from PlanCandidateEntity c
      where c.id = :id and c.ownerScopeKey = :ownerScopeKey
      """)
  Optional<PlanCandidateEntity> lockByIdAndOwner(
      @Param("id") String id, @Param("ownerScopeKey") String ownerScopeKey);

  /** 只有一个确认请求能把独立交付从「未准备」推进到「准备中」。 */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying(clearAutomatically = true)
  @Query(
      """
      update PlanCandidateEntity c set c.preparationStatus = :preparing,
        c.workspaceOperationId = :workspaceOperationId,
        c.preparationId = :preparationId, c.preparationStartedAt = :startedAt
      where c.id = :id and c.ownerScopeKey = :ownerScopeKey
        and c.status = :ready and c.preparationStatus is null
      """)
  int beginPreparation(
      @Param("id") String id,
      @Param("ownerScopeKey") String ownerScopeKey,
      @Param("ready") String ready,
      @Param("preparing") String preparing,
      @Param("workspaceOperationId") String workspaceOperationId,
      @Param("preparationId") String preparationId,
      @Param("startedAt") String startedAt);

  /** 记录 Git 准备结果；只有预期的操作标识可以结算，被接管后不能覆盖新状态。 */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying(clearAutomatically = true)
  @Query(
      """
      update PlanCandidateEntity c set c.preparationStatus = :status,
        c.preparedWorkspaceId = :workspaceId
      where c.id = :id and c.preparationStatus = :preparing
        and c.preparationId = :preparationId
      """)
  int finishPreparation(
      @Param("id") String id,
      @Param("preparationId") String preparationId,
      @Param("workspaceId") String workspaceId,
      @Param("status") String status,
      @Param("preparing") String preparing);

  /** 把中断遗留的准备操作转交给当前请求；旧请求之后无法再结算。 */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying(clearAutomatically = true)
  @Query(
      """
      update PlanCandidateEntity c set c.preparationId = :newId,
        c.preparationStartedAt = :startedAt
      where c.id = :id and c.preparationStatus = :preparing
        and c.preparationId = :expectedId and c.preparedWorkspaceId is null
      """)
  int reassignPreparation(
      @Param("id") String id,
      @Param("expectedId") String expectedId,
      @Param("newId") String newId,
      @Param("startedAt") String startedAt,
      @Param("preparing") String preparing);

  /** 消费候选并绑定唯一运行；已消费时条件更新失败，调用方返回原运行。 */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying(clearAutomatically = true)
  @Query(
      """
      update PlanCandidateEntity c set c.status = :consumed, c.runId = :runId,
        c.settledAt = :settledAt
      where c.id = :id and c.status = :ready and c.runId is null
      """)
  int consume(
      @Param("id") String id,
      @Param("consumed") String consumed,
      @Param("ready") String ready,
      @Param("runId") String runId,
      @Param("settledAt") String settledAt);

  /**
   * 用人工修订后的图覆盖候选内容。
   *
   * <p>只有仍处于 READY 且尚未绑定运行的候选才会被改写：条件更新让并发确认、过期与重复修订 都退化为影响 0 行，调用方据此拒绝而不是静默覆盖。
   *
   * <p>提供 {@code expectedSha256} 时，摘要匹配必须在同一条 SQL 里原子完成：只在 Java 层比较会留下
   * 「检查后、写入前被别人改掉」的竞态窗口，并发编辑就会互相覆盖。
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying(clearAutomatically = true)
  @Query(
      """
      update PlanCandidateEntity c set c.candidateJson = :candidateJson,
        c.candidateSha256 = :sha256, c.agentBindingsJson = :bindingsJson
      where c.id = :id and c.ownerScopeKey = :ownerScopeKey
        and c.status = :ready and c.runId is null
        and (:expectedSha256 is null or c.candidateSha256 = :expectedSha256)
      """)
  int reviseDraft(
      @Param("id") String id,
      @Param("ownerScopeKey") String ownerScopeKey,
      @Param("candidateJson") String candidateJson,
      @Param("sha256") String sha256,
      @Param("bindingsJson") String bindingsJson,
      @Param("expectedSha256") String expectedSha256,
      @Param("ready") String ready);
}
