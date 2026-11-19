package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 计划依赖边；复合主键与数据库保持一致。 */
@Entity
@Table(name = "plan_node_dependencies")
public class PlanNodeDependencyEntity {
  @EmbeddedId private Id id;

  protected PlanNodeDependencyEntity() {}

  public PlanNodeDependencyEntity(String runId, String nodeId, String prerequisiteId) {
    this.id = new Id(runId, nodeId, prerequisiteId);
  }

  public Id getId() {
    return id;
  }

  @Embeddable
  public static class Id implements Serializable {
    @Column(name = "run_id", length = 36)
    private String runId;

    @Column(name = "node_id", length = 36)
    private String nodeId;

    @Column(name = "prerequisite_id", length = 36)
    private String prerequisiteId;

    protected Id() {}

    public Id(String runId, String nodeId, String prerequisiteId) {
      this.runId = runId;
      this.nodeId = nodeId;
      this.prerequisiteId = prerequisiteId;
    }

    public String getRunId() {
      return runId;
    }

    public String getNodeId() {
      return nodeId;
    }

    public String getPrerequisiteId() {
      return prerequisiteId;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Id id)) return false;
      return Objects.equals(runId, id.runId)
          && Objects.equals(nodeId, id.nodeId)
          && Objects.equals(prerequisiteId, id.prerequisiteId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(runId, nodeId, prerequisiteId);
    }
  }
}
