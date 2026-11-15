package dev.workbench.evaluation;

import dev.workbench.domain.RunStatus;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.task.RunRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

/** 运行固定种子集；实际模型和工具仍由现有 AgentScope 链路调用。 */
@Service
public class EvaluationService {
  private final EvaluationRepository evaluations;
  private final RunService runs;
  private final RunRepository records;
  private final SeedCaseEvaluator evaluator;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final AtomicBoolean active = new AtomicBoolean();
  private String activeBatch;
  private String currentRun;
  private boolean cancellationRequested;

  public EvaluationService(
      EvaluationRepository evaluations,
      RunService runs,
      RunRepository records,
      SeedCaseEvaluator evaluator) {
    this.evaluations = evaluations;
    this.runs = runs;
    this.records = records;
    this.evaluator = evaluator;
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
  public synchronized String start() {
    if (!active.compareAndSet(false, true)) throw new IllegalStateException("已有评测正在运行。");
    String id = RunRepository.id();
    boolean created = false;
    try {
      evaluations.create(id);
      created = true;
      activeBatch = id;
      cancellationRequested = false;
      executor.submit(() -> execute(id));
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
  }

  private void execute(String batchId) {
    EvaluationStatus finalStatus = EvaluationStatus.COMPLETED;
    try {
      for (SeedCase sample : SeedCase.values()) {
        String runId;
        synchronized (this) {
          if (cancellationRequested) break;
          evaluations.startCase(batchId, sample);
          runId =
              runs.create(
                  new CreateTaskRequest(
                      "评测：" + sample.title(),
                      sample.scenario(),
                      sample.maxRounds(),
                      sample.maxTools(),
                      SeedSuite.MAX_SECONDS,
                      "mock",
                      null,
                      null,
                      false,
                      null,
                      false,
                      sample.maxTools(),
                      java.util.List.of(),
                      java.util.List.of(),
                      null,
                      sample.maxIdenticalTools()));
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
        evaluations.finishCaseWithChecks(
            batchId, sample, evaluator.evaluate(sample, runId, status));
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
      Thread.sleep(100);
    }
  }
}
