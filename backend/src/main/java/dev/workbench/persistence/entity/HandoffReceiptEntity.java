package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "handoff_receipts")
public class HandoffReceiptEntity {
  @Id
  @Column(name = "handoff_id", length = 36, nullable = false)
  private String handoffId;

  @Column(name = "run_id", length = 36, nullable = false)
  private String runId;

  @Column(name = "receiver_agent_id", length = 36, nullable = false)
  private String receiverAgentId;

  @Column(name = "received_at", length = 40, nullable = false)
  private String receivedAt;

  protected HandoffReceiptEntity() {}

  public HandoffReceiptEntity(
      String handoffId, String runId, String receiverAgentId, String receivedAt) {
    this.handoffId = handoffId;
    this.runId = runId;
    this.receiverAgentId = receiverAgentId;
    this.receivedAt = receivedAt;
  }
}
