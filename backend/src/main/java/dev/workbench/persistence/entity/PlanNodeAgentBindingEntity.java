package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 计划节点最终使用的 Agent 定义版本、来源和选择理由；运行后不再重算。 */
@Entity
@Table(name = "plan_node_agent_bindings")
public class PlanNodeAgentBindingEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "node_id", length = 36)
  private String nodeId;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Column(name = "agent_kind", nullable = false, length = 20)
  private String agentKind;

  @Column(name = "definition_id", nullable = false, length = 64)
  private String definitionId;

  @Column(name = "definition_version", nullable = false)
  private int definitionVersion;

  @Column(name = "spec_sha256", length = 64)
  private String specSha256;

  @Column(name = "source", nullable = false, length = 20)
  private String source;

  @Column(name = "reason", nullable = false, length = 1000)
  private String reason;

  @Column(name = "display_name", nullable = false, length = 120)
  private String displayName;

  @Column(name = "components_json", columnDefinition = "CLOB")
  private String componentsJson;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected PlanNodeAgentBindingEntity() {}

  public PlanNodeAgentBindingEntity(
      String nodeId,
      String runId,
      String agentKind,
      String definitionId,
      int definitionVersion,
      String specSha256,
      String source,
      String reason,
      String displayName,
      String componentsJson,
      String createdAt) {
    this.nodeId = nodeId;
    this.runId = runId;
    this.agentKind = agentKind;
    this.definitionId = definitionId;
    this.definitionVersion = definitionVersion;
    this.specSha256 = specSha256;
    this.source = source;
    this.reason = reason;
    this.displayName = displayName;
    this.componentsJson = componentsJson;
    this.createdAt = createdAt;
  }

  @Override
  public String getId() {
    return nodeId;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    isNew = false;
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getRunId() {
    return runId;
  }

  public String getAgentKind() {
    return agentKind;
  }

  public String getDefinitionId() {
    return definitionId;
  }

  public int getDefinitionVersion() {
    return definitionVersion;
  }

  public String getSpecSha256() {
    return specSha256;
  }

  public String getSource() {
    return source;
  }

  public String getReason() {
    return reason;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getComponentsJson() {
    return componentsJson;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
