package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 动态工作流节点边界，保持历史 H2 检查点的字段编码。 */
@Entity
@Table(name = "workflow_checkpoints")
public class WorkflowCheckpointEntity {
  public static final int CURRENT_FORMAT_VERSION = 1;
  private static final int LEGACY_TOOL_COUNT = 0;

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(length = 36)
  private String id;

  @Column(name = "plan_id", nullable = false, length = 36)
  private String planId;

  @Column(nullable = false)
  private int version;

  @Column(name = "completed_nodes", nullable = false)
  private int completedNodes;

  @Column(nullable = false)
  private int rounds;

  // 旧列有 tools=0 的数据库约束。实际工具消耗存放在 tool_calls。
  @Column(name = "tools", nullable = false, updatable = false)
  private int legacyTools;

  @Column(name = "tool_calls")
  private Integer toolCalls;

  @Column(name = "elapsed_ms", nullable = false)
  private long elapsedMs;

  @Column(name = "boundary_sha256", nullable = false, length = 64)
  private String boundarySha256;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected WorkflowCheckpointEntity() {}

  public WorkflowCheckpointEntity(
      String planId,
      int completedNodes,
      int rounds,
      int toolCalls,
      long elapsedMs,
      String boundarySha256,
      String createdAt) {
    this.planId = planId;
    this.version = CURRENT_FORMAT_VERSION;
    this.completedNodes = completedNodes;
    this.rounds = rounds;
    this.legacyTools = LEGACY_TOOL_COUNT;
    this.toolCalls = toolCalls;
    this.elapsedMs = elapsedMs;
    this.boundarySha256 = boundarySha256;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public int getVersion() {
    return version;
  }

  public int getCompletedNodes() {
    return completedNodes;
  }

  public int getRounds() {
    return rounds;
  }

  public int getToolCalls() {
    return resolveToolCalls(toolCalls);
  }

  /** 已有检查点缺少 tool_calls 时按旧边界的零工具次数解释。 */
  public static int resolveToolCalls(Integer storedToolCalls) {
    return storedToolCalls == null ? LEGACY_TOOL_COUNT : storedToolCalls;
  }

  public long getElapsedMs() {
    return elapsedMs;
  }

  public String getBoundarySha256() {
    return boundarySha256;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
