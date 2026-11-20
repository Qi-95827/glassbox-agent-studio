package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 已物化候选节点的文件范围、产出契约和验收条件；运行只能使用这里声明的读写集合。 */
@Entity
@Table(name = "plan_node_specs")
public class PlanNodeSpecEntity {
  @Id
  @Column(name = "plan_node_id", length = 36)
  private String planNodeId;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Column(name = "node_key", nullable = false, length = 64)
  private String nodeKey;

  @Column(name = "candidate_sha256", nullable = false, length = 64)
  private String candidateSha256;

  @Lob
  @Column(name = "read_files_json", nullable = false)
  private String readFilesJson;

  @Lob
  @Column(name = "write_files_json", nullable = false)
  private String writeFilesJson;

  @Lob
  @Column(name = "outputs_json", nullable = false)
  private String outputsJson;

  @Lob
  @Column(name = "acceptance_json", nullable = false)
  private String acceptanceJson;

  @Column(name = "spec_sha256", nullable = false, length = 64)
  private String specSha256;

  protected PlanNodeSpecEntity() {}

  public PlanNodeSpecEntity(
      String planNodeId,
      String runId,
      String nodeKey,
      String candidateSha256,
      String readFilesJson,
      String writeFilesJson,
      String outputsJson,
      String acceptanceJson,
      String specSha256) {
    this.planNodeId = planNodeId;
    this.runId = runId;
    this.nodeKey = nodeKey;
    this.candidateSha256 = candidateSha256;
    this.readFilesJson = readFilesJson;
    this.writeFilesJson = writeFilesJson;
    this.outputsJson = outputsJson;
    this.acceptanceJson = acceptanceJson;
    this.specSha256 = specSha256;
  }

  public String getPlanNodeId() {
    return planNodeId;
  }

  public String getRunId() {
    return runId;
  }

  public String getNodeKey() {
    return nodeKey;
  }

  public String getCandidateSha256() {
    return candidateSha256;
  }

  public String getReadFilesJson() {
    return readFilesJson;
  }

  public String getWriteFilesJson() {
    return writeFilesJson;
  }

  public String getOutputsJson() {
    return outputsJson;
  }

  public String getAcceptanceJson() {
    return acceptanceJson;
  }

  public String getSpecSha256() {
    return specSha256;
  }
}
