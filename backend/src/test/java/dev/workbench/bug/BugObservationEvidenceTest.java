package dev.workbench.bug;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 观察证据必须绑定本次运行，且按 Unicode 码点截断。 */
class BugObservationEvidenceTest {
  @Test
  void acceptsOnlyMatchingObservationAndDoesNotSplitUnicode() {
    String check = UUID.randomUUID().toString();
    String run = UUID.randomUUID().toString();
    String workspace = UUID.randomUUID().toString();
    String hash = "a".repeat(64);
    String output = "😀".repeat(4000) + "尾";
    var valid =
        new BugService.Check(
            check,
            "MOCK-PY-001",
            workspace,
            BugService.CheckKind.REPRODUCE,
            BugService.CheckStatus.FAILURES_OBSERVED,
            output,
            1,
            "2026-01-01T00:00:00Z",
            hash,
            run);
    var evidence = BugObservationEvidence.from(valid, run, "MOCK-PY-001", workspace);
    assertEquals(UUID.fromString(check), evidence.checkId());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BugObservationEvidence(
                null,
                UUID.fromString(run),
                "MOCK-PY-001",
                UUID.fromString(workspace),
                BugService.CheckKind.REPRODUCE,
                BugService.CheckStatus.FAILURES_OBSERVED,
                hash,
                "输出"));
    assertEquals(4000, evidence.output().codePointCount(0, evidence.output().length()));
    assertTrue(evidence.output().endsWith("😀"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BugObservationEvidence.from(
                valid, UUID.randomUUID().toString(), "MOCK-PY-001", workspace));
    assertThrows(
        IllegalArgumentException.class,
        () -> BugObservationEvidence.from(valid, run, "OTHER", workspace));
    assertThrows(
        IllegalArgumentException.class,
        () -> BugObservationEvidence.from(valid, run, "MOCK-PY-001", UUID.randomUUID().toString()));
    var badHash =
        new BugService.Check(
            check,
            "MOCK-PY-001",
            workspace,
            BugService.CheckKind.REPRODUCE,
            BugService.CheckStatus.FAILURES_OBSERVED,
            output,
            1,
            "2026-01-01T00:00:00Z",
            "坏摘要",
            run);
    assertThrows(
        IllegalArgumentException.class,
        () -> BugObservationEvidence.from(badHash, run, "MOCK-PY-001", workspace));
  }
}
