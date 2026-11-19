package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 可核验的周期执行边界快照。 */
@Entity
@Table(name = "run_snapshots")
public class RunSnapshotEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Column(name = "plan_version_id", nullable = false, length = 36)
  private String planVersionId;

  @Column(name = "event_seq", nullable = false)
  private long eventSeq;

  @Column(name = "completed_nodes", nullable = false)
  private int completedNodes;

  @Column(name = "rounds", nullable = false)
  private int rounds;

  @Column(name = "tools", nullable = false)
  private int tools;

  @Column(name = "elapsed_ms", nullable = false)
  private long elapsedMs;

  @Column(name = "workspace_fingerprint", length = 64)
  private String workspaceFingerprint;

  @Column(name = "budget_sha256", nullable = false, length = 64)
  private String budgetSha256;

  @Column(name = "boundary_sha256", nullable = false, length = 64)
  private String boundarySha256;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected RunSnapshotEntity() {}

  public RunSnapshotEntity(
      String id,
      String runId,
      String planVersionId,
      long eventSeq,
      int completedNodes,
      int rounds,
      int tools,
      long elapsedMs,
      String workspaceFingerprint,
      String budgetSha256,
      String boundarySha256,
      String createdAt) {
    this.id = id;
    this.runId = runId;
    this.planVersionId = planVersionId;
    this.eventSeq = eventSeq;
    this.completedNodes = completedNodes;
    this.rounds = rounds;
    this.tools = tools;
    this.elapsedMs = elapsedMs;
    this.workspaceFingerprint = workspaceFingerprint;
    this.budgetSha256 = budgetSha256;
    this.boundarySha256 = boundarySha256;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public String getRunId() {
    return runId;
  }

  public String getPlanVersionId() {
    return planVersionId;
  }

  public long getEventSeq() {
    return eventSeq;
  }

  public int getCompletedNodes() {
    return completedNodes;
  }

  public int getRounds() {
    return rounds;
  }

  public int getTools() {
    return tools;
  }

  public long getElapsedMs() {
    return elapsedMs;
  }

  public String getWorkspaceFingerprint() {
    return workspaceFingerprint;
  }

  public String getBudgetSha256() {
    return budgetSha256;
  }

  public String getBoundarySha256() {
    return boundarySha256;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
