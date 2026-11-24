package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 用户私有 MCP Server；headers/env 仅保存加密密文。 */
@Entity
@Table(name = "user_mcp_servers")
public class UserMcpServerEntity {
  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "owner_scope_key", length = 80, nullable = false)
  private String ownerScopeKey;

  @Column(name = "name", length = 120, nullable = false)
  private String name;

  @Column(name = "slug", length = 64, nullable = false)
  private String slug;

  @Column(name = "transport", length = 30, nullable = false)
  private String transport;

  @Column(name = "command", length = 200)
  private String command;

  @Column(name = "args_json", length = 2000, nullable = false)
  private String argsJson;

  @Column(name = "endpoint_url", length = 500)
  private String endpointUrl;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "headers_cipher")
  private String headersCipher;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "env_cipher")
  private String envCipher;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  @Column(name = "updated_at", length = 40, nullable = false)
  private String updatedAt;

  protected UserMcpServerEntity() {}

  public UserMcpServerEntity(
      String id,
      String ownerScopeKey,
      String name,
      String slug,
      String transport,
      String command,
      String argsJson,
      String endpointUrl,
      String headersCipher,
      String envCipher,
      boolean enabled,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.ownerScopeKey = ownerScopeKey;
    this.name = name;
    this.slug = slug;
    this.transport = transport;
    this.command = command;
    this.argsJson = argsJson == null ? "[]" : argsJson;
    this.endpointUrl = endpointUrl;
    this.headersCipher = headersCipher;
    this.envCipher = envCipher;
    this.enabled = enabled;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getId() {
    return id;
  }

  public String getOwnerScopeKey() {
    return ownerScopeKey;
  }

  public String getName() {
    return name;
  }

  public String getSlug() {
    return slug;
  }

  public String getTransport() {
    return transport;
  }

  public String getCommand() {
    return command;
  }

  public String getArgsJson() {
    return argsJson;
  }

  public String getEndpointUrl() {
    return endpointUrl;
  }

  public String getHeadersCipher() {
    return headersCipher;
  }

  public String getEnvCipher() {
    return envCipher;
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

  public void update(
      String name,
      String transport,
      String command,
      String argsJson,
      String endpointUrl,
      String headersCipher,
      String envCipher,
      boolean enabled,
      String updatedAt) {
    this.name = name;
    this.transport = transport;
    this.command = command;
    this.argsJson = argsJson;
    this.endpointUrl = endpointUrl;
    this.headersCipher = headersCipher;
    this.envCipher = envCipher;
    this.enabled = enabled;
    this.updatedAt = updatedAt;
  }
}
