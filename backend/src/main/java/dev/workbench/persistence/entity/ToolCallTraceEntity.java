package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 单次工具调用的入参、ToolGateway 安检明细与完整结果（脱敏后存储）。 */
@Entity
@Table(name = "tool_call_traces")
public class ToolCallTraceEntity {
  @Id
  @Column(name = "tool_call_id", length = 36, nullable = false)
  private String toolCallId;

  @Column(name = "run_id", length = 36, nullable = false)
  private String runId;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "input_args_json")
  private String inputArgsJson;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "gateway_checks_json")
  private String gatewayChecksJson;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "full_result")
  private String fullResult;

  @Column(name = "result_truncated", nullable = false)
  private boolean resultTruncated;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  protected ToolCallTraceEntity() {}

  public ToolCallTraceEntity(
      String toolCallId,
      String runId,
      String inputArgsJson,
      String gatewayChecksJson,
      String fullResult,
      boolean resultTruncated,
      String createdAt) {
    this.toolCallId = toolCallId;
    this.runId = runId;
    this.inputArgsJson = inputArgsJson;
    this.gatewayChecksJson = gatewayChecksJson;
    this.fullResult = fullResult;
    this.resultTruncated = resultTruncated;
    this.createdAt = createdAt;
  }

  public String getToolCallId() {
    return toolCallId;
  }

  public String getRunId() {
    return runId;
  }

  public String getInputArgsJson() {
    return inputArgsJson;
  }

  public String getGatewayChecksJson() {
    return gatewayChecksJson;
  }

  public String getFullResult() {
    return fullResult;
  }

  public boolean isResultTruncated() {
    return resultTruncated;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
