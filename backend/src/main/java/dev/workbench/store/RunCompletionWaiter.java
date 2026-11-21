package dev.workbench.store;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * 运行终态完成通知。
 *
 * <p>等待方注册一个 Future 后按有界超时等待；运行进入终态时由 {@link RunRecordService} 通知。
 * 超时后等待方应重新读取数据库状态，避免丢失通知与数据库提交之间的竞态。
 */
@Component
public class RunCompletionWaiter {
  private final ConcurrentHashMap<String, CompletableFuture<Void>> waiters =
      new ConcurrentHashMap<>();

  public CompletableFuture<Void> register(String runId) {
    return waiters.computeIfAbsent(runId, ignored -> new CompletableFuture<>());
  }

  public void remove(String runId, CompletableFuture<Void> future) {
    waiters.remove(runId, future);
  }

  /** @return true 表示收到终态通知；false 表示超时。 */
  public boolean await(CompletableFuture<Void> future, long millis) throws InterruptedException {
    try {
      future.get(Math.max(1, millis), TimeUnit.MILLISECONDS);
      return true;
    } catch (TimeoutException e) {
      return false;
    } catch (ExecutionException e) {
      throw new IllegalStateException("等待运行终态失败。", e);
    }
  }

  public void signal(String runId) {
    CompletableFuture<Void> future = waiters.remove(runId);
    if (future != null) future.complete(null);
  }
}
