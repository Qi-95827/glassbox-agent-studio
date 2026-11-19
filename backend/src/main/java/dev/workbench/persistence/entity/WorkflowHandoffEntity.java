package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "workflow_handoffs")
public class WorkflowHandoffEntity {
  @EmbeddedId private Key id;

  @Column(name = "sender_attempt_id", length = 36, nullable = false)
  private String senderAttemptId;

  @Column(name = "schema_version", nullable = false)
  private Integer schemaVersion;

  @Column(name = "accepted_at", length = 40, nullable = false)
  private String acceptedAt;

  protected WorkflowHandoffEntity() {}

  public WorkflowHandoffEntity(
      String receiverAttemptId,
      String dependencyNodeId,
      String senderAttemptId,
      Integer schemaVersion,
      String acceptedAt) {
    this.id = new Key(receiverAttemptId, dependencyNodeId);
    this.senderAttemptId = senderAttemptId;
    this.schemaVersion = schemaVersion;
    this.acceptedAt = acceptedAt;
  }

  @Embeddable
  public static class Key implements Serializable {
    @Column(name = "receiver_attempt_id", length = 36)
    private String receiverAttemptId;

    @Column(name = "dependency_node_id", length = 36)
    private String dependencyNodeId;

    protected Key() {}

    Key(String receiverAttemptId, String dependencyNodeId) {
      this.receiverAttemptId = receiverAttemptId;
      this.dependencyNodeId = dependencyNodeId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(receiverAttemptId, key.receiverAttemptId)
          && Objects.equals(dependencyNodeId, key.dependencyNodeId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(receiverAttemptId, dependencyNodeId);
    }
  }
}
