package dev.workbench.evaluation;

import dev.workbench.audit.AuditService;
import dev.workbench.domain.RunStatus;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.store.RunRecordService;
import dev.workbench.workspace.WorkspaceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import dev.workbench.domain.CaseStatus;

/** 运行固定种子集；实际模型和工具仍由现有 AgentScope 链路调用。 */
@Service
public class EvaluationService {
  private final EvaluationRepository evaluations;
  private final RunService runs;
  private final RunRecordService records;
  private final SeedCaseEvaluator evaluator;
  private final WorkspaceService workspaces;
  private final EvaluationSuiteService suites;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final AuditService audit;
  private final ExecutorService executor;
  private final AtomicBoolean active = new AtomicBoolean();
  private String activeBatch;
  private String currentRun;
  private boolean cancellationRequested;

  public EvaluationService(
      EvaluationRepository evaluations,
      RunService runs,
      RunRecordService records,
      SeedCaseEvaluator evaluator,
      WorkspaceService workspaces,
      EvaluationSuiteService suites,
      com.fasterxml.jackson.databind.ObjectMapper json,
      AuditService audit,
      @org.springframework.beans.factory.annotation.Qualifier("evaluationExecutor")
          ExecutorService executor) {
    this.evaluations = evaluations;
    this.runs = runs;
    this.records = records;
    this.evaluator = evaluator;
    this.workspaces = workspaces;
    this.suites = suites;
    this.json = json;
    this.audit = audit;
    this.executor = executor;
  }

  @PostConstruct
  void initialize() {
    evaluations.interruptActive();
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  /** 只接受固定样本，避免通过评测接口触发真实模型和代码写入。 */
  public String start() {
    return start(SeedSuite.Variant.LOCAL_AGENT_V2);
  }

  public String start(SeedSuite.Variant suite) {
    return start(
        new EvaluationConfig(suite.name(), null, null, null, null, AuditService.LOCAL_ACTOR));
  }

  /** 指定套件版本与预算上限；预算只能收紧样本默认值。 */
  public synchronized String start(EvaluationConfig config) {
    java.util.Objects.requireNonNull(config);
    SeedSuite.Variant suite;
    try {
      suite = SeedSuite.Variant.valueOf(config.suite());
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("未知评测套件。");
    }
    var version = suites.resolve(suite, config.version());
    if (!active.compareAndSet(false, true)) throw new IllegalStateException("已有评测正在运行。");
    String id = dev.workbench.domain.WorkbenchIds.newId();
    boolean created = false;
    try {
      String configJson;
      try {
        configJson = json.writeValueAsString(config);
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new IllegalStateException("评测配置无法保存。", e);
      }
      evaluations.create(
          id,
          suite,
          version.version(),
          version.definitionJson(),
          version.definitionSha256(),
          configJson,
          config.requestedBy() == null ? AuditService.LOCAL_ACTOR : config.requestedBy());
      created = true;
      activeBatch = id;
      cancellationRequested = false;
      executor.submit(() -> execute(id, suite, config));
      audit.record(
          "EVALUATION_START",
          "EVALUATION_BATCH",
          id,
          "RUNNING",
          suite.name() + " v" + version.version());
      return id;
    } catch (RuntimeException e) {
      if (created) evaluations.finishBatch(id, EvaluationStatus.FAILED);
      activeBatch = null;
      active.set(false);
      throw e;
    }
  }

  /** 停止调度后续样本，并向当前运行传播协作式取消。 */
  public synchronized void cancel(String id) {
    if (!id.equals(activeBatch) || cancellationRequested)
      throw new IllegalStateException("评测不存在、已经结束或正在取消。");
    evaluations.requestCancel(id);
    cancellationRequested = true;
    if (currentRun != null) {
      try {
        runs.cancel(currentRun);
      } catch (IllegalStateException ignored) {
        // 运行可能在发送取消的瞬间已结束，后续按实际终态判断。
      }
    }
    audit.record("EVALUATION_CANCEL", "EVALUATION_BATCH", id, "CANCELLING", "用户请求取消评测");
  }

  private void execute(String batchId, SeedSuite.Variant suite, EvaluationConfig config) {
    EvaluationStatus finalStatus = EvaluationStatus.COMPLETED;
    try {
      for (SeedCase sample : suite.cases()) {
        String runId;
        synchronized (this) {
          if (cancellationRequested) break;
          evaluations.startCase(batchId, sample);
          runId = runs.create(request(sample, batchId, config));
          evaluations.attachRun(batchId, sample, runId);
          currentRun = runId;
        }
        RunStatus status = waitForRun(runId);
        synchronized (this) {
          currentRun = null;
        }
        if (status == RunStatus.CANCELLED) {
          evaluations.finishCase(batchId, sample, CaseStatus.CANCELLED, "关联运行已响应取消。");
          continue;
        }
        var checksForCase = evaluator.evaluate(sample, runId, status);
        evaluations.finishCaseWithChecks(batchId, sample, checksForCase);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      finalStatus = EvaluationStatus.INTERRUPTED;
    } catch (Exception e) {
      finalStatus = EvaluationStatus.FAILED;
    } finally {
      synchronized (this) {
        if (finalStatus == EvaluationStatus.COMPLETED && cancellationRequested)
          finalStatus = EvaluationStatus.CANCELLED;
        try {
          evaluations.finishBatch(batchId, finalStatus);
        } finally {
          activeBatch = null;
          currentRun = null;
          active.set(false);
        }
      }
    }
  }

  /**
   * 固定样本请求；工作区样本使用独立临时仓库，不触碰用户工作区或默认数据目录。
   *
   * <p>临时仓库保留在系统临时目录，便于复核；批量评测不自动清理。
   */
  private CreateTaskRequest request(SeedCase sample, String batchId, EvaluationConfig config) {
    var fixture = createFixture(sample, batchId);
    String workspaceId = fixture == null ? null : fixture.id();
    List<String> files = fixture == null ? null : List.of(sample.workspaceFixtureFile());
    int rounds =
        config.maxRounds() == null ? sample.maxRounds() : Math.min(sample.maxRounds(), config.maxRounds());
    int tools =
        config.maxTools() == null ? sample.maxTools() : Math.min(sample.maxTools(), config.maxTools());
    int seconds =
        config.maxSeconds() == null ? SeedSuite.MAX_SECONDS : config.maxSeconds();
    return new CreateTaskRequest(
        "评测：" + sample.title(),
        sample.scenario(),
        rounds,
        tools,
        seconds,
        "mock",
        workspaceId,
        null,
        sample.writeCase(),
        sample.bugId(),
        false,
        null,
        List.of(),
        List.of(),
        null,
        sample.maxIdenticalTools(),
        sample.entryKind(),
        null,
        null,
        files,
        null,
        null,
        sample.workflowTemplate(),
        false,
        null);
  }

  private WorkspaceService.Workspace createFixture(SeedCase sample, String batchId) {
    String file = sample.workspaceFixtureFile();
    if (file == null) return null;
    try {
      Path repo = Files.createTempDirectory("agent-eval-" + batchId.substring(0, 8) + "-");
      git(repo, "init", "-b", "main");
      git(repo, "config", "user.email", "eval@example.invalid");
      git(repo, "config", "user.name", "Evaluation");
      Files.writeString(
          repo.resolve(file),
          sample.bugCase() ? "def add(a,b):\n    return a-b\n" : "评测授权文件内容",
          StandardCharsets.UTF_8);
      git(repo, "add", ".");
      git(repo, "commit", "-m", "fixture");
      return workspaces.create(repo.toString(), "main");
    } catch (Exception e) {
      throw new IllegalStateException("评测工作区创建失败。", e);
    }
  }

  private static void git(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.add("-C");
    command.add(dir.toString());
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) throw new IllegalStateException("评测临时仓库 Git 操作失败：" + output);
  }

  private RunStatus waitForRun(String runId) throws InterruptedException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
    while (true) {
      RunStatus status = records.runSnapshot(runId).status();
      if (!status.canCancel()) return status;
      if (System.nanoTime() >= deadline) {
        try {
          runs.cancel(runId);
        } catch (IllegalStateException ignored) {
          /* 可能恰好已经终止。 */
        }
        throw new IllegalStateException("评测等待运行超过五分钟，已请求取消。");
      }
      dev.workbench.framework.concurrent.AsyncDelay.await(100);
    }
  }
}
