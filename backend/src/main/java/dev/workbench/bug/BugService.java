package dev.workbench.bug;

import dev.workbench.persistence.entity.BugCheckCaseEntity;
import dev.workbench.persistence.entity.BugCheckEntity;
import dev.workbench.persistence.entity.BugFeedbackEntity;
import dev.workbench.persistence.repository.BugCheckCaseRepository;
import dev.workbench.persistence.repository.BugCheckRepository;
import dev.workbench.persistence.repository.BugFeedbackRepository;
import dev.workbench.task.RunRecordService;
import dev.workbench.workspace.ApprovalService;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedCaseInsensitiveMap;

/** 模拟 Bug 和受限验证；测试结果不能代替用户对真实代码的审查。 */
@Service
public class BugService {
  private static final String BUG_ID = "MOCK-PY-001";
  private static final String IMAGE = "python:3.12-alpine";
  private static final String RUNNING = "RUNNING";
  private final WorkspaceService workspaces;
  private final BugCheckRepository bugChecks;
  private final BugCheckCaseRepository bugCases;
  private final BugFeedbackRepository feedbackRows;
  private final ApprovalService approvals;
  private final TransactionTemplate tx;

  public BugService(
      WorkspaceService workspaces,
      BugCheckRepository bugChecks,
      BugCheckCaseRepository bugCases,
      BugFeedbackRepository feedbackRows,
      ApprovalService approvals,
      TransactionTemplate tx) {
    this.workspaces = workspaces;
    this.bugChecks = bugChecks;
    this.bugCases = bugCases;
    this.feedbackRows = feedbackRows;
    this.approvals = approvals;
    this.tx = tx;
  }

  private static final Bug SIMULATED_BUG =
      new Bug(
          BUG_ID,
          "Python 单元测试失败",
          "模拟一个 Python 项目的测试失败问题；需要在工作区存在 tests 目录及 unittest 测试。",
          "tests/test_*.py",
          "synthetic-local",
          1,
          "add(2, 1) 返回 1 而不是 3；unittest 中 test_add 断言失败（AssertionError: 1 != 3）。",
          "test_add",
          "test_calc.TestCalc.test_add",
          "AssertionError: 1 != 3");

  public List<Bug> list() {
    return List.of(SIMULATED_BUG);
  }

  /** 唯一受支持的模拟 Bug；检查匹配和证据文案使用同一份定义。 */
  public Bug simulatedBug() {
    return SIMULATED_BUG;
  }

  private static boolean matchesTargetSymptom(UnittestResults.Report report) {
    UnittestResults.Case target = report.find(SIMULATED_BUG.expectedTestId());
    return target != null
        && target.outcome() == UnittestResults.Outcome.FAILED
        && hasExactAssertion(target.message(), SIMULATED_BUG.expectedFailureText());
  }

  /** 断言按完整行比较；`1 != 3` 不能命中 `1 != 30`。 */
  private static boolean hasExactAssertion(String message, String expected) {
    String wanted = expected.strip();
    return message.lines().map(String::strip).anyMatch(wanted::equals);
  }

  /** 复现只接受目标用例自身输出的预期断言；其他用例的相同断言不能替代。 */
  static CheckStatus reproductionStatus(int exit, UnittestResults.Report report) {
    if (exit != 0 && exit != 1) return CheckStatus.ERROR;
    UnittestResults.Case target = report.find(SIMULATED_BUG.expectedTestId());
    if (exit == 0)
      return target != null && target.outcome() == UnittestResults.Outcome.SKIPPED
          ? CheckStatus.TARGET_SKIPPED
          : CheckStatus.NOT_REPRODUCED;
    if (target == null) return CheckStatus.TARGET_NOT_EXECUTED;
    if (target.outcome() == UnittestResults.Outcome.SKIPPED) return CheckStatus.TARGET_SKIPPED;
    return matchesTargetSymptom(report) ? CheckStatus.FAILURES_OBSERVED : CheckStatus.TARGET_MISMATCH;
  }

  /** 回归要求目标用例实际执行并通过；被跳过、缺失或其他失败都不能报告已修复。 */
  static CheckStatus regressionStatus(int exit, UnittestResults.Report report) {
    if (exit != 0 && exit != 1) return CheckStatus.ERROR;
    UnittestResults.Case target = report.find(SIMULATED_BUG.expectedTestId());
    if (target == null) return CheckStatus.TARGET_NOT_EXECUTED;
    if (target.outcome() == UnittestResults.Outcome.SKIPPED) return CheckStatus.TARGET_SKIPPED;
    if (target.outcome() != UnittestResults.Outcome.PASSED) return CheckStatus.FAILED;
    return exit == 0 ? CheckStatus.PASSED : CheckStatus.FAILED;
  }

  private static String bugContext() {
    return "Bug 来源："
        + SIMULATED_BUG.source()
        + " v"
        + SIMULATED_BUG.version()
        + "；目标用例："
        + SIMULATED_BUG.expectedTestId()
        + "；预期现象："
        + SIMULATED_BUG.expectedSymptom();
  }

  /** 逐用例证据随检查记录输出，便于用户核对断言来自哪个用例。 */
  private static String caseSummary(List<UnittestResults.Case> cases) {
    if (cases.isEmpty()) return "逐用例结果：未解析到测试用例。";
    StringBuilder summary = new StringBuilder("逐用例结果：");
    for (UnittestResults.Case test : cases) {
      summary
          .append("\n- ")
          .append(test.testId())
          .append(" [")
          .append(test.outcome())
          .append("]");
      if (!test.message().isBlank())
        summary.append(" ").append(test.message().replace('\n', ' '));
    }
    return summary.toString();
  }

  /** 当前受支持的模拟 Bug；未知标识在计划创建和检查执行时拒绝。 */
  public boolean supports(String bugId) {
    return BUG_ID.equals(bugId);
  }

  public List<Map<String, Object>> checks(String workspaceId) {
    return withCases(bugChecks.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId));
  }

  /** 只返回明确记录属于该运行的检测，历史和手动检测不猜测归属。 */
  public List<Map<String, Object>> checksForRun(String runId) {
    if (bugChecks.countRuns(runId) != 1)
      throw new org.springframework.dao.EmptyResultDataAccessException(1);
    return withCases(bugChecks.findByRunIdOrderByCreatedAtAscIdAsc(runId));
  }

  /** 逐用例证据一次读取后按检查分组，避免每个检测重复查询。 */
  private List<Map<String, Object>> withCases(List<BugCheckEntity> checks) {
    if (checks.isEmpty()) return List.of();
    List<String> ids = checks.stream().map(BugCheckEntity::getId).toList();
    Map<String, List<BugCheckCaseEntity>> casesByCheck =
        bugCases.findCasesForChecks(ids).stream()
            .collect(
                Collectors.groupingBy(
                    row -> row.getId().getCheckId(), Collectors.toList()));
    return checks.stream()
        .map(
            check ->
                checkMap(check, casesByCheck.getOrDefault(check.getId(), List.of())))
        .toList();
  }

  private static Map<String, Object> checkMap(
      BugCheckEntity check, List<BugCheckCaseEntity> cases) {
    Map<String, Object> row = new LinkedCaseInsensitiveMap<>();
    row.put("ID", check.getId());
    row.put("BUG_ID", check.getBugId());
    row.put("WORKSPACE_ID", check.getWorkspaceId());
    row.put("RUN_ID", check.getRunId());
    row.put("KIND", check.getKind());
    row.put("STATUS", check.getStatus());
    row.put("OUTPUT", check.getOutput());
    row.put("DURATION_MS", check.getDurationMs());
    row.put("CREATED_AT", check.getCreatedAt());
    row.put("WORKSPACE_FINGERPRINT", check.getWorkspaceFingerprint());
    row.put(
        "CASES",
        cases.stream()
            .map(
                test -> {
                  Map<String, Object> value = new LinkedCaseInsensitiveMap<>();
                  value.put("SEQUENCE_NO", test.getId().getSequenceNo());
                  value.put("TEST_ID", test.getTestId());
                  value.put("RESULT", test.getResult());
                  value.put("MESSAGE", test.getMessage());
                  return value;
                })
            .toList());
    return row;
  }

  /** 模拟平台反馈由用户显式提交；关闭前检查当前代码版本的回归和审查。 */
  public Map<String, Object> feedback(
      String bugId, String workspaceId, FeedbackStatus status, String note) {
    if (!BUG_ID.equals(bugId)) throw new IllegalArgumentException("模拟 Bug 不存在。");
    workspaces.get(workspaceId);
    if (note != null && note.length() > 1000)
      throw new IllegalArgumentException("反馈说明不能超过 1000 字。");
    if (status == FeedbackStatus.USER_CONFIRMED_CLOSED) {
      String current = workspaces.verificationFingerprint(workspaceId);
      boolean passed =
          checks(workspaceId).stream()
              .anyMatch(
                  c ->
                      bugId.equals(c.get("BUG_ID"))
                          && "REGRESSION".equals(c.get("KIND"))
                          && "PASSED".equals(c.get("STATUS"))
                          && current.equals(c.get("WORKSPACE_FINGERPRINT")));
      boolean approved =
          approvals.list(workspaceId).stream()
              .anyMatch(
                  a -> "APPROVED".equals(a.get("STATUS")) && current.equals(a.get("FINGERPRINT")));
      if (!passed || !approved) throw new IllegalStateException("需要当前代码版本的通过回归及有效人工审查，才能记录用户确认关闭。");
    }
    String id = RunRecordService.id();
    BugFeedbackEntity saved =
        feedbackRows.saveAndFlush(
            new BugFeedbackEntity(
                id, bugId, workspaceId, status.name(), note, RunRecordService.now()));
    return feedbackMap(saved);
  }

  public List<Map<String, Object>> feedbackHistory(String workspaceId) {
    return feedbackRows.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
        .map(BugService::feedbackMap)
        .toList();
  }

  private static Map<String, Object> feedbackMap(BugFeedbackEntity row) {
    Map<String, Object> value = new LinkedCaseInsensitiveMap<>();
    value.put("ID", row.getId());
    value.put("BUG_ID", row.getBugId());
    value.put("WORKSPACE_ID", row.getWorkspaceId());
    value.put("STATUS", row.getStatus());
    value.put("NOTE", row.getNote());
    value.put("CREATED_AT", row.getCreatedAt());
    return value;
  }

  /** 固定测试入口；无发现的测试不能当作通过。 */
  public Check run(String bugId, String workspaceId, CheckKind kind) {
    return run(bugId, workspaceId, kind, () -> {});
  }

  /** 手动验证不属于某个 Agent 运行，不能推断关联。 */
  public Check run(String bugId, String workspaceId, CheckKind kind, Runnable check) {
    return runInternal(null, bugId, workspaceId, kind, check);
  }

  /** 任务验证必须核对运行与 Bug、工作区的归属。 */
  public Check runForTask(
      String runId, String bugId, String workspaceId, CheckKind kind, Runnable check) {
    if (bugChecks.countRunningRunForTask(runId, RUNNING, bugId, workspaceId) != 1)
      throw new IllegalArgumentException("测试记录与运行、Bug 或工作区不匹配。");
    return runInternal(runId, bugId, workspaceId, kind, check);
  }

  private Check runInternal(
      String runId, String bugId, String workspaceId, CheckKind kind, Runnable check) {
    if (!BUG_ID.equals(bugId)) throw new IllegalArgumentException("模拟 Bug 不存在。");
    // 启动容器前先响应取消与预算限制，避免已停止的任务继续产生新动作。
    check.run();
    WorkspaceService.Workspace workspace = workspaces.get(workspaceId);
    Path root = Path.of(workspace.workspacePath()).toAbsolutePath().normalize();
    Path tests = root.resolve("tests");
    if (!Files.isDirectory(tests) || Files.isSymbolicLink(tests))
      return save(
          runId, bugId, workspaceId, kind, CheckStatus.NEEDS_INFO, "缺少 tests 目录，无法执行验证。", 0);
    String fingerprint;
    try {
      fingerprint = workspaces.verificationFingerprint(workspaceId);
    } catch (RuntimeException e) {
      // 未声明未跟踪文件或文件不可读时不能把结果绑定到代码版本；不得当作“缺少测试”生成补充请求。
      return save(
          runId,
          bugId,
          workspaceId,
          kind,
          CheckStatus.STALE,
          "无法可靠绑定工作区版本；请检查未跟踪文件和 Git 状态。",
          0);
    }
    String source = root.toString();
    if (source.contains(",")) throw new IllegalArgumentException("工作区路径含逗号，不能安全传入容器挂载参数。");
    String name = "agent-check-" + RunRecordService.id().substring(0, 12);
    // 容器内固定执行 unittest：无网络、只读挂载、低权限、资源上限，不接受模型提供的 shell 命令。
    String script =
        "import unittest,sys; s=unittest.defaultTestLoader.discover('tests',pattern='test_*.py');"
            + " n=s.countTestCases(); print('TESTS_DISCOVERED='+str(n),flush=True);"
            + " r=unittest.TextTestRunner(stream=sys.stdout,verbosity=2).run(s); sys.exit(3 if n==0 else (0 if"
            + " r.wasSuccessful() else 1))";
    List<String> command =
        List.of(
            "docker",
            "run",
            "--rm",
            "--name",
            name,
            "--pull=never",
            "--network=none",
            "--memory=256m",
            "--cpus=1",
            "--pids-limit=128",
            "--read-only",
            "--tmpfs",
            "/tmp:rw,noexec,nosuid,size=64m",
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges",
            "--user=65534:65534",
            "--mount",
            "type=bind,source=" + source + ",target=/workspace,readonly",
            "--workdir=/workspace",
            "--env",
            "PYTHONDONTWRITEBYTECODE=1",
            IMAGE,
            "python",
            "-c",
            script);
    long start = System.nanoTime();
    check.run();
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      CompletableFuture<String> output =
          CompletableFuture.supplyAsync(
              () -> {
                try (var input = process.getInputStream();
                    var buffer = new java.io.ByteArrayOutputStream()) {
                  byte[] chunk = new byte[4096];
                  int n, total = 0;
                  while ((n = input.read(chunk)) != -1) {
                    int keep = Math.max(0, Math.min(n, 100_000 - total));
                    if (keep > 0) buffer.write(chunk, 0, keep);
                    total += n;
                  }
                  return buffer.toString(StandardCharsets.UTF_8)
                      + (total > 100_000 ? "\n（输出已截断）" : "");
                } catch (Exception e) {
                  return "无法读取测试输出。";
                }
              });
      while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
        check.run();
        if (elapsed(start) >= 30_000) {
          process.destroyForcibly();
          stopContainer(name);
          return save(
              runId,
              bugId,
              workspaceId,
              kind,
              CheckStatus.TIMEOUT,
              "测试超过 30 秒，已请求停止容器；请核查运行环境。",
              elapsed(start));
        }
      }
      String text = output.get(5, TimeUnit.SECONDS);
      int exit = process.exitValue();
      boolean discovered = text.matches("(?s).*TESTS_DISCOVERED=[1-9][0-9]*.*");
      UnittestResults.Report report = UnittestResults.parse(text);
      CheckStatus status;
      if (!discovered) status = exit == 3 ? CheckStatus.NEEDS_INFO : CheckStatus.ERROR;
      else if (kind == CheckKind.REPRODUCE) status = reproductionStatus(exit, report);
      else status = regressionStatus(exit, report);
      String after;
      try {
        after = workspaces.verificationFingerprint(workspaceId);
      } catch (RuntimeException e) {
        after = null;
      }
      if (!fingerprint.equals(after)) status = CheckStatus.STALE;
      return save(
          runId,
          bugId,
          workspaceId,
          kind,
          status,
          bugContext()
              + "\n固定命令：python unittest discover；容器无网络、工作区只读；退出码："
              + exit
              + "\n"
              + caseSummary(report.cases())
              + "\n"
              + text,
          elapsed(start),
          fingerprint.equals(after) ? fingerprint : null,
          report.cases());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      stopContainer(name);
      return save(
          runId,
          bugId,
          workspaceId,
          kind,
          CheckStatus.INTERRUPTED,
          "验证中断，容器停止结果需核查。",
          elapsed(start));
    } catch (RuntimeException e) {
      stopContainer(name);
      save(
          runId,
          bugId,
          workspaceId,
          kind,
          CheckStatus.INTERRUPTED,
          "验证被运行控制中止，容器停止结果需核查。",
          elapsed(start));
      throw e;
    } catch (Exception e) {
      stopContainer(name);
      return save(
          runId,
          bugId,
          workspaceId,
          kind,
          CheckStatus.ERROR,
          "无法运行容器测试，请检查 Docker 和本地测试镜像。",
          elapsed(start));
    }
  }

  private void stopContainer(String name) {
    try {
      Process p = new ProcessBuilder("docker", "rm", "-f", name).redirectErrorStream(true).start();
      p.waitFor(5, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      /* 容器可能已自行退出；调用方保留结果未知的提示。 */
    }
  }

  private long elapsed(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  private Check save(
      String runId,
      String bugId,
      String workspaceId,
      CheckKind kind,
      CheckStatus status,
      String output,
      long duration) {
    return save(runId, bugId, workspaceId, kind, status, output, duration, null, List.of());
  }

  private Check save(
      String runId,
      String bugId,
      String workspaceId,
      CheckKind kind,
      CheckStatus status,
      String output,
      long duration,
      String fingerprint) {
    return save(runId, bugId, workspaceId, kind, status, output, duration, fingerprint, List.of());
  }

  private Check save(
      String runId,
      String bugId,
      String workspaceId,
      CheckKind kind,
      CheckStatus status,
      String output,
      long duration,
      String fingerprint,
      List<UnittestResults.Case> cases) {
    String id = RunRecordService.id(), time = RunRecordService.now();
    BugCheckEntity checkRow =
        new BugCheckEntity(
            id,
            bugId,
            workspaceId,
            runId,
            kind.name(),
            status.name(),
            output,
            duration,
            time,
            fingerprint);
    List<BugCheckCaseEntity> caseRows = new ArrayList<>(cases.size());
    for (int i = 0; i < cases.size(); i++) {
      UnittestResults.Case test = cases.get(i);
      caseRows.add(
          new BugCheckCaseEntity(id, i, test.testId(), test.outcome().name(), test.message()));
    }
    // 检测与逐用例证据一起提交；任一写入失败不留下不完整结果。
    tx.executeWithoutResult(
        ignored -> {
          bugChecks.saveAndFlush(checkRow);
          if (!caseRows.isEmpty()) bugCases.saveAllAndFlush(caseRows);
        });
    return new Check(
        id, bugId, workspaceId, kind, status, output, duration, time, fingerprint, runId, cases);
  }

  public enum CheckKind {
    REPRODUCE,
    REGRESSION
  }

  public enum FeedbackStatus {
    NEEDS_INFO,
    WAITING_REVIEW,
    REJECTED,
    USER_CONFIRMED_CLOSED
  }

  public enum CheckStatus {
    NEEDS_INFO,
    FAILURES_OBSERVED,
    TARGET_MISMATCH,
    TARGET_NOT_EXECUTED,
    TARGET_SKIPPED,
    NOT_REPRODUCED,
    PASSED,
    FAILED,
    ERROR,
    TIMEOUT,
    INTERRUPTED,
    STALE
  }

  public record Bug(
      String id,
      String title,
      String description,
      String expectedTests,
      String source,
      int version,
      String expectedSymptom,
      String expectedFailingTest,
      String expectedTestId,
      String expectedFailureText) {}

  public record Check(
      String id,
      String bugId,
      String workspaceId,
      CheckKind kind,
      CheckStatus status,
      String output,
      long durationMs,
      String createdAt,
      String workspaceFingerprint,
      String runId,
      List<UnittestResults.Case> cases) {
    public Check(
        String id,
        String bugId,
        String workspaceId,
        CheckKind kind,
        CheckStatus status,
        String output,
        long durationMs,
        String createdAt,
        String workspaceFingerprint,
        String runId) {
      this(
          id,
          bugId,
          workspaceId,
          kind,
          status,
          output,
          durationMs,
          createdAt,
          workspaceFingerprint,
          runId,
          List.of());
    }
  }
}
