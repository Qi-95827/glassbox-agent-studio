package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 探测后缓存的 MCP 工具清单与单工具启停/风险。 */
@Entity
@Table(name = "user_mcp_tools")
public class UserMcpToolEntity {
  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "server_id", length = 36, nullable = false)
  private String serverId;

  @Column(name = "name", length = 120, nullable = false)
  private String name;

  @Column(name = "description", length = 1000)
  private String description;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "input_schema_json")
  private String inputSchemaJson;

  @Column(name = "risk_level", length = 20, nullable = false)
  private String riskLevel;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  @Column(name = "updated_at", length = 40, nullable = false)
  private String updatedAt;

  protected UserMcpToolEntity() {}

  public UserMcpToolEntity(
      String id,
      String serverId,
      String name,
      String description,
      String inputSchemaJson,
      String riskLevel,
      boolean enabled,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.serverId = serverId;
    this.name = name;
    this.description = description;
    this.inputSchemaJson = inputSchemaJson;
    this.riskLevel = riskLevel == null ? "HIGH" : riskLevel;
    this.enabled = enabled;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getId() {
    return id;
  }

  public String getServerId() {
    return serverId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getInputSchemaJson() {
    return inputSchemaJson;
  }

  public String getRiskLevel() {
    return riskLevel;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void update(String riskLevel, boolean enabled, String updatedAt) {
    this.riskLevel = riskLevel;
    this.enabled = enabled;
    this.updatedAt = updatedAt;
  }
}
