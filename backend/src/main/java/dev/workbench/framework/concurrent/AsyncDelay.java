package dev.workbench.framework.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 有界退避等待；避免业务线程用 Thread.sleep 忙等。 */
public final class AsyncDelay {
  public static void await(long millis) {
    CompletableFuture.runAsync(
            () -> {},
            CompletableFuture.delayedExecutor(Math.max(1, millis), TimeUnit.MILLISECONDS))
        .join();
  }

  private AsyncDelay() {}
}
