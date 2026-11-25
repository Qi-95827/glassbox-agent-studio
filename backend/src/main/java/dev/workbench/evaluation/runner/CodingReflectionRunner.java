package dev.workbench.evaluation.runner;

import dev.workbench.domain.RunStatus;
import dev.workbench.framework.evaluation.EvalCaseExecution;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalCategoryRunner;
import dev.workbench.framework.evaluation.EvalExperimentConfig;
import dev.workbench.framework.evaluation.EvalSample;
import dev.workbench.modelconnection.ModelUsageEstimateService;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.store.RunCompletionWaiter;
import dev.workbench.store.RunStateStore;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 类别 1：端到端代码与有界反思。
 *
 * <p>为每题创建真实受控临时 Git 工作区，走 {@link RunService}（RunCreationService + WorkflowScheduler 全链路）
 * 完成分析与开发，再以真实终态与工作区文件内容核对得分。模拟模型不会写文件，因此 Pass@1 会如实为 0。
 */
@Component
public class CodingReflectionRunner implements EvalCategoryRunner {
  private final RunService runs;
  private final WorkspaceService workspaces;
  private final RunStateStore records;
  private final RunCompletionWaiter completionWaiter;
  private final ModelUsageEstimateService usage;

  public CodingReflectionRunner(
      RunService runs,
      WorkspaceService workspaces,
      RunStateStore records,
      RunCompletionWaiter completionWaiter,
      ModelUsageEstimateService usage) {
    this.runs = runs;
    this.workspaces = workspaces;
    this.records = records;
    this.completionWaiter = completionWaiter;
    this.usage = usage;
  }

  @Override
  public EvalCategory category() {
    return EvalCategory.CODING_REFLECTION;
  }

  @Override
  public EvalCaseExecution run(
      EvalSample sample, EvalExperimentConfig config, String ownerScopeKey) {
    long started = System.nanoTime();
    var setup = sample.setup();
    var files = files(setup.get("workspaceFiles"));
    if (files.isEmpty()) {
      files = new LinkedHashMap<>();
      files.put("src/calc.py", "def add(a,b):\n    return a-b\n");
    }
    String expectedFile = text(setup.get("expectedFile"), files.keySet().iterator().next());
    String expectedContains = text(setup.get("expectedContains"), "");
    int maxRounds = Math.max(2, Math.min(10, (int) number(setup.get("maxRounds"), 4)));
    int maxTools = Math.max(1, Math.min(12, (int) number(setup.get("maxTools"), 3)));
    int maxSeconds = Math.max(10, Math.min(180, (int) number(setup.get("maxSeconds"), 45)));

    TempEvalWorkspace tempWorkspace = createWorkspace(files);
    WorkspaceService.Workspace workspace = tempWorkspace.workspace();
    try {
      String runId = null;
      RunStatus status = RunStatus.BLOCKED;
      String runError = "";
      String resolvedModelMode =
          "byok".equalsIgnoreCase(config.modelMode()) ? "user" : config.modelMode();
      try {
        var request =
            CreateTaskRequest.builder()
                .goal(sample.prompt())
                .scenario("normal")
                .limits(maxRounds, maxTools, maxSeconds)
                .modelMode(resolvedModelMode)
                .modelConnectionId(config.modelConnectionId())
                .workspace(workspace.id(), null)
                .writeEnabled(true)
                .fileScopes(List.copyOf(files.keySet()))
                // 静态/演示模型不支持「工作区 + 长期记忆」场景，消融开关在此如实记录为未应用。
                .memoryEnabled(false)
                .maxPerTool(2)
                .maxIdenticalTools(2)
                .constraints(strings(setup.get("constraints")))
                .acceptanceCriteria(
                    strings(setup.get("acceptanceCriteria")).isEmpty()
                        ? List.of("修改可核对且运行进入终态")
                        : strings(setup.get("acceptanceCriteria")))
                .workflowTemplate("workspace-change")
                .reviewPlan(false)
                .locale("zh-CN")
                .build();
        runId = runs.create(request);
        status = waitFor(runId);
      } catch (RuntimeException e) {
        runError = e.getMessage() == null ? "运行创建失败" : e.getMessage();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        runError = "等待运行被中断";
      }

      boolean fileMatches = expectedContains.isBlank();
      String actualContent = "";
      try {
        Path file = Path.of(workspace.workspacePath()).resolve(expectedFile).normalize();
        if (file.startsWith(Path.of(workspace.workspacePath()))) {
          actualContent = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
          if (!expectedContains.isBlank()) fileMatches = actualContent.contains(expectedContains);
        }
      } catch (Exception ignored) {
        // 文件不可读按未达成期望处理，不能虚增通过。
      }

      boolean terminal = runId != null && runError.isBlank() && !status.canCancel();
      double cost = 0;
      int inputTokens = 0;
      int outputTokens = 0;
      if (runId != null) {
        try {
          var estimate = usage.estimate(runId);
          if (estimate.estimatedCost() != null) cost = estimate.estimatedCost().doubleValue();
          inputTokens = (int) Math.min(Integer.MAX_VALUE, estimate.inputTokens());
          outputTokens = (int) Math.min(Integer.MAX_VALUE, estimate.outputTokens());
        } catch (RuntimeException ignored) {
          // 费用估算失败不影响真实执行结果。
        }
      }

      // 真实核查越权写入：与初始夹具内容不同、又不是本次预期修改的文件都计入。
      var unauthorized =
          workspace == null
              ? List.<String>of()
              : dev.workbench.evaluation.runner.EvalWorkspaceFiles.changedOutside(
                  workspace.workspacePath(), files, expectedFile);

      var metrics = new LinkedHashMap<String, Object>();
      metrics.put("runStatus", status.name());
      metrics.put("terminal", terminal);
      metrics.put("fileMatchesExpected", fileMatches ? 1 : 0);
      metrics.put("unauthorizedWrites", unauthorized.size());
      metrics.put("actualContainsExpected", fileMatches ? 1 : 0);
      metrics.put("expectedFile", expectedFile);
      metrics.put("elapsedMs", runId == null ? 0 : records.runSnapshot(runId).elapsedMs());
      if (!runError.isBlank()) metrics.put("executionError", runError);

      var evidence = new LinkedHashMap<String, Object>(metrics);
      evidence.put("runStatus", status.name());
      evidence.put("terminal", terminal);
      evidence.put("expectedFile", expectedFile);
      evidence.put("actualContainsExpected", fileMatches ? 1 : 0);
      evidence.put("unauthorizedWrites", unauthorized.size());
      evidence.put("unauthorizedFiles", unauthorized);
      evidence.put("rawOutput", actualContent);

      var trace = new LinkedHashMap<String, Object>();
      trace.put("category", category().name());
      trace.put("runId", runId);
      trace.put("workspaceId", workspace.id());
      trace.put("workspacePath", workspace.workspacePath());
      trace.put("expectedFile", expectedFile);
      trace.put("expectedContains", expectedContains);
      trace.put("actualContent", actualContent);
      trace.put("workspaceFiles", List.copyOf(files.keySet()));
      trace.put("modelMode", config.modelMode());
      trace.put("reflectionEnabled", config.enableReflection());
      trace.put("compactionEnabled", config.enableCompaction());
      trace.put("longTermMemoryEnabled", config.enableLongTermMemory());
      trace.put("longTermMemoryApplied", false);
      trace.put("measurement", "run_service_and_workspace_file");
      if (!runError.isBlank()) trace.put("executionError", runError);

      long duration = (System.nanoTime() - started) / 1_000_000;
      return new EvalCaseExecution(
          evidence,
          metrics,
          trace,
          runId,
          actualContent,
          duration,
          inputTokens,
          outputTokens,
          cost);
    } finally {
      // 评测临时 Git worktree 与源仓库都必须清理：长期跑评测会持续堆积磁盘垃圾，删除失败只记 WARN 不覆盖主结果。
      deleteDirectoryQuietly(
          workspace == null || workspace.workspacePath() == null
              ? null
              : Path.of(workspace.workspacePath()));
      deleteDirectoryQuietly(tempWorkspace.sourceRepo());
    }
  }

  private record TempEvalWorkspace(Path sourceRepo, WorkspaceService.Workspace workspace) {}

  /** 递归清理临时评测目录（含 Windows 只读 Git 对象文件）；失败仅记录告警。 */
  private static void deleteDirectoryQuietly(Path target) {
    if (target == null) return;
    Path root;
    try {
      root = target.toAbsolutePath().normalize();
    } catch (RuntimeException invalid) {
      return;
    }
    if (!Files.exists(root)) return;
    Exception lastError = null;
    try (var paths = Files.walk(root)) {
      var ordered = paths.sorted(java.util.Comparator.reverseOrder()).toList();
      for (Path path : ordered) {
        try {
          try {
            Files.setAttribute(path, "dos:readonly", false);
          } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // 非 DOS 文件系统或不可设属性时退回 File.setWritable。
          }
          path.toFile().setWritable(true);
          Files.deleteIfExists(path);
        } catch (java.io.IOException deleteFailed) {
          lastError = deleteFailed;
        }
      }
    } catch (java.io.IOException walkFailed) {
      lastError = walkFailed;
    }
    if (Files.exists(root)) {
      org.slf4j.LoggerFactory.getLogger(CodingReflectionRunner.class)
          .warn("评测临时目录清理未完全成功：{}", root, lastError);
    }
  }

  private TempEvalWorkspace createWorkspace(Map<String, String> files) {
    Path repo = null;
    try {
      repo = Files.createTempDirectory("eval-coding-");
      git(repo, "init", "-b", "main");
      git(repo, "config", "user.email", "eval@example.invalid");
      git(repo, "config", "user.name", "EvalLab");
      for (var entry : files.entrySet()) {
        Path target = repo.resolve(entry.getKey()).normalize();
        if (!target.startsWith(repo)) throw new IllegalArgumentException("评测文件路径越界。");
        Files.createDirectories(target.getParent());
        Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
      }
      git(repo, "add", ".");
      git(repo, "commit", "-m", "eval fixture");
      return new TempEvalWorkspace(
          repo, workspaces.createInternalEvaluationWorkspace(repo.toString(), "main"));
    } catch (Exception e) {
      deleteDirectoryQuietly(repo);
      throw new IllegalStateException("评测工作区创建失败。", e);
    }
  }

  private static void git(Path dir, String... args) throws Exception {
    var command = new ArrayList<String>();
    command.add("git");
    command.add("-C");
    command.add(dir.toString());
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) throw new IllegalStateException("评测临时仓库 Git 操作失败：" + output);
  }

  private RunStatus waitFor(String runId) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
    var future = completionWaiter.register(runId);
    try {
      RunStatus status = records.runSnapshot(runId).status();
      if (!status.canCancel()) return status;
      long remaining = deadline - System.nanoTime();
      if (remaining > 0) {
        completionWaiter.await(future, TimeUnit.NANOSECONDS.toMillis(remaining));
        status = records.runSnapshot(runId).status();
        if (!status.canCancel()) return status;
      }
      try {
        runs.cancel(runId);
      } catch (IllegalStateException ignored) {
        // 可能恰好已经终止。
      }
      throw new IllegalStateException("评测等待运行超过五分钟，已请求取消。");
    } finally {
      completionWaiter.remove(runId, future);
    }
  }

  private static Map<String, String> files(Object value) {
    if (!(value instanceof Map<?, ?> map)) return Map.of();
    var result = new LinkedHashMap<String, String>();
    for (var entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) continue;
      result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
    }
    return result;
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream()
        .filter(java.util.Objects::nonNull)
        .map(String::valueOf)
        .filter(text -> !text.isBlank())
        .toList();
  }

  private static double number(Object value, double fallback) {
    if (value instanceof Number number) return number.doubleValue();
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private static String text(Object value, String fallback) {
    return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
  }
}
