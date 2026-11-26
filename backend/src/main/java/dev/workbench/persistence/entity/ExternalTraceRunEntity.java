package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 外部 Agent 框架接入的一条 Trace 运行。
 *
 * <p>与工作台运行严格分开：它没有计划、没有工具授权、没有检查点，只承载外部框架推送的 Span，
 * 因此在工作台里只能只读透视，不会参与任何执行、恢复或分叉。
 */
@Entity
@Table(name = "external_trace_runs")
public class ExternalTraceRunEntity {
  @Id
  @Column(name = "id", length = 64, nullable = false)
  private String id;

  @Column(name = "owner_scope_key", length = 128, nullable = false)
  private String ownerScopeKey;

  @Column(name = "name", length = 200, nullable = false)
  private String name;

  @Column(name = "source", length = 80, nullable = false)
  private String source;

  @Column(name = "created_at", length = 64, nullable = false)
  private String createdAt;

  protected ExternalTraceRunEntity() {}

  public ExternalTraceRunEntity(
      String id, String ownerScopeKey, String name, String source, String createdAt) {
    this.id = id;
    this.ownerScopeKey = ownerScopeKey;
    this.name = name;
    this.source = source;
    this.createdAt = createdAt;
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

  public String getSource() {
    return source;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
