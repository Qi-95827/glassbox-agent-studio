package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 单次模型调用的完整请求与响应透视快照（脱敏后存储）。 */
@Entity
@Table(name = "model_call_payloads")
public class ModelCallPayloadEntity {
  @Id
  @Column(name = "model_call_id", length = 36, nullable = false)
  private String modelCallId;

  @Column(name = "run_id", length = 36, nullable = false)
  private String runId;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "request_messages_json")
  private String requestMessagesJson;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "request_tools_json")
  private String requestToolsJson;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "response_content")
  private String responseContent;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "response_tool_calls_json")
  private String responseToolCallsJson;

  /** 推理模型的思考链（脱敏后）。与正文分开保存，避免思考链被当成答案展示。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "reasoning_content")
  private String reasoningContent;

  /** 本次调用是否以增量流式方式完成；历史行默认 false。 */
  @Column(name = "streamed", nullable = false)
  private boolean streamed;

  @Column(name = "truncated", nullable = false)
  private boolean truncated;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "context_breakdown_json")
  private String contextBreakdownJson;

  @Column(name = "compaction_tier", length = 30)
  private String compactionTier;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  protected ModelCallPayloadEntity() {}

  public ModelCallPayloadEntity(
      String modelCallId,
      String runId,
      String requestMessagesJson,
      String requestToolsJson,
      String responseContent,
      String responseToolCallsJson,
      boolean truncated,
      String createdAt) {
    this(
        modelCallId,
        runId,
        requestMessagesJson,
        requestToolsJson,
        responseContent,
        responseToolCallsJson,
        truncated,
        null,
        null,
        createdAt);
  }

  public ModelCallPayloadEntity(
      String modelCallId,
      String runId,
      String requestMessagesJson,
      String requestToolsJson,
      String responseContent,
      String responseToolCallsJson,
      boolean truncated,
      String contextBreakdownJson,
      String compactionTier,
      String createdAt) {
    this(
        modelCallId,
        runId,
        requestMessagesJson,
        requestToolsJson,
        responseContent,
        responseToolCallsJson,
        truncated,
        contextBreakdownJson,
        compactionTier,
        createdAt,
        null,
        false);
  }

  public ModelCallPayloadEntity(
      String modelCallId,
      String runId,
      String requestMessagesJson,
      String requestToolsJson,
      String responseContent,
      String responseToolCallsJson,
      boolean truncated,
      String contextBreakdownJson,
      String compactionTier,
      String createdAt,
      String reasoningContent,
      boolean streamed) {
    this.modelCallId = modelCallId;
    this.runId = runId;
    this.requestMessagesJson = requestMessagesJson;
    this.requestToolsJson = requestToolsJson;
    this.responseContent = responseContent;
    this.responseToolCallsJson = responseToolCallsJson;
    this.truncated = truncated;
    this.contextBreakdownJson = contextBreakdownJson;
    this.compactionTier = compactionTier;
    this.createdAt = createdAt;
    this.reasoningContent = reasoningContent;
    this.streamed = streamed;
  }

  public String getModelCallId() {
    return modelCallId;
  }

  public String getRunId() {
    return runId;
  }

  public String getRequestMessagesJson() {
    return requestMessagesJson;
  }

  public String getRequestToolsJson() {
    return requestToolsJson;
  }

  public String getResponseContent() {
    return responseContent;
  }

  public String getResponseToolCallsJson() {
    return responseToolCallsJson;
  }

  public boolean isTruncated() {
    return truncated;
  }

  public String getReasoningContent() {
    return reasoningContent;
  }

  public boolean isStreamed() {
    return streamed;
  }

  public String getContextBreakdownJson() {
    return contextBreakdownJson;
  }

  public String getCompactionTier() {
    return compactionTier;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
