package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tool_attempt_fingerprints")
public class ToolAttemptEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "run_id", length = 36, nullable = false)
  private String runId;

  @Column(name = "tool_id", length = 36, nullable = false)
  private String toolId;

  @Column(name = "kind", length = 40, nullable = false)
  private String kind;

  @Column(name = "fingerprint", length = 64, nullable = false)
  private String fingerprint;

  protected ToolAttemptEntity() {}

  public ToolAttemptEntity(String runId, String toolId, String kind, String fingerprint) {
    this.runId = runId;
    this.toolId = toolId;
    this.kind = kind;
    this.fingerprint = fingerprint;
  }
}
