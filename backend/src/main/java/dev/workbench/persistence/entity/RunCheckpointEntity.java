package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "checkpoints")
public class RunCheckpointEntity {
  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "run_id", length = 36, nullable = false)
  private String runId;

  @Column(name = "version", nullable = false)
  private Integer version;

  @Column(name = "stage", length = 30, nullable = false)
  private String stage;

  @Column(name = "rounds", nullable = false)
  private Integer rounds;

  @Column(name = "tools", nullable = false)
  private Integer tools;

  @Column(name = "elapsed_ms", nullable = false)
  private Long elapsedMs;

  @Column(name = "tool_result", length = 2000)
  private String toolResult;

  @Column(name = "event_id", nullable = false)
  private Long eventId;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  @Column(name = "checksum", length = 64)
  private String checksum;

  @Column(name = "engine", length = 30, nullable = false)
  private String engine;

  protected RunCheckpointEntity() {}

  public RunCheckpointEntity(
      String id,
      String runId,
      Integer version,
      String stage,
      Integer rounds,
      Integer tools,
      Long elapsedMs,
      String toolResult,
      Long eventId,
      String createdAt,
      String checksum,
      String engine) {
    this.id = id;
    this.runId = runId;
    this.version = version;
    this.stage = stage;
    this.rounds = rounds;
    this.tools = tools;
    this.elapsedMs = elapsedMs;
    this.toolResult = toolResult;
    this.eventId = eventId;
    this.createdAt = createdAt;
    this.checksum = checksum;
    this.engine = engine;
  }
}
