package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 有界反思回边及其已触发次数；运行计划只允许审查节点回指直接上游。 */
@Entity
@Table(name = "plan_revision_edges")
public class PlanRevisionEdgeEntity {
  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "run_id", length = 36, nullable = false)
  private String runId;

  @Column(name = "reviewer_node_id", length = 36, nullable = false)
  private String reviewerNodeId;

  @Column(name = "target_node_id", length = 36, nullable = false)
  private String targetNodeId;

  @Column(name = "max_revisions", nullable = false)
  private int maxRevisions;

  @Column(name = "revision_count", nullable = false)
  private int revisionCount;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "last_verdict_json")
  private String lastVerdictJson;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  @Column(name = "updated_at", length = 40, nullable = false)
  private String updatedAt;

  protected PlanRevisionEdgeEntity() {}

  public PlanRevisionEdgeEntity(
      String id,
      String runId,
      String reviewerNodeId,
      String targetNodeId,
      int maxRevisions,
      int revisionCount,
      String lastVerdictJson,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.runId = runId;
    this.reviewerNodeId = reviewerNodeId;
    this.targetNodeId = targetNodeId;
    this.maxRevisions = maxRevisions;
    this.revisionCount = revisionCount;
    this.lastVerdictJson = lastVerdictJson;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getId() {
    return id;
  }

  public String getRunId() {
    return runId;
  }

  public String getReviewerNodeId() {
    return reviewerNodeId;
  }

  public String getTargetNodeId() {
    return targetNodeId;
  }

  public int getMaxRevisions() {
    return maxRevisions;
  }

  public int getRevisionCount() {
    return revisionCount;
  }

  public String getLastVerdictJson() {
    return lastVerdictJson;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void trigger(String verdictJson, String time) {
    this.revisionCount += 1;
    this.lastVerdictJson = verdictJson;
    this.updatedAt = time;
  }
}
