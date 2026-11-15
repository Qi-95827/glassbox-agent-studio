package dev.workbench.bug;

import dev.workbench.task.RunRepository;
import dev.workbench.workspace.ApprovalService;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 模拟 Bug 和受限验证；测试结果不能代替用户对真实代码的审查。 */
@Service
public class BugService {
  private static final String BUG_ID = "MOCK-PY-001";
  private static final String IMAGE = "python:3.12-alpine";
  private final WorkspaceService workspaces;
  private final JdbcTemplate db;
  private final ApprovalService approvals;

  public BugService(WorkspaceService workspaces, JdbcTemplate db, ApprovalService approvals) {
    this.workspaces = workspaces;
    this.db = db;
    this.approvals = approvals;
  }

  public List<Bug> list() {
    return List.of(
        new Bug(
            BUG_ID,
            "Python 单元测试失败",
            "模拟一个 Python 项目的测试失败问题；需要在工作区存在 tests 目录及 unittest 测试。",
            "tests/test_*.py"));
  }

  public List<Map<String, Object>> checks(String workspaceId) {
    return db.queryForList(
        "SELECT id,bug_id,workspace_id,run_id,kind,status,CAST(output AS VARCHAR) AS"
            + " output,duration_ms,created_at,workspace_fingerprint FROM bug_checks WHERE"
            + " workspace_id=? ORDER BY created_at DESC",
        workspaceId);
  }

  /** 只返回明确记录属于该运行的检测，历史和手动检测不猜测归属。 */
  public List<Map<String, Object>> checksForRun(String runId) {
    Integer exists =
        db.queryForObject("SELECT COUNT(*) FROM runs WHERE id=?", Integer.class, runId);
    if (exists == null || exists != 1)
      throw new org.springframework.dao.EmptyResultDataAccessException(1);
    return db.queryForList(
        "SELECT id,bug_id,workspace_id,run_id,kind,status,CAST(output AS VARCHAR) AS"
            + " output,duration_ms,created_at,workspace_fingerprint FROM bug_checks WHERE run_id=?"
            + " ORDER BY created_at,id",
        runId);
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
    String id = RunRepository.id();
    db.update(
        "INSERT INTO bug_feedback VALUES(?,?,?,?,?,?)",
        id,
        bugId,
        workspaceId,
        status.name(),
        note,
        RunRepository.now());
    return db.queryForMap("SELECT * FROM bug_feedback WHERE id=?", id);
  }

  public List<Map<String, Object>> feedbackHistory(String workspaceId) {
    return db.queryForList(
        "SELECT * FROM bug_feedback WHERE workspace_id=? ORDER BY created_at DESC", workspaceId);
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
    Integer matches =
        db.queryForObject(
            "SELECT COUNT(*) FROM runs r JOIN tasks t ON r.task_id=t.id WHERE r.id=? AND"
                + " r.status='RUNNING' AND t.bug_id=? AND t.workspace_id=?",
            Integer.class,
            runId,
            bugId,
            workspaceId);
    if (matches == null || matches != 1) throw new IllegalArgumentException("测试记录与运行、Bug 或工作区不匹配。");
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
      return save(
          runId,
          bugId,
          workspaceId,
          kind,
          CheckStatus.NEEDS_INFO,
          "无法可靠绑定工作区版本；请检查未跟踪文件和 Git 状态。",
          0);
    }
    String source = root.toString();
    if (source.contains(",")) throw new IllegalArgumentException("工作区路径含逗号，不能安全传入容器挂载参数。");
    String name = "agent-check-" + RunRepository.id().substring(0, 12);
    // 容器内固定执行 unittest：无网络、只读挂载、低权限、资源上限，不接受模型提供的 shell 命令。
    String script =
        "import unittest,sys; s=unittest.defaultTestLoader.discover('tests',pattern='test_*.py');"
            + " n=s.countTestCases(); print('TESTS_DISCOVERED='+str(n),flush=True);"
            + " r=unittest.TextTestRunner(verbosity=2).run(s); sys.exit(3 if n==0 else (0 if"
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
      CheckStatus status;
      if (!discovered) status = exit == 3 ? CheckStatus.NEEDS_INFO : CheckStatus.ERROR;
      else if (kind == CheckKind.REPRODUCE)
        status =
            exit == 0
                ? CheckStatus.NOT_REPRODUCED
                : exit == 1 ? CheckStatus.FAILURES_OBSERVED : CheckStatus.ERROR;
      else
        status =
            exit == 0 ? CheckStatus.PASSED : exit == 1 ? CheckStatus.FAILED : CheckStatus.ERROR;
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
          "固定命令：python unittest discover；容器无网络、工作区只读；退出码：" + exit + "\n" + text,
          elapsed(start),
          fingerprint.equals(after) ? fingerprint : null);
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
    return save(runId, bugId, workspaceId, kind, status, output, duration, null);
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
    String id = RunRepository.id(), time = RunRepository.now();
    db.update(
        "INSERT INTO"
            + " bug_checks(id,bug_id,workspace_id,kind,status,output,duration_ms,created_at,workspace_fingerprint,run_id)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        id,
        bugId,
        workspaceId,
        kind.name(),
        status.name(),
        output,
        duration,
        time,
        fingerprint,
        runId);
    return new Check(
        id, bugId, workspaceId, kind, status, output, duration, time, fingerprint, runId);
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
    NOT_REPRODUCED,
    PASSED,
    FAILED,
    ERROR,
    TIMEOUT,
    INTERRUPTED,
    STALE
  }

  public record Bug(String id, String title, String description, String expectedTests) {}

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
      String runId) {}
}
