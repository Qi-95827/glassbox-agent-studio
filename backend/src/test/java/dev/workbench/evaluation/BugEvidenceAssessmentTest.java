package dev.workbench.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.bug.BugService.CheckKind;
import dev.workbench.bug.BugService.CheckStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 证据评估只读取同次运行记录，不把旧版本或冲突结果当成通过。 */
class BugEvidenceAssessmentTest {
  private static BugEvidenceAssessmentService.Evidence evidence(
      String id, String kind, String status, String fingerprint) {
    return new BugEvidenceAssessmentService.Evidence(
        UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        CheckKind.valueOf(kind),
        CheckStatus.valueOf(status),
        fingerprint);
  }

  @Test
  void comparesActualCodeVersionAndPreservesContradictoryResults() {
    var failedObservation = evidence("a", "REPRODUCE", "FAILURES_OBSERVED", "old");
    var passedRegression = evidence("b", "REGRESSION", "PASSED", "new");
    var report =
        BugEvidenceAssessmentService.assessEvidence(
            "run", "bug", "new", List.of(failedObservation, passedRegression));
    assertEquals(BugEvidenceAssessment.Observation.FAILURE_OBSERVED, report.observation());
    assertEquals(BugEvidenceAssessment.Regression.PASSED_CURRENT_VERSION, report.regression());
    assertEquals(List.of(failedObservation.id().toString()), report.observationEvidenceIds());
    assertEquals(List.of(passedRegression.id().toString()), report.regressionEvidenceIds());
    var stale =
        BugEvidenceAssessmentService.assessEvidence(
            "run", "bug", "different", List.of(failedObservation, passedRegression));
    assertEquals(BugEvidenceAssessment.Regression.UNCONFIRMED, stale.regression());
    var unknown =
        BugEvidenceAssessmentService.assessEvidence("run", "bug", null, List.of(passedRegression));
    assertEquals(BugEvidenceAssessment.Regression.UNCONFIRMED, unknown.regression());
    var conflicting =
        BugEvidenceAssessmentService.assessEvidence(
            "run",
            "bug",
            "new",
            List.of(
                failedObservation,
                passedRegression,
                evidence("c", "REPRODUCE", "NOT_REPRODUCED", "old"),
                evidence("d", "REGRESSION", "FAILED", "new")));
    assertEquals(BugEvidenceAssessment.Observation.CONFLICT, conflicting.observation());
    assertEquals(BugEvidenceAssessment.Regression.CONFLICT, conflicting.regression());
    var missingFingerprint =
        BugEvidenceAssessmentService.assessEvidence(
            "run",
            "bug",
            "new",
            List.of(
                evidence("e", "REPRODUCE", "FAILURES_OBSERVED", null),
                evidence("f", "REGRESSION", "PASSED", null)));
    assertEquals(BugEvidenceAssessment.Observation.UNCONFIRMED, missingFingerprint.observation());
    assertEquals(BugEvidenceAssessment.Regression.UNCONFIRMED, missingFingerprint.regression());
  }
}
