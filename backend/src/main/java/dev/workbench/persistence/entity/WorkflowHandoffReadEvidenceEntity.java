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
@Table(name = "workflow_handoff_tool_evidence")
public class WorkflowHandoffReadEvidenceEntity {
  @EmbeddedId private Key id;

  @Column(name = "tool_kind", length = 40, nullable = false)
  private String toolKind;

  @Column(name = "tool_version", nullable = false)
  private Integer toolVersion;

  @Lob
  @Column(name = "resource_version")
  private String resourceVersion;

  protected WorkflowHandoffReadEvidenceEntity() {}

  public WorkflowHandoffReadEvidenceEntity(
      String receiverAttemptId,
      String dependencyNodeId,
      String toolId,
      String toolKind,
      Integer toolVersion,
      String resourceVersion) {
    this.id = new Key(receiverAttemptId, dependencyNodeId, toolId);
    this.toolKind = toolKind;
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
