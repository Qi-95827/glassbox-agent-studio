package dev.workbench.persistence.entity;

import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * plan_nodes 表映射。未映射的列由数据库默认值或既有 SQL 维护；节点状态迁移不经过 JPA。
 *
 * <p>同一实体同时承载兼容计划节点（plan_version_id 为空、使用 role_code）和版本化工作流节点（使用
 * plan_version_id、node_key、permission_role）。
 */
@Entity
@Table(name = "plan_nodes")
public class PlanNodeEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Column(name = "sequence_no", nullable = false)
  private int sequenceNo;

  @Column(name = "role_code", length = 30)
  private String roleCode;

  @Column(name = "prerequisite_role", length = 30)
  private String prerequisiteRole;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 30)
  private PlanNodeStatus status;

  @Column(name = "agent_id", length = 36, insertable = false)
  private String agentId;

  @Column(name = "source_agent_id", length = 36, insertable = false)
  private String sourceAgentId;

  @Column(name = "started_at", length = 40)
  private String startedAt;

  @Column(name = "finished_at", length = 40)
  private String finishedAt;

  @Column(name = "definition_id", length = 64)
  private String definitionId;

  @Column(name = "definition_version")
  private Integer definitionVersion;

  @Column(name = "definition_sha256", length = 64)
  private String definitionSha256;

  @Column(name = "plan_version_id", length = 36)
  private String planVersionId;

  @Column(name = "node_key", length = 64)
  private String nodeKey;

  @Column(name = "node_label", length = 120)
  private String nodeLabel;

  @Column(name = "phase_key", length = 64)
  private String phaseKey;

  @Column(name = "permission_role", length = 30)
  private String permissionRole;

  @Column(name = "workspace_read", nullable = false)
  private boolean workspaceRead;

  @Column(name = "workspace_write", nullable = false)
  private boolean workspaceWrite;

  @Column(name = "max_node_model_calls")
  private Integer maxNodeModelCalls;

  @Column(name = "max_node_tool_calls")
  private Integer maxNodeToolCalls;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "node_kind", nullable = false, length = 30)
  private PlanNodeKind nodeKind;

  @Column(name = "check_id", length = 64)
  private String checkId;

  @Column(name = "check_version")
  private Integer checkVersion;

  protected PlanNodeEntity() {}

  public PlanNodeEntity(String id, String runId, int sequenceNo, PlanNodeStatus status) {
    this.id = id;
    this.runId = runId;
    this.sequenceNo = sequenceNo;
    this.status = status;
    this.nodeKind = PlanNodeKind.AGENT;
  }

  public String getId() {
    return id;
  }

  public String getRunId() {
    return runId;
  }

  public int getSequenceNo() {
    return sequenceNo;
  }

  public String getRoleCode() {
    return roleCode;
  }

  public void setRoleCode(String roleCode) {
    this.roleCode = roleCode;
  }

  public String getPrerequisiteRole() {
    return prerequisiteRole;
  }

  public void setPrerequisiteRole(String prerequisiteRole) {
    this.prerequisiteRole = prerequisiteRole;
  }

  public PlanNodeStatus getStatus() {
    return status;
  }

  public String getDefinitionId() {
    return definitionId;
  }

  public void setDefinition(String id, Integer version, String sha256) {
    this.definitionId = id;
    this.definitionVersion = version;
    this.definitionSha256 = sha256;
  }

  public Integer getDefinitionVersion() {
    return definitionVersion;
  }

  public String getDefinitionSha256() {
    return definitionSha256;
  }

  public String getPlanVersionId() {
    return planVersionId;
  }

  public void setPlanVersionId(String planVersionId) {
    this.planVersionId = planVersionId;
  }

  public String getNodeKey() {
    return nodeKey;
  }

  public void setNodeKey(String nodeKey) {
    this.nodeKey = nodeKey;
  }

  public String getNodeLabel() {
    return nodeLabel;
  }

  public void setNodeLabel(String nodeLabel) {
    this.nodeLabel = nodeLabel;
  }

  public String getPhaseKey() {
    return phaseKey;
  }

  public void setPhaseKey(String phaseKey) {
    this.phaseKey = phaseKey;
  }

  public String getPermissionRole() {
    return permissionRole;
  }

  public void setPermissionRole(String permissionRole) {
    this.permissionRole = permissionRole;
  }

  public boolean isWorkspaceRead() {
    return workspaceRead;
  }

  public void setWorkspaceRead(boolean workspaceRead) {
    this.workspaceRead = workspaceRead;
  }

  public boolean isWorkspaceWrite() {
    return workspaceWrite;
  }

  public void setWorkspaceWrite(boolean workspaceWrite) {
    this.workspaceWrite = workspaceWrite;
  }

  public Integer getMaxNodeModelCalls() {
    return maxNodeModelCalls;
  }

  public void setMaxNodeModelCalls(Integer maxNodeModelCalls) {
    this.maxNodeModelCalls = maxNodeModelCalls;
  }

  public Integer getMaxNodeToolCalls() {
    return maxNodeToolCalls;
  }

  public void setMaxNodeToolCalls(Integer maxNodeToolCalls) {
    this.maxNodeToolCalls = maxNodeToolCalls;
  }

  public PlanNodeKind getNodeKind() {
    return nodeKind;
  }

  public void setNodeKind(PlanNodeKind nodeKind) {
    this.nodeKind = nodeKind;
  }

  public String getCheckId() {
    return checkId;
  }

  public void setCheck(String checkId, Integer checkVersion) {
    this.checkId = checkId;
    this.checkVersion = checkVersion;
  }

  public Integer getCheckVersion() {
    return checkVersion;
  }
}
