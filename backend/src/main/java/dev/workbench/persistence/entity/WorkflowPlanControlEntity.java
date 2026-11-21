package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 运行级调试控制：执行模式（单步教学 / 并发）与节点断点集合。 */
@Entity
@Table(name = "workflow_plan_controls")
public class WorkflowPlanControlEntity {
  @Id
  @Column(name = "run_id", length = 36, nullable = false)
  private String runId;

  @Column(name = "execution_mode", length = 30, nullable = false)
  private String executionMode;

  @Lob
  @Column(name = "breakpoints_json", nullable = false)
  private String breakpointsJson;

  @Column(name = "updated_at", length = 40, nullable = false)
  private String updatedAt;

  protected WorkflowPlanControlEntity() {}

  public WorkflowPlanControlEntity(
      String runId, String executionMode, String breakpointsJson, String updatedAt) {
    this.runId = runId;
    this.executionMode = executionMode;
    this.breakpointsJson = breakpointsJson;
    this.updatedAt = updatedAt;
  }

  public String getRunId() {
    return runId;
  }

  public String getExecutionMode() {
    return executionMode;
  }

  public String getBreakpointsJson() {
    return breakpointsJson;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void update(String executionMode, String breakpointsJson, String updatedAt) {
    this.executionMode = executionMode;
    this.breakpointsJson = breakpointsJson;
    this.updatedAt = updatedAt;
  }
}
