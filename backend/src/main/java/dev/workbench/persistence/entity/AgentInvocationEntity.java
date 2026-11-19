package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_invocations")
public class AgentInvocationEntity extends InvocationEntity {
  @Column(name = "role_name", length = 80, nullable = false)
  private String roleName;

  @Column(name = "task_goal", length = 500, nullable = false)
  private String taskGoal;

  @Column(name = "result", length = 2000)
  private String result;

  @Column(name = "role_code", length = 30)
  private String roleCode;

  @Column(name = "role_version")
  private Integer roleVersion;

  @Column(name = "definition_id", length = 64)
  private String definitionId;

  @Column(name = "definition_version")
  private Integer definitionVersion;

  @Column(name = "definition_sha256", length = 64)
  private String definitionSha256;

  protected AgentInvocationEntity() {}

  public AgentInvocationEntity(
      String id,
      String runId,
      String time,
      String roleName,
      String taskGoal,
      String roleCode,
      Integer roleVersion) {
    super(id, runId, time);
    this.roleName = roleName;
    this.taskGoal = taskGoal;
    this.roleCode = roleCode;
    this.roleVersion = roleVersion;
  }
}
