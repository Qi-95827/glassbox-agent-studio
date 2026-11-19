package dev.workbench.persistence.repository;

import jakarta.persistence.EntityManager;

import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.persistence.entity.RunSupplementEntity;
import dev.workbench.persistence.entity.RunSupplementEntity.Kind;
import dev.workbench.persistence.entity.RunSupplementEntity.Status;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 补充请求、答复与系统检查尝试的查询。*/
@Repository
@Transactional(readOnly = true)
public class SupplementRepository {
  private static final String REQUEST_FOR_RUN =
      """
      select s.id, s.runId, s.nodeId, s.reasonCode, s.question, s.kind, s.status,
        s.answerPath, s.answerSha256, s.taskVersion, s.createdAt, s.decidedAt
      from RunSupplementEntity s where s.runId = :runId
      """;
  private final EntityManager sessions;

  public SupplementRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  public List<Request> findOpen(String runId) {
    return sessions
        .createQuery(
            REQUEST_FOR_RUN + "and s.status = :status order by s.createdAt, s.id", Request.class)
        .setParameter("runId", runId)
        .setParameter("status", Status.OPEN)
        .setMaxResults(2)
        .getResultList();
  }

  public Request findLatest(String runId) {
    return sessions
        .createQuery(
            REQUEST_FOR_RUN + "order by s.createdAt desc, s.id desc", Request.class)
        .setParameter("runId", runId)
        .setMaxResults(1)
        .getResultList().stream().findFirst().orElse(null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(
      String id,
      String runId,
      String nodeId,
      String reasonCode,
      String question,
      int taskVersion,
      String now) {
    sessions.persist(
        new RunSupplementEntity(id, runId, nodeId, reasonCode, question, taskVersion, now));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean recordAnswer(String id, String path, String sha256, String now) {
    return sessions
            .createQuery(
                """
                update RunSupplementEntity s set s.status = :applied,
                  s.answerPath = :path, s.answerSha256 = :sha, s.decidedAt = :now
                where s.id = :id and s.status = :open
                """)
            .setParameter("id", id)
            .setParameter("open", Status.OPEN)
            .setParameter("applied", Status.APPLIED)
            .setParameter("path", path)
            .setParameter("sha", sha256)
            .setParameter("now", now)
            .executeUpdate()
        == 1;
  }

  public SystemCheckStatus findLatestCheckStatus(String nodeId) {
    return sessions
        .createQuery(
            """
            select c.status from WorkflowCheckEntity c
            where c.nodeId = :nodeId order by c.attemptNo desc
            """,
            SystemCheckStatus.class)
        .setParameter("nodeId", nodeId)
        .setMaxResults(1)
        .getSingleResult();
  }

  public record Request(
      String id,
      String runId,
      String nodeId,
      String reasonCode,
      String question,
      Kind requestKind,
      Status requestStatus,
      String answerPath,
      String answerSha256,
      Integer taskVersion,
      String createdAt,
      String decidedAt) {
    public String kind() {
      return requestKind.name();
    }

    public String status() {
      return requestStatus.name();
    }
  }
}