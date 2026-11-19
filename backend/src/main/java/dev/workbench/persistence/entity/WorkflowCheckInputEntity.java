package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "workflow_check_inputs")
public class WorkflowCheckInputEntity {
  @EmbeddedId private Key id;

  @Column(name = "source_kind", length = 30, nullable = false)
  private String sourceKind;

  @Column(name = "source_attempt_id", length = 36)
  private String sourceAttemptId;

  @Column(name = "source_check_id", length = 36)
  private String sourceCheckId;

  @Column(name = "source_run_id", length = 36, nullable = false)
  private String sourceRunId;

  @Column(name = "result_sha256", length = 64, nullable = false)
  private String resultSha256;

  protected WorkflowCheckInputEntity() {}

  public WorkflowCheckInputEntity(
      String checkId,
      String dependencyNodeId,
      String sourceKind,
      String sourceAttemptId,
      String sourceCheckId,
      String sourceRunId,
      String resultSha256) {
    this.id = new Key(checkId, dependencyNodeId);
    this.sourceKind = sourceKind;
    this.sourceAttemptId = sourceAttemptId;
    this.sourceCheckId = sourceCheckId;
    this.sourceRunId = sourceRunId;
    this.resultSha256 = resultSha256;
  }

  @Embeddable
  public static class Key implements Serializable {
    @Column(name = "check_id", length = 36)
    private String checkId;

    @Column(name = "dependency_node_id", length = 36)
    private String dependencyNodeId;

    protected Key() {}

    Key(String checkId, String dependencyNodeId) {
      this.checkId = checkId;
      this.dependencyNodeId = dependencyNodeId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(checkId, key.checkId)
          && Objects.equals(dependencyNodeId, key.dependencyNodeId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(checkId, dependencyNodeId);
    }
  }
}
