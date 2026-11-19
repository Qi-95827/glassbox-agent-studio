package dev.workbench.persistence.repository;

import jakarta.persistence.EntityManager;

import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.persistence.entity.PlanNodeDependencyEntity;
import dev.workbench.persistence.entity.PlanNodeEntity;
import dev.workbench.persistence.entity.WorkflowPlanEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 保存初始图和计划元数据，节点状态单独由计划执行仓库结算。*/
@Repository
public class PlanGraphRepository {
  private static final int INITIAL_PLAN_VERSION = 1;
  private final EntityManager sessions;

  public PlanGraphRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  public void savePlan(
      String id,
      String runId,
      String templateId,
      int templateVersion,
      boolean reviewRequired,
      String approvedAt,
      String scopeType,
      String authorizationSha256,
      int maxAttempts) {
    sessions.persist(
        new WorkflowPlanEntity(
            id,
            runId,
            INITIAL_PLAN_VERSION,
            templateId,
            templateVersion,
            reviewRequired,
            approvedAt,
            scopeType,
            authorizationSha256,
            maxAttempts));
  }

  public void saveNode(PlanNodeEntity node) {
    sessions.persist(node);
  }

  public void saveEdge(String runId, String nodeId, String prerequisiteId) {
    sessions.persist(new PlanNodeDependencyEntity(runId, nodeId, prerequisiteId));
  }

  /** 先写入头和节点，再写依赖边及读取图摘要。*/
  public void flush() {
    sessions.flush();
  }

  public void updateGraphSha256(String planId, String sha256) {
    sessions.find(WorkflowPlanEntity.class, planId).setGraphSha256(sha256);
  }

  @Transactional(readOnly = true)
  public WorkflowPlan findPlan(String runId) {
    var plan = findPlanEntity(runId);
    if (plan == null) return null;
    return new WorkflowPlan(
        UUID.fromString(plan.getId()),
        plan.getPlanVersion(),
        plan.getTemplateId(),
        plan.getTemplateVersion(),
        plan.isReviewRequired(),
        plan.getApprovedAt() == null ? null : Instant.parse(plan.getApprovedAt()),
        plan.getGraphSha256(),
        plan.getSourceCheckpointId() == null ? null : UUID.fromString(plan.getSourceCheckpointId()),
        WorkflowTemplate.Scope.valueOf(plan.getScopeType()),
        plan.getAuthorizationSha256(),
        plan.getMaxAttempts());
  }

  public int approveIfPending(String planId, String runId, String approvedAt) {
    var session = sessions;
    int changed =
        session
            .createQuery(
                """
                update WorkflowPlanEntity p set p.approvedAt = :approvedAt
                where p.id = :id and p.runId = :runId and p.reviewRequired = true
                    and p.approvedAt is null
                """)
            .setParameter("id", planId)
            .setParameter("runId", runId)
            .setParameter("approvedAt", approvedAt)
            .executeUpdate();
    // 批准事务可能已经读过该计划。只刷新被更新的实体。
if (changed == 1) session.refresh(session.find(WorkflowPlanEntity.class, planId));
    return changed;
  }

  public int attachRecovery(String runId, UUID checkpointId, String approvedAt) {
    var plan = findPlanEntity(runId);
    if (plan == null) return 0;
    var session = sessions;
    int changed =
        session
            .createQuery(
                """
                update WorkflowPlanEntity p
                set p.sourceCheckpointId = :checkpointId, p.approvedAt = coalesce(p.approvedAt, :approvedAt)
                where p.id = :id and p.sourceCheckpointId is null
                """)
            .setParameter("id", plan.getId())
            .setParameter("checkpointId", checkpointId.toString())
            .setParameter("approvedAt", approvedAt)
            .executeUpdate();
    if (changed == 1) session.refresh(plan);
    return changed;
  }

  public boolean resetFailedCheck(String runId, String nodeId) {
    return sessions
            .createQuery(
                """
                update PlanNodeEntity n set n.status = :pending, n.startedAt = null, n.finishedAt = null
                where n.id = :id and n.runId = :runId and n.status = :failed
                """)
            .setParameter("id", nodeId)
            .setParameter("runId", runId)
            .setParameter("pending", PlanNodeStatus.PENDING)
            .setParameter("failed", PlanNodeStatus.FAILED)
            .executeUpdate()
        == 1;
  }

  public void resetSkippedNodes(String runId) {
    sessions
        .createQuery(
            """
            update PlanNodeEntity n set n.status = :pending, n.finishedAt = null
            where n.runId = :runId and n.status = :skipped
            """)
        .setParameter("runId", runId)
        .setParameter("pending", PlanNodeStatus.PENDING)
        .setParameter("skipped", PlanNodeStatus.SKIPPED)
        .executeUpdate();
  }

  private WorkflowPlanEntity findPlanEntity(String runId) {
    return sessions
        .createQuery(
            "from WorkflowPlanEntity p where p.runId = :runId", WorkflowPlanEntity.class)
        .setParameter("runId", runId)
        .getResultList().stream().findFirst().orElse(null);
  }
}