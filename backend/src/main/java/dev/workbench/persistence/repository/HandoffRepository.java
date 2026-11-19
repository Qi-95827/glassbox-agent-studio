package dev.workbench.persistence.repository;

import jakarta.persistence.EntityManager;

import dev.workbench.domain.HandoffDirection;
import dev.workbench.persistence.entity.HandoffEntity;
import dev.workbench.persistence.entity.HandoffReceiptEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 交接内容和实际接收记录。同行读写由调用方的运行锁及事务协调。*/
@Repository
@Transactional(readOnly = true)
public class HandoffRepository {
  private final EntityManager sessions;

  public HandoffRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  public Optional<Payload> findById(String id) {
    return sessions
        .createQuery(
            "select h.runId, h.direction, h.payload from HandoffEntity h where h.id = :id",
            Payload.class)
        .setParameter("id", id)
        .getResultList().stream().findFirst();
  }

  public List<ReceiptView> findByRunId(String runId) {
    return sessions
        .createQuery(
            """
            select h.id, h.runId, h.agentId, h.direction, h.payload, h.createdAt,
              r.receiverAgentId, r.receivedAt
            from HandoffEntity h
            left join HandoffReceiptEntity r on r.handoffId = h.id and r.runId = h.runId
            where h.runId = :runId order by h.createdAt, h.id
            """,
            ReceiptView.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(
      String id,
      String runId,
      String agentId,
      HandoffDirection direction,
      String payload,
      String time) {
    sessions.persist(new HandoffEntity(id, runId, agentId, direction.name(), payload, time));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insertReceipt(String id, String runId, String agentId, String time) {
    sessions.persist(new HandoffReceiptEntity(id, runId, agentId, time));
  }

  public record Payload(String runId, String direction, String json) {}

  public record ReceiptView(
      String id,
      String runId,
      String agentId,
      String direction,
      String payload,
      String createdAt,
      String receiverAgentId,
      String receivedAt) {}
}