package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "workflow_handoff_write_evidence")
public class WorkflowHandoffWriteEvidenceEntity {
  @EmbeddedId private Key id;

  @Column(name = "tool_version", nullable = false)
  private Integer toolVersion;

  @Lob
  @Column(name = "resource_version")
  private String resourceVersion;

  protected WorkflowHandoffWriteEvidenceEntity() {}

  public WorkflowHandoffWriteEvidenceEntity(
      String receiverAttemptId,
      String dependencyNodeId,
      String toolId,
      Integer toolVersion,
      String resourceVersion) {
    this.id = new Key(receiverAttemptId, dependencyNodeId, toolId);
    this.toolVersion = toolVersion;
    this.resourceVersion = resourceVersion;
  }

  @Embeddable
  public static class Key implements Serializable {
    @Column(name = "receiver_attempt_id", length = 36)
    private String receiverAttemptId;

    @Column(name = "dependency_node_id", length = 36)
    private String dependencyNodeId;

    @Column(name = "tool_id", length = 36)
    private String toolId;

    protected Key() {}

    Key(String receiverAttemptId, String dependencyNodeId, String toolId) {
      this.receiverAttemptId = receiverAttemptId;
      this.dependencyNodeId = dependencyNodeId;
      this.toolId = toolId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(receiverAttemptId, key.receiverAttemptId)
          && Objects.equals(dependencyNodeId, key.dependencyNodeId)
          && Objects.equals(toolId, key.toolId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(receiverAttemptId, dependencyNodeId, toolId);
    }
  }
}
