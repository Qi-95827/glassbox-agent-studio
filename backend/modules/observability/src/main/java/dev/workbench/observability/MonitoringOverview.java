package dev.workbench.observability;

import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.evaluation.CaseStatus;
import java.util.List;

/** 本机数据库的低敏聚合快照。空列表表示没有记录，不代表监控系统健康。 */
public record MonitoringOverview(
    List<RunCount> runs,
    List<InvocationCount> agents,
    List<InvocationCount> models,
    List<InvocationCount> tools,
    List<CaseCount> evaluationCases,
    List<StopCount> stopCauses,
    long missingStopCauses,
    long inconsistentStopCauses,
    Usage usage) {
  /** 未识别编码保留在 unrecognizedStatus，不强行映射成已知状态。 */
  public record RunCount(RunStatus status, String unrecognizedStatus, long count) {}

  public record InvocationCount(InvocationStatus status, String unrecognizedStatus, long count) {}

  public record CaseCount(CaseStatus status, String unrecognizedStatus, long count) {}

  /** 仅对枚举及其对应状态一致的原因计数。 */
  public record StopCount(StopCause cause, long count) {}

  /** 已知合计为 null 表示无已知数据；耗时单位为毫秒。 */
  public record Usage(
      Long knownInputTokens,
      long unknownInputCalls,
      Long knownOutputTokens,
      long unknownOutputCalls,
      Long knownModelDurationMs,
      long unknownModelDurationCalls,
      Long knownToolDurationMs,
      long unknownToolDurationCalls) {}
}
