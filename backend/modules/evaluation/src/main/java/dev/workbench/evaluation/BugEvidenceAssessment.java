package dev.workbench.evaluation;

import java.util.List;

/** 只评价检测记录是否支持对应描述，不自动声称目标 Bug 已修复。 */
public record BugEvidenceAssessment(
    String runId,
    String bugId,
    Observation observation,
    Regression regression,
    String currentFingerprint,
    List<String> observationEvidenceIds,
    List<String> regressionEvidenceIds,
    int ignoredEvidenceCount) {
  public BugEvidenceAssessment {
    observationEvidenceIds = List.copyOf(observationEvidenceIds);
    regressionEvidenceIds = List.copyOf(regressionEvidenceIds);
  }

  public enum Observation {
    NOT_APPLICABLE,
    FAILURE_OBSERVED,
    FAILURE_NOT_OBSERVED,
    CONFLICT,
    UNCONFIRMED
  }

  public enum Regression {
    NOT_APPLICABLE,
    PASSED_CURRENT_VERSION,
    FAILED_CURRENT_VERSION,
    CONFLICT,
    UNCONFIRMED
  }
}
