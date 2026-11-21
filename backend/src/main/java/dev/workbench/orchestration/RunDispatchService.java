package dev.workbench.orchestration;

import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.StopCause;
import dev.workbench.store.RunCreationStore;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.StopCompletion;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 提交后派发、运行内顺序提交与队列拒绝收口。 */
final class RunDispatchService {
  private final RunStateStore runs;
  private final RunCreationStore creation;
  private final ExecutorService executor;
  private final RunService owner;
  private volatile RunExecutionEngine engine;

  RunDispatchService(
      RunStateStore runs,
      RunCreationStore creation,
      ExecutorService executor,
      RunService owner) {
    this.runs = runs;
    this.creation = creation;
    this.executor = executor;
    this.owner = owner;
  }

  void setEngine(RunExecutionEngine engine) {
    this.engine = engine;
  }

  /** 有外层事务时只在提交成功后派发；无活动事务时调用方已完成提交。 */
  void dispatchAfterCommit(String runId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatchInitial(runId);
            }
          });
      return;
    }
    dispatchInitial(runId);
  }

  void dispatchInitial(String runId) {
    owner.beforeDispatch.run();
    owner.dispatchInvocationCount++;
    try {
      executor.submit(() -> engine.execute(runId, CheckpointStage.BEFORE_TOOL, null, false));
    } catch (RejectedExecutionException e) {
      String reason = "运行未能加入执行队列，尚未启动 Agent。";
      runs.finishStoppedRun(
          new StopCompletion(
              java.util.UUID.fromString(runId),
              null,
              StopCause.UNCLASSIFIED_FAILURE,
              reason,
              0,
              0,
              0,
              null));
    }
  }

  /** 补充接受后从安全边界重新排队；沿用与首次派发相同的单线程队列与拒绝语义。 */
  void dispatchContinuation(String runId) {
    dispatchAfterCommit(runId);
  }

  /** 候选确认事务提交后派发；调用方不得在未提交事务中调用。 */
  void dispatchConfirmed(String runId) {
    dispatchInitial(runId);
  }

  void dispatchRecovery(String nextId, CheckpointStage stage, String previousResult) {
    try {
      executor.submit(() -> engine.execute(nextId, stage, previousResult, true));
    } catch (RejectedExecutionException e) {
      creation.rejectRecoveryBeforeDispatch(nextId);
    }
  }
}
