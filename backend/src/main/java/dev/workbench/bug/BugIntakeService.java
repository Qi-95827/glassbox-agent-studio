package dev.workbench.bug;

import dev.workbench.domain.TaskDefinition;
import dev.workbench.persistence.entity.BugIntakeEntity;
import dev.workbench.persistence.repository.BugIntakeRepository;
import dev.workbench.persistence.repository.BugRunSummary;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模拟 Bug 的原始描述、来源版本与分诊状态。
 *
 * <p>原始描述和来源在任务创建时保存；分诊结论只来自受信检查结果，模型不能改写。相同来源与内容会标记为重复提交，内容变化会链接上一份记录。
 */
@Service
public class BugIntakeService {
  private final BugIntakeRepository intakes;
  private final BugService bugs;
  private final WorkspaceService workspaces;

  public BugIntakeService(
      BugIntakeRepository intakes, BugService bugs, WorkspaceService workspaces) {
    this.intakes = intakes;
    this.bugs = bugs;
    this.workspaces = workspaces;
  }

  /** 保存本次任务的 Bug 原始描述与来源版本，并在同来源内判断重复或变化。 */
  @Transactional
  public synchronized Intake record(TaskDefinition task, String runId) {
    if (task.bugId() == null) return null;
    BugService.Bug bug =
        bugs.list().stream()
            .filter(item -> item.id().equals(task.bugId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("模拟 Bug 不存在。"));
    String description = task.goal();
    String hash =
        sha256(
            String.join(
                "\n",
                bug.id(),
                bug.source(),
                Integer.toString(bug.version()),
                description,
                bug.description(),
                bug.expectedSymptom(),
                bug.expectedTestId(),
                bug.expectedFailureText()));
    String workspaceKey = task.workspaceId() == null ? "" : task.workspaceId();
    List<BugIntakeEntity> previousRows =
        intakes.findPrevious(
            bug.id(),
            bug.source(),
            bug.version(),
            workspaceKey,
            PageRequest.of(0, 1));
    BugIntakeEntity previous = previousRows.isEmpty() ? null : previousRows.getFirst();
    String previousId = previous == null ? null : previous.getId();
    int sequence = previous == null ? 1 : previous.getSubmissionSeq() + 1;
    List<BugIntakeEntity> sameRows =
        intakes.findSameContent(
            bug.id(),
            bug.source(),
            bug.version(),
            workspaceKey,
            hash,
            PageRequest.of(0, 1));
    String duplicateOf = sameRows.isEmpty() ? null : sameRows.getFirst().getId();
    BugIntakeEntity row =
        new BugIntakeEntity(
            dev.workbench.task.RunRecordService.id(),
            bug.id(),
            bug.source(),
            bug.version(),
            description,
            bug.description(),
            bug.expectedSymptom(),
            bug.expectedTestId(),
            hash,
            task.workspaceId(),
            task.id().toString(),
            runId,
            sequence,
            duplicateOf,
            previousId,
            TriageStatus.PENDING.name(),
            dev.workbench.task.RunRecordService.now());
    intakes.saveAndFlush(row);
    return toIntake(row);
  }

  /** 复现检查是分诊的唯一来源；用户补充后的再次检查会覆盖为最新结论。 */
  @Transactional
  public void triage(BugService.Check check) {
    if (check == null || check.runId() == null) return;
    TriageStatus status = TriageStatus.from(check.status());
    intakes.updateTriage(
        check.runId(),
        status.name(),
        status.reason(check.status()),
        check.id(),
        dev.workbench.task.RunRecordService.now());
  }

  /** 回归结果只记录实际执行的目标用例结论，不改变原始描述。 */
  @Transactional
  public void regression(BugService.Check check) {
    if (check == null || check.runId() == null) return;
    intakes.updateRegression(
        check.runId(),
        check.status().name(),
        check.id(),
        dev.workbench.task.RunRecordService.now());
  }

  public Intake findByRun(String runId) {
    return intakes.findFirstByRunId(runId).map(BugIntakeService::toIntake).orElse(null);
  }

  public List<Intake> history(String workspaceId) {
    return intakes.findByWorkspaceIdOrderByCreatedAtDescIdDesc(workspaceId).stream()
        .map(BugIntakeService::toIntake)
        .toList();
  }

  /** 交付视图同时给出分诊、检查和结论；没有回归或未匹配时明确说明未验证。 */
  public Map<String, Object> delivery(String runId) {
    Intake intake = findByRun(runId);
    var checks = bugs.checksForRun(runId);
    Map<String, Object> reproduction = lastCheck(checks, "REPRODUCE");
    Map<String, Object> regression = lastCheck(checks, "REGRESSION");
    BugRunSummary run =
        intakes
            .findRunSummary(runId)
            .orElseThrow(() -> new EmptyResultDataAccessException(1));
    String currentFingerprint = null;
    boolean currentReadable = false;
    if (intake != null && intake.workspaceId() != null) {
      try {
        currentFingerprint = workspaces.verificationFingerprint(intake.workspaceId());
        currentReadable = true;
      } catch (RuntimeException ignored) {
        /* 工作区不可读时不能把历史回归当作当前版本证据。 */
      }
    }
    String regressionFingerprint =
        regression == null ? null : objectString(regression.get("WORKSPACE_FINGERPRINT"));
    boolean regressionEvidenceCurrent =
        regression != null
            && currentReadable
            && regressionFingerprint != null
            && regressionFingerprint.equals(currentFingerprint);
    String conclusion;
    String conclusionReason;
    if (intake == null) {
      conclusion = "NO_INTAKE";
      conclusionReason = "本次运行没有保存 Bug 原始描述记录，不能生成完整交付结论。";
    } else if (intake.triageStatus() == TriageStatus.PENDING) {
      conclusion = "PENDING";
      conclusionReason = "复现检查尚未完成，等待分诊。";
    } else if (intake.triageStatus() != TriageStatus.MATCHED) {
      conclusion = intake.triageStatus().name();
      conclusionReason = intake.triageReason();
    } else if (regression == null) {
      conclusion = "AWAITING_REGRESSION";
      conclusionReason = "目标现象已匹配，但尚无当前代码版本的回归检查。";
    } else if (!regressionEvidenceCurrent) {
      conclusion = "EVIDENCE_STALE";
      conclusionReason =
          "回归记录属于旧代码版本，当前版本尚未重新测试；历史结果保留，但不能作为当前版本交付依据，请重新运行回归。";
    } else if ("PASSED".equals(regression.get("STATUS"))) {
      conclusion = "WAITING_USER_REVIEW";
      conclusionReason = "修复与回归检查已完成，等待用户核对代码差异、原问题和验收条件；系统不会自动合并或关闭。";
    } else {
      conclusion = "REGRESSION_NOT_PASSED";
      conclusionReason = "目标用例未在回归中实际执行并通过，不能报告 Bug 已修复。";
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("runId", runId);
    result.put("runStatus", run.status());
    result.put("runReason", run.reason());
    result.put("intake", intake);
    result.put(
        "triage",
        intake == null
            ? null
            : Map.of(
                "status", intake.triageStatus().name(),
                "label", intake.triageStatus().label(),
                "reason", intake.triageReason() == null ? "" : intake.triageReason()));
    result.put("reproduction", reproduction);
    result.put("regression", regression);
    result.put("currentFingerprint", currentFingerprint);
    result.put("regressionFingerprint", regressionFingerprint);
    result.put("regressionEvidenceCurrent", regression == null ? null : regressionEvidenceCurrent);
    result.put("conclusion", conclusion);
    result.put(
        "conclusionLabel",
        switch (conclusion) {
          case "WAITING_USER_REVIEW" -> "等待用户核对";
          case "EVIDENCE_STALE" -> "证据已过期，需重新测试";
          case "REGRESSION_NOT_PASSED" -> "回归未通过";
          case "AWAITING_REGRESSION" -> "等待回归";
          case "NO_INTAKE" -> "缺少原始描述";
          case "PENDING" -> "待分诊";
          case "MATCHED" -> "目标现象匹配";
          case "NEEDS_INFO" -> "待补充";
          case "NOT_REPRODUCED" -> "未复现";
          case "ENVIRONMENT_UNAVAILABLE" -> "环境不可用";
          case "TARGET_MISMATCH" -> "现象不符";
          case "OTHER_FAILURE" -> "其他失败";
          default -> conclusion;
        });
    result.put("conclusionReason", conclusionReason);
    return result;
  }

  private static String objectString(Object value) {
    return value == null ? null : value.toString();
  }

  private static Map<String, Object> lastCheck(List<Map<String, Object>> checks, String kind) {
    return checks.stream()
        .filter(check -> kind.equals(check.get("KIND")))
        .reduce((first, second) -> second)
        .orElse(null);
  }

  private static Intake toIntake(BugIntakeEntity row) {
    return new Intake(
        row.getId(),
        row.getBugId(),
        row.getSource(),
        row.getSourceVersion(),
        row.getOriginalDescription(),
        row.getBugDescription(),
        row.getExpectedSymptom(),
        row.getExpectedTestId(),
        row.getContentSha256(),
        row.getWorkspaceId(),
        row.getTaskId(),
        row.getRunId(),
        row.getSubmissionSeq(),
        row.getDuplicateOf(),
        row.getPreviousIntakeId(),
        TriageStatus.valueOf(row.getTriageStatus()),
        row.getTriageReason(),
        row.getTriageCheckId(),
        row.getTriageUpdatedAt(),
        row.getRegressionStatus(),
        row.getRegressionCheckId(),
        row.getRegressionUpdatedAt(),
        row.getCreatedAt());
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("无法计算 Bug 描述摘要。", e);
    }
  }

  public enum TriageStatus {
    PENDING("待分诊"),
    MATCHED("目标现象匹配"),
    NEEDS_INFO("待补充"),
    NOT_REPRODUCED("未复现"),
    ENVIRONMENT_UNAVAILABLE("环境不可用"),
    TARGET_MISMATCH("现象不符"),
    OTHER_FAILURE("其他失败");

    private final String label;

    TriageStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    static TriageStatus from(BugService.CheckStatus status) {
      return switch (status) {
        case FAILURES_OBSERVED -> MATCHED;
        case NEEDS_INFO -> NEEDS_INFO;
        case NOT_REPRODUCED -> NOT_REPRODUCED;
        case ERROR, TIMEOUT, STALE -> ENVIRONMENT_UNAVAILABLE;
        case TARGET_MISMATCH, TARGET_NOT_EXECUTED, TARGET_SKIPPED -> TARGET_MISMATCH;
        case PASSED, FAILED, INTERRUPTED -> OTHER_FAILURE;
      };
    }

    String reason(BugService.CheckStatus status) {
      return switch (status) {
        case FAILURES_OBSERVED -> "目标测试用例实际执行失败，且断言来自该用例。";
        case NEEDS_INFO -> "缺少可执行测试或验证条件，等待用户补充。";
        case NOT_REPRODUCED -> "目标测试用例未观察到失败，不能进入修复。";
        case ERROR -> "容器或测试环境不可用，复现检查未完成。";
        case TIMEOUT -> "复现检查超时，无法确认目标现象。";
        case STALE -> "工作区在检查期间变化，结果不对应当前代码版本。";
        case TARGET_MISMATCH -> "存在测试失败，但未匹配目标用例与预期断言。";
        case TARGET_NOT_EXECUTED -> "未找到完整目标测试用例；相似名称不能替代。";
        case TARGET_SKIPPED -> "目标测试用例被跳过，未实际执行。";
        case INTERRUPTED -> "检查被中断，结果未知，按其他失败处理。";
        case PASSED, FAILED -> "复现检查返回了不适用的状态，按其他失败处理。";
      };
    }
  }

  public record Intake(
      String id,
      String bugId,
      String source,
      int sourceVersion,
      String originalDescription,
      String bugDescription,
      String expectedSymptom,
      String expectedTestId,
      String contentSha256,
      String workspaceId,
      String taskId,
      String runId,
      int submissionSeq,
      String duplicateOf,
      String previousIntakeId,
      TriageStatus triageStatus,
      String triageReason,
      String triageCheckId,
      String triageUpdatedAt,
      String regressionStatus,
      String regressionCheckId,
      String regressionUpdatedAt,
      String createdAt) {}
}
