package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 外部框架推送的单个 Span：只保存已脱敏的名称与属性，不保存任何原始提示词或密钥。 */
@Entity
@Table(name = "external_trace_spans")
public class ExternalTraceSpanEntity {
  @Id
  @Column(name = "id", length = 64, nullable = false)
  private String id;

  @Column(name = "run_id", length = 64, nullable = false)
  private String runId;

  @Column(name = "seq", nullable = false)
  private int seq;

  @Column(name = "kind", length = 32, nullable = false)
  private String kind;

  @Column(name = "name", length = 200, nullable = false)
  private String name;

  @Column(name = "node_key", length = 64)
  private String nodeKey;

  @Column(name = "status", length = 32)
  private String status;

  @Column(name = "started_at", length = 64)
  private String startedAt;

  @Column(name = "ended_at", length = 64)
  private String endedAt;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "attributes_json")
  private String attributesJson;

  @Column(name = "created_at", length = 64, nullable = false)
  private String createdAt;

  protected ExternalTraceSpanEntity() {}

  public ExternalTraceSpanEntity(
      String id,
      String runId,
      int seq,
      String kind,
      String name,
      String nodeKey,
      String status,
      String startedAt,
      String endedAt,
      String attributesJson,
      String createdAt) {
    this.id = id;
    this.runId = runId;
    this.seq = seq;
    this.kind = kind;
    this.name = name;
    this.nodeKey = nodeKey;
    this.status = status;
    this.startedAt = startedAt;
    this.endedAt = endedAt;
    this.attributesJson = attributesJson;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public String getRunId() {
    return runId;
  }

  public int getSeq() {
    return seq;
  }

  public String getKind() {
    return kind;
  }

  public String getName() {
    return name;
  }

  public String getNodeKey() {
    return nodeKey;
  }

  public String getStatus() {
    return status;
  }

  public String getStartedAt() {
    return startedAt;
  }

  public String getEndedAt() {
    return endedAt;
  }

  public String getAttributesJson() {
    return attributesJson;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
