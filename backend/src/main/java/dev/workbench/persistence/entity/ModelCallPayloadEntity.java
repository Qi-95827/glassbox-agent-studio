package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 单次模型调用的完整请求与响应透视快照（脱敏后存储）。 */
@Entity
@Table(name = "model_call_payloads")
public class ModelCallPayloadEntity {
  @Id
  @Column(name = "model_call_id", length = 36, nullable = false)
  private String modelCallId;

  @Column(name = "run_id", length = 36, nullable = false)
  private String runId;

  @Lob
  @Column(name = "request_messages_json")
  private String requestMessagesJson;

  @Lob
  @Column(name = "request_tools_json")
  private String requestToolsJson;

  @Lob
  @Column(name = "response_content")
  private String responseContent;

  @Lob
  @Column(name = "response_tool_calls_json")
  private String responseToolCallsJson;

  @Column(name = "truncated", nullable = false)
  private boolean truncated;

  @Lob
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
