package dev.workbench.persistence.repository;

import jakarta.persistence.EntityManager;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 核对真实测试记录和恢复复用来源，返回是否有唯一匹配。*/
@Repository
@Transactional(readOnly = true)
public class HandoffEvidenceRepository {
  private final EntityManager sessions;

  public HandoffEvidenceRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  public boolean matchesBugCheck(String checkId, String runId, String taskId) {
    return sessions
            .createQuery(
                """
                select b.id from BugCheckReferenceEntity b
                join RunEntity r on r.id = b.runId
                join TaskEntity t on t.id = r.taskId
                where b.id = :checkId and b.runId = :runId and r.taskId = :taskId
                  and b.bugId = t.bugId and b.workspaceId = t.workspaceId and b.kind in :kinds
                """,
                String.class)
            .setParameter("checkId", checkId)
            .setParameter("runId", runId)
            .setParameter("taskId", taskId)
            .setParameter(
                "kinds",
                List.of(
                    dev.workbench.bug.BugService.CheckKind.REPRODUCE.name(),
                    dev.workbench.bug.BugService.CheckKind.REGRESSION.name()))
            .getResultList().stream().findFirst().orElse(null)
        != null;
  }

  public boolean hasUniqueReusedAnalysis(String runId, String taskId, String sourceRunId) {
    return sessions
            .createQuery(
                """
                select count(n.id) from PlanNodeEntity n
                join AgentInvocationEntity a on a.id = n.sourceAgentId
                join RunEntity r on r.id = a.runId
                where n.runId = :runId and n.roleCode = :role and n.status = :reused
                  and a.runId = :sourceRunId and a.roleName = :roleName and a.status = :completed
                  and r.taskId = :taskId
                """,
                Long.class)
            .setParameter("runId", runId)
            .setParameter("taskId", taskId)
            .setParameter("sourceRunId", sourceRunId)
            .setParameter("role", AgentRole.ANALYST.name())
            .setParameter("reused", PlanNodeStatus.REUSED)
            .setParameter("roleName", AgentRole.ANALYST.displayName())
            .setParameter("completed", InvocationStatus.COMPLETED)
            .getSingleResult()
        == 1;
  }
}