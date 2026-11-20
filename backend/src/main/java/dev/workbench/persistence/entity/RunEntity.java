package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 运行记录映射，恢复派发状态的初始值由数据库设置。 */
@Entity
@Table(name = "runs")
public class RunEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "task_id", nullable = false, length = 36)
  private String taskId;

  @Column(name = "parent_id", length = 36)
  private String parentId;

  @Column(name = "status", nullable = false, length = 30)
  private String status;

  @Column(name = "reason", length = 1000)
  private String reason;

  @Column(name = "result", length = 2000)
  private String result;

  @Column(name = "rounds", nullable = false)
  private int rounds;

  @Column(name = "tools", nullable = false)
  private int tools;

  @Column(name = "max_rounds")
  private Integer maxRounds;

  @Column(name = "max_tools")
  private Integer maxTools;

  @Column(name = "max_per_tool")
  private Integer maxPerTool;

  @Column(name = "max_reported_tokens")
  private Long maxReportedTokens;

  @Column(name = "max_identical_tools")
  private Integer maxIdenticalTools;

  @Column(name = "max_seconds")
  private Integer maxSeconds;

  @Column(name = "elapsed_ms", nullable = false)
  private long elapsedMs;

  @Column(name = "max_agent_rounds")
  private Integer maxAgentRounds;

  @Column(name = "max_agent_tools")
  private Integer maxAgentTools;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  @Column(name = "recovery_dispatch", nullable = false, length = 30, insertable = false)
  private String recoveryDispatch;

  @Column(name = "stop_cause", length = 40)
  private String stopCause;

  public RunEntity() {}

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    isNew = false;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getTaskId() {
    return taskId;
  }

  public void setTaskId(String taskId) {
    this.taskId = taskId;
  }

  public String getParentId() {
    return parentId;
  }

  public void setParentId(String parentId) {
    this.parentId = parentId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public String getResult() {
    return result;
  }

  public void setResult(String result) {
    this.result = result;
  }

  public int getRounds() {
    return rounds;
  }

  public void setRounds(int rounds) {
    this.rounds = rounds;
  }

  public int getTools() {
    return tools;
  }

  public void setTools(int tools) {
    this.tools = tools;
  }

  public Integer getMaxRounds() {
    return maxRounds;
  }

  public void setMaxRounds(Integer maxRounds) {
    this.maxRounds = maxRounds;
  }

  public Integer getMaxTools() {
    return maxTools;
  }

  public void setMaxTools(Integer maxTools) {
    this.maxTools = maxTools;
  }

  public Integer getMaxPerTool() {
    return maxPerTool;
  }

  public void setMaxPerTool(Integer maxPerTool) {
    this.maxPerTool = maxPerTool;
  }

  public Long getMaxReportedTokens() {
    return maxReportedTokens;
  }

  public void setMaxReportedTokens(Long maxReportedTokens) {
    this.maxReportedTokens = maxReportedTokens;
  }

  public Integer getMaxIdenticalTools() {
    return maxIdenticalTools;
  }

  public void setMaxIdenticalTools(Integer maxIdenticalTools) {
    this.maxIdenticalTools = maxIdenticalTools;
  }

  public Integer getMaxSeconds() {
    return maxSeconds;
  }

  public void setMaxSeconds(Integer maxSeconds) {
    this.maxSeconds = maxSeconds;
  }

  public long getElapsedMs() {
    return elapsedMs;
  }

  public void setElapsedMs(long elapsedMs) {
    this.elapsedMs = elapsedMs;
  }

  public Integer getMaxAgentRounds() {
    return maxAgentRounds;
  }

  public void setMaxAgentRounds(Integer maxAgentRounds) {
    this.maxAgentRounds = maxAgentRounds;
  }

  public Integer getMaxAgentTools() {
    return maxAgentTools;
  }

  public void setMaxAgentTools(Integer maxAgentTools) {
    this.maxAgentTools = maxAgentTools;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
