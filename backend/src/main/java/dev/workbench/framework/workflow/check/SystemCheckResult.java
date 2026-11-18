package dev.workbench.framework.workflow.check;

import java.util.Objects;

/**
 * 系统检查的结算结果。
 *
 * <p>{@code summary} 会作为可交接的检查结论展示给下游；{@code evidence} 保存结构化说明（例如引用计数、
 * 缺失项），不包含文件正文或密钥。{@code externalEvidenceId} 指向系统内另一张权威证据表（例如 {@code
 * bug_checks}），用于核对归属而不复制其正文。{@code reasonCode} 是稳定原因编码，供阻塞分类和补充信息
 * 门槛使用；未分类时为空，读取方不得从消息文本反推。
 */
public record SystemCheckResult(
    SystemCheckStatus status,
    String summary,
    String evidence,
    String externalEvidenceId,
    String reasonCode) {

  /** 兼容既有构造：未分类原因的检查结果。 */
  public SystemCheckResult(
      SystemCheckStatus status, String summary, String evidence, String externalEvidenceId) {
    this(status, summary, evidence, externalEvidenceId, null);
  }

  public SystemCheckResult {
    Objects.requireNonNull(status, "检查状态不能为空。");
    if (status == SystemCheckStatus.RUNNING) throw new IllegalArgumentException("结算结果不能是运行中状态。");
    if (summary == null || summary.isBlank() || summary.length() > 1000)
      throw new IllegalArgumentException("检查摘要必须为 1 到 1000 个字符。");
    if (evidence != null && evidence.length() > 2000)
      throw new IllegalArgumentException("检查证据说明不能超过 2000 个字符。");
    if (externalEvidenceId != null
        && (externalEvidenceId.isBlank() || externalEvidenceId.length() > 80))
      throw new IllegalArgumentException("外部证据标识无效。");
    if (reasonCode != null && (reasonCode.isBlank() || reasonCode.length() > 50))
      throw new IllegalArgumentException("检查原因编码无效。");
  }

  public static SystemCheckResult passed(String summary) {
    return new SystemCheckResult(SystemCheckStatus.PASSED, summary, null, null);
  }

  public static SystemCheckResult passed(
      String summary, String evidence, String externalEvidenceId) {
    return new SystemCheckResult(SystemCheckStatus.PASSED, summary, evidence, externalEvidenceId);
  }

  public static SystemCheckResult failed(String summary, String evidence) {
    return new SystemCheckResult(SystemCheckStatus.FAILED, summary, evidence, null);
  }

  public static SystemCheckResult failed(
      String summary, String evidence, String externalEvidenceId) {
    return new SystemCheckResult(SystemCheckStatus.FAILED, summary, evidence, externalEvidenceId);
  }

  /** 带稳定原因编码的失败结果；用于区分“检查未通过”的具体原因。 */
  public static SystemCheckResult failed(
      String summary, String evidence, String externalEvidenceId, String reasonCode) {
    return new SystemCheckResult(
        SystemCheckStatus.FAILED, summary, evidence, externalEvidenceId, reasonCode);
  }

  public static SystemCheckResult blocked(String summary, String evidence) {
    return new SystemCheckResult(SystemCheckStatus.BLOCKED, summary, evidence, null);
  }

  public static SystemCheckResult blocked(
      String summary, String evidence, String externalEvidenceId) {
    return new SystemCheckResult(SystemCheckStatus.BLOCKED, summary, evidence, externalEvidenceId);
  }

  /** 带稳定原因编码的阻塞结果；编码用于补充信息门槛与聚合，不承载用户数据。 */
  public static SystemCheckResult blocked(
      String summary, String evidence, String externalEvidenceId, String reasonCode) {
    return new SystemCheckResult(
        SystemCheckStatus.BLOCKED, summary, evidence, externalEvidenceId, reasonCode);
  }
}
