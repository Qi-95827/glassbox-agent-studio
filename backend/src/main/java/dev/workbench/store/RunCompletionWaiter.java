package dev.workbench.store;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * 运行终态完成通知。
 *
 * <p>等待方注册后先读取持久化状态，再有界等待通知；通知在事务提交后发出。多个消费者各自持有 Future，
 * 任一消费者退出不会移除其他消费者的等待。
 */
@Component
public class RunCompletionWaiter {
  private final ConcurrentHashMap<String, CopyOnWriteArrayList<CompletableFuture<Void>>> waiters =
      new ConcurrentHashMap<>();

  public CompletableFuture<Void> register(String runId) {
    var future = new CompletableFuture<Void>();
    waiters.compute(
        runId,
        (ignored, list) -> {
          var current = list == null ? new CopyOnWriteArrayList<CompletableFuture<Void>>() : list;
          current.add(future);
          return current;
        });
    return future;
  }

  public void remove(String runId, CompletableFuture<Void> future) {
    waiters.computeIfPresent(
        runId,
        (ignored, list) -> {
          list.remove(future);
          return list.isEmpty() ? null : list;
        });
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
    var futures = waiters.remove(runId);
    if (futures == null) return;
    for (var future : futures) future.complete(null);
  }
}
