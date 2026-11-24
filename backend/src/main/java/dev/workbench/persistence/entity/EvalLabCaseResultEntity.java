package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 单题真实执行轨迹、断言明细与双轨裁判结果。 */
@Entity
@Table(name = "eval_lab_case_results")
public class EvalLabCaseResultEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "eval_run_id", nullable = false, length = 36)
  private String evalRunId;

  @Column(name = "category", nullable = false, length = 40)
  private String category;

  @Column(name = "sample_id", nullable = false, length = 80)
  private String sampleId;

  @Column(name = "sample_title_zh", length = 200)
  private String sampleTitleZh;

  @Column(name = "sample_title_en", length = 200)
  private String sampleTitleEn;

  @Column(name = "source_pack", length = 80)
  private String sourcePack;

  @Column(name = "repeat_index", nullable = false)
  private int repeatIndex;

  @Column(name = "linked_run_id", length = 36)
  private String linkedRunId;

  @Column(name = "passed", nullable = false)
  private boolean passed;

  @Column(name = "code_score", nullable = false)
  private double codeScore;

  @Column(name = "judge_score")
  private Double judgeScore;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "metrics_json", nullable = false)
  private String metricsJson;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "assertions_json", nullable = false)
  private String assertionsJson;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "judge_verdict_json")
  private String judgeVerdictJson;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "trace_snapshot_json")
  private String traceSnapshotJson;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "raw_output")
  private String rawOutput;

  @Column(name = "duration_ms", nullable = false)
  private long durationMs;

  @Column(name = "input_tokens", nullable = false)
  private int inputTokens;

  @Column(name = "output_tokens", nullable = false)
  private int outputTokens;

  @Column(name = "cost_usd", nullable = false)
  private double costUsd;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected EvalLabCaseResultEntity() {}

  public EvalLabCaseResultEntity(
      String id,
      String evalRunId,
      String category,
      String sampleId,
      String sampleTitleZh,
      String sampleTitleEn,
      String sourcePack,
      int repeatIndex,
      String linkedRunId,
      boolean passed,
      double codeScore,
      Double judgeScore,
      String metricsJson,
      String assertionsJson,
      String judgeVerdictJson,
      String traceSnapshotJson,
      String rawOutput,
      long durationMs,
      int inputTokens,
      int outputTokens,
      double costUsd,
      String createdAt) {
    this.id = id;
    this.evalRunId = evalRunId;
    this.category = category;
    this.sampleId = sampleId;
    this.sampleTitleZh = sampleTitleZh;
    this.sampleTitleEn = sampleTitleEn;
    this.sourcePack = sourcePack;
    this.repeatIndex = Math.max(1, repeatIndex);
    this.linkedRunId = linkedRunId;
    this.passed = passed;
    this.codeScore = Math.max(0, Math.min(1, codeScore));
    this.judgeScore = judgeScore;
    this.metricsJson = metricsJson;
    this.assertionsJson = assertionsJson;
    this.judgeVerdictJson = judgeVerdictJson;
    this.traceSnapshotJson = traceSnapshotJson;
    this.rawOutput = rawOutput;
    this.durationMs = Math.max(0, durationMs);
    this.inputTokens = Math.max(0, inputTokens);
    this.outputTokens = Math.max(0, outputTokens);
    this.costUsd = Math.max(0, costUsd);
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public String getEvalRunId() {
    return evalRunId;
  }

  public String getCategory() {
    return category;
  }

  public String getSampleId() {
    return sampleId;
  }

  public String getSampleTitleZh() {
    return sampleTitleZh;
  }

  public String getSampleTitleEn() {
    return sampleTitleEn;
  }

  public String getSourcePack() {
    return sourcePack;
  }

  public int getRepeatIndex() {
    return repeatIndex;
  }

  public String getLinkedRunId() {
    return linkedRunId;
  }

  public boolean isPassed() {
    return passed;
  }

  public double getCodeScore() {
    return codeScore;
  }

  public Double getJudgeScore() {
    return judgeScore;
  }

  public String getMetricsJson() {
    return metricsJson;
  }

  public String getAssertionsJson() {
    return assertionsJson;
  }

  public String getJudgeVerdictJson() {
    return judgeVerdictJson;
  }

  public String getTraceSnapshotJson() {
    return traceSnapshotJson;
  }

  public String getRawOutput() {
    return rawOutput;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public int getInputTokens() {
    return inputTokens;
  }

  public int getOutputTokens() {
    return outputTokens;
  }

  public double getCostUsd() {
    return costUsd;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
