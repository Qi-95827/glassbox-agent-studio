package dev.workbench.evaluation;

import dev.workbench.bug.BugService;
import dev.workbench.bug.BugService.CheckKind;
import dev.workbench.bug.BugService.CheckStatus;
import dev.workbench.workspace.WorkspaceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 基于本次运行与工作区当前状态生成只读证据判断，不启动测试或修改代码。 */
@Service
public class BugEvidenceAssessmentService {
  private final JdbcTemplate db;
  private final BugService bugs;
  private final WorkspaceService workspaces;

  public BugEvidenceAssessmentService(
      JdbcTemplate db, BugService bugs, WorkspaceService workspaces) {
    this.db = db;
    this.bugs = bugs;
    this.workspaces = workspaces;
  }

  public BugEvidenceAssessment assess(String runId) {
    var task =
        db.queryForMap(
            "SELECT t.bug_id,t.workspace_id FROM runs r JOIN tasks t ON t.id=r.task_id WHERE"
                + " r.id=?",
            runId);
    String bugId = (String) task.get("BUG_ID");
    if (bugId == null)
      return new BugEvidenceAssessment(
          runId,
          null,
          BugEvidenceAssessment.Observation.NOT_APPLICABLE,
          BugEvidenceAssessment.Regression.NOT_APPLICABLE,
          null,
          List.of(),
          List.of(),
          0);
    String workspace = (String) task.get("WORKSPACE_ID");
    String current = null;
    if (workspace != null) {
      try {
        current = workspaces.verificationFingerprint(workspace);
      } catch (RuntimeException ignored) {
        /* 无法确认代码状态时不推断结果。 */
      }
    }
    var all = bugs.checksForRun(runId);
    List<Evidence> valid = new ArrayList<>();
    for (var row : all) {
      if (!bugId.equals(row.get("BUG_ID")) || !Objects.equals(workspace, row.get("WORKSPACE_ID")))
        continue;
      parseEvidence(row).ifPresent(valid::add);
    }
    var result = assessEvidence(runId, bugId, current, valid);
    return new BugEvidenceAssessment(
        result.runId(),
        result.bugId(),
        result.observation(),
        result.regression(),
        result.currentFingerprint(),
        result.observationEvidenceIds(),
        result.regressionEvidenceIds(),
        all.size() - valid.size());
  }

  /** 数据库边界显式转换类型；未知编码或摘要损坏不能当作有效证据。 */
  private static java.util.Optional<Evidence> parseEvidence(Map<String, Object> row) {
    try {
      var id = UUID.fromString(Objects.requireNonNull(row.get("ID")).toString());
      var kind = CheckKind.valueOf(Objects.requireNonNull(row.get("KIND")).toString());
      var status = CheckStatus.valueOf(Objects.requireNonNull(row.get("STATUS")).toString());
      String fingerprint = (String) row.get("WORKSPACE_FINGERPRINT");
      if (fingerprint != null && !fingerprint.matches("[0-9a-f]{64}"))
        return java.util.Optional.empty();
      if (kind == CheckKind.REPRODUCE
          && (status == CheckStatus.PASSED || status == CheckStatus.FAILED))
        return java.util.Optional.empty();
      if (kind == CheckKind.REGRESSION
          && (status == CheckStatus.FAILURES_OBSERVED || status == CheckStatus.NOT_REPRODUCED))
        return java.util.Optional.empty();
      return java.util.Optional.of(new Evidence(id, kind, status, fingerprint));
    } catch (NullPointerException | IllegalArgumentException | ClassCastException e) {
      return java.util.Optional.empty();
    }
  }

  /** 相同代码状态出现互相矛盾的结果时保留冲突，不挑选较有利的记录。 */
  static BugEvidenceAssessment assessEvidence(
      String runId, String bugId, String current, List<Evidence> checks) {
    var observed =
        checks.stream()
            .filter(
                c ->
                    c.kind() == CheckKind.REPRODUCE
                        && c.status() == CheckStatus.FAILURES_OBSERVED
                        && c.fingerprint() != null)
            .toList();
    var notObserved =
        checks.stream()
            .filter(
                c ->
                    c.kind() == CheckKind.REPRODUCE
                        && c.status() == CheckStatus.NOT_REPRODUCED
                        && c.fingerprint() != null)
            .toList();
    boolean contradictory =
        observed.stream()
            .anyMatch(
                a ->
                    notObserved.stream()
                        .anyMatch(b -> Objects.equals(a.fingerprint(), b.fingerprint())));
    var observation =
        contradictory
            ? BugEvidenceAssessment.Observation.CONFLICT
            : !observed.isEmpty()
                ? BugEvidenceAssessment.Observation.FAILURE_OBSERVED
                : !notObserved.isEmpty()
                    ? BugEvidenceAssessment.Observation.FAILURE_NOT_OBSERVED
                    : BugEvidenceAssessment.Observation.UNCONFIRMED;
    var observationIds =
        java.util.stream.Stream.concat(observed.stream(), notObserved.stream())
            .map(c -> c.id().toString())
            .toList();
    var matching =
        current == null
            ? List.<Evidence>of()
            : checks.stream()
                .filter(
                    c ->
                        c.kind() == CheckKind.REGRESSION
                            && current.equals(c.fingerprint())
                            && (c.status() == CheckStatus.PASSED
                                || c.status() == CheckStatus.FAILED))
                .toList();
    boolean pass = matching.stream().anyMatch(c -> c.status() == CheckStatus.PASSED);
    boolean fail = matching.stream().anyMatch(c -> c.status() == CheckStatus.FAILED);
    var regression =
        pass && fail
            ? BugEvidenceAssessment.Regression.CONFLICT
            : pass
                ? BugEvidenceAssessment.Regression.PASSED_CURRENT_VERSION
                : fail
                    ? BugEvidenceAssessment.Regression.FAILED_CURRENT_VERSION
                    : BugEvidenceAssessment.Regression.UNCONFIRMED;
    return new BugEvidenceAssessment(
        runId,
        bugId,
        observation,
        regression,
        current,
        observationIds,
        matching.stream().map(c -> c.id().toString()).toList(),
        0);
  }

  /** 已通过字段及枚举校验的单条运行检测记录。 */
  record Evidence(UUID id, CheckKind kind, CheckStatus status, String fingerprint) {}
}
