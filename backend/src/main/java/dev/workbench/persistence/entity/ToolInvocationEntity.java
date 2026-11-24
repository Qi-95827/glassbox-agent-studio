package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tool_invocations")
public class ToolInvocationEntity extends InvocationEntity {
  @Column(name = "agent_id", length = 36, nullable = false)
  private String agentId;

  @Column(name = "tool_name", length = 80, nullable = false)
  private String toolName;

  @Column(name = "result", length = 2000)
  private String result;

  @Column(name = "error_message", length = 1000)
  private String errorMessage;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "tool_version")
  private Integer toolVersion;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "resource_version")
  private String resourceVersion;

  protected ToolInvocationEntity() {}

  public ToolInvocationEntity(
      String id, String runId, String time, String agentId, String toolName, Integer toolVersion) {
    super(id, runId, time);
    this.agentId = agentId;
    this.toolName = toolName;
    this.toolVersion = toolVersion;
  }
}
