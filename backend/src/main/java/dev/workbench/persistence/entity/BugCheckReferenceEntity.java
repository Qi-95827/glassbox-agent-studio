package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** 交接中用于核对检测记录归属的只读映射。 */
@Entity
@Immutable
@Table(name = "bug_checks")
public class BugCheckReferenceEntity {
  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "run_id", length = 36)
  private String runId;

  @Column(name = "bug_id", length = 80, nullable = false)
  private String bugId;

  @Column(name = "workspace_id", length = 36, nullable = false)
  private String workspaceId;

  @Column(name = "workspace_fingerprint", length = 64)
  private String workspaceFingerprint;

  @Column(length = 30, nullable = false)
  private String kind;
}
