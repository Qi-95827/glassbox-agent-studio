package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 运行创建前的规划候选。
 *
 * <p>保存归属、输入快照、模型原文的归一化结果、验证结论和用量。候选消耗单独归属， 不代表执行节点调用；模型隐藏推理和凭据不写入该表。
 */
@Entity
@Table(name = "plan_candidates")
public class PlanCandidateEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "owner_scope_key", nullable = false, length = 80)
  private String ownerScopeKey;

  @Column(name = "actor_key", nullable = false, length = 80)
  private String actorKey;

  /** 同组输入（目标、授权文件和入口）的稳定标识；版本在组内递增。 */
  @Column(name = "group_id", nullable = false, length = 64)
  private String groupId;

  @Column(name = "input_version", nullable = false)
  private int inputVersion;

  /** 客户端请求标识；同归属下唯一，用于安全重试。 */
  @Column(name = "request_id", nullable = false, length = 80)
  private String requestId;

  @Column(name = "source_candidate_id", length = 36)
  private String sourceCandidateId;

  @Column(name = "schema_version", nullable = false)
  private int schemaVersion;

  @Column(name = "input_sha256", nullable = false, length = 64)
  private String inputSha256;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "input_json", nullable = false)
  private String inputJson;

  /** 规划时的已有工作区版本；独立交付在确认前为空。 */
  @Column(name = "workspace_fingerprint", length = 64)
  private String workspaceFingerprint;

  /** 规划时每个授权文件的 SHA-256；确认运行时逐项复核。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "file_versions_json")
  private String fileVersionsJson;

  @Column(name = "candidate_sha256", length = 64)
  private String candidateSha256;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "candidate_json")
  private String candidateJson;

  /** 已解析的节点 Agent 版本与选择理由；确认和执行不重新猜测。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "agent_bindings_json")
  private String agentBindingsJson;

  /** 发起规划时冻结的 P1 短引用与精确版本；响应和确认不能改用更新版本。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "agent_aliases_json")
  private String agentAliasesJson;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "errors_json")
  private String errorsJson;

  @Column(name = "model_name", length = 80)
  private String modelName;

  @Column(name = "input_tokens")
  private Integer inputTokens;

  @Column(name = "output_tokens")
  private Integer outputTokens;

  /** 独立交付确认时准备的工作区；已有工作区沿用输入快照。 */
  @Column(name = "prepared_workspace_id", length = 36)
  private String preparedWorkspaceId;

  /** PREPARING/READY；Git 结果未知时保留 PREPARING 等待人工核对。 */
  @Column(name = "preparation_status", length = 20)
  private String preparationStatus;

  /** 独立工作区准备操作标识；目录名固定为 idea-&lt;id&gt;，同一候选在接管前后保持不变。 */
  @Column(name = "workspace_operation_id", length = 36)
  private String workspaceOperationId;

  /** 当前准备租约持有者；接管时更换，结算必须绑定该标识。 */
  @Column(name = "preparation_id", length = 36)
  private String preparationId;

  /** 本次准备开始时间；用于区分活跃操作与中断遗留。 */
  @Column(name = "preparation_started_at", length = 40)
  private String preparationStartedAt;

  /** 确认后创建的唯一运行；重复确认返回同一标识。 */
  @Column(name = "run_id", length = 36)
  private String runId;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "settled_at", length = 40)
  private String settledAt;

  protected PlanCandidateEntity() {}

  public PlanCandidateEntity(
      String id,
      String ownerScopeKey,
      String actorKey,
      String groupId,
      int inputVersion,
      String requestId,
      String sourceCandidateId,
      int schemaVersion,
      String inputSha256,
      String inputJson,
      String workspaceFingerprint,
      String fileVersionsJson,
      String status,
      String createdAt) {
    this.id = id;
    this.ownerScopeKey = ownerScopeKey;
    this.actorKey = actorKey;
    this.groupId = groupId;
    this.inputVersion = inputVersion;
    this.requestId = requestId;
    this.sourceCandidateId = sourceCandidateId;
    this.schemaVersion = schemaVersion;
    this.inputSha256 = inputSha256;
    this.inputJson = inputJson;
    this.workspaceFingerprint = workspaceFingerprint;
    this.fileVersionsJson = fileVersionsJson;
    this.status = status;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public String getOwnerScopeKey() {
    return ownerScopeKey;
  }

  public String getActorKey() {
    return actorKey;
  }

  public String getGroupId() {
    return groupId;
  }

  public int getInputVersion() {
    return inputVersion;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getSourceCandidateId() {
    return sourceCandidateId;
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  public String getInputSha256() {
    return inputSha256;
  }

  public String getInputJson() {
    return inputJson;
  }

  public String getWorkspaceFingerprint() {
    return workspaceFingerprint;
  }

  public String getFileVersionsJson() {
    return fileVersionsJson;
  }

  public String getPreparedWorkspaceId() {
    return preparedWorkspaceId;
  }

  public String getPreparationStatus() {
    return preparationStatus;
  }

  public String getPreparationId() {
    return preparationId;
  }

  public String getWorkspaceOperationId() {
    return workspaceOperationId;
  }

  public String getPreparationStartedAt() {
    return preparationStartedAt;
  }

  public String getRunId() {
    return runId;
  }

  public String getCandidateSha256() {
    return candidateSha256;
  }

  public String getCandidateJson() {
    return candidateJson;
  }

  public String getAgentBindingsJson() {
    return agentBindingsJson;
  }

  public void setAgentBindingsJson(String agentBindingsJson) {
    this.agentBindingsJson = agentBindingsJson;
  }

  public String getAgentAliasesJson() {
    return agentAliasesJson;
  }

  public void setAgentAliasesJson(String agentAliasesJson) {
    this.agentAliasesJson = agentAliasesJson;
  }

  public String getStatus() {
    return status;
  }

  public String getErrorsJson() {
    return errorsJson;
  }

  public String getModelName() {
    return modelName;
  }

  public Integer getInputTokens() {
    return inputTokens;
  }

  public Integer getOutputTokens() {
    return outputTokens;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getSettledAt() {
    return settledAt;
  }
}
