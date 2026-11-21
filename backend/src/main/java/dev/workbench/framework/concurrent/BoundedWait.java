package dev.workbench.framework.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 有界、可中断的等待；不使用 Thread.sleep，也不阻塞公共 ForkJoinPool。 */
public final class BoundedWait {
  /** 等待到超时或线程被中断；中断会立即传播为 IllegalStateException。 */
  public static void pause(long millis) {
    try {
      new CountDownLatch(1).await(Math.max(1, millis), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待被中断。", e);
    }
  }

  private BoundedWait() {}
}
