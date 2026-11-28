package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 按运行标识分段锁的行为契约。
 *
 * <p>两个必须同时成立的性质：同一 {@code runId} 严格互斥（状态迁移不会互相穿插），
 * 落在不同槽位的两个 {@code runId} 真正并发（一个慢运行不会阻塞其他运行）。
 */
class RunLockRegistryTest {
  private final RunLockRegistry locks = new RunLockRegistry();

  @Test
  void sameRunIdAlwaysMapsToTheSameMonitor() {
    assertSame(locks.lockFor("run-1"), locks.lockFor("run-1"));
    assertSame(locks.lockFor("run-1"), locks.lockFor(new String("run-1")), "哈希相等必须命中同一把锁");
    assertSame(locks.lockFor(null), locks.lockFor(null));
    assertSame(locks.lockFor(null), locks.lockFor(null), "null 必须稳定回退到 0 号槽位");
    assertEquals(0, locks.stripeOf(null));
    assertSame(locks.lockFor(null), lockAt(0));
  }

  @Test
  void differentStripesUseDifferentMonitors() {
    String first = runIdInStripe(0);
    String second = runIdInStripe(1);
    assertNotEquals(locks.stripeOf(first), locks.stripeOf(second));
    assertNotEquals(locks.lockFor(first), locks.lockFor(second));
  }

  /** 同一 runId 的临界区严格互斥：并发计数峰值必须恒为 1。 */
  @Test
  void sameRunIdEntersCriticalSectionStrictlyOneAtATime() throws Exception {
    String runId = "run-mutual-exclusion";
    Object lock = locks.lockFor(runId);
    var inCritical = new AtomicInteger();
    var peak = new AtomicInteger();
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(2);
    Runnable worker =
        () -> {
          await(start);
          try {
            for (int iteration = 0; iteration < 500; iteration++) {
              synchronized (lock) {
                int current = inCritical.incrementAndGet();
                peak.accumulateAndGet(current, Math::max);
                Thread.onSpinWait();
                inCritical.decrementAndGet();
              }
            }
          } finally {
            done.countDown();
          }
        };
    var first = new Thread(worker, "lock-a");
    var second = new Thread(worker, "lock-b");
    first.start();
    second.start();
    start.countDown();
    assertTrue(done.await(10, TimeUnit.SECONDS), "同一 runId 的临界区不应死锁");
    assertEquals(1, peak.get(), "同一 runId 绝不允许两个线程同时进入临界区");
  }

  /**
   * 不同槽位的两个 runId 必须能同时停留在各自临界区。
   *
   * <p>若仍然共用一把全局锁，先进入的线程会一直等到对端退出，第二个线程永远进不来，本用例会超时失败。
   */
  @Test
  void differentStripesEnterCriticalSectionsConcurrently() throws Exception {
    String first = runIdInStripe(0);
    String second = runIdInStripe(1);
    Object firstLock = locks.lockFor(first);
    Object secondLock = locks.lockFor(second);
    var firstEntered = new CountDownLatch(1);
    var secondEntered = new CountDownLatch(1);
    var firstFailure = new AtomicReference<Throwable>();
    var secondFailure = new AtomicReference<Throwable>();
    var firstDone = new CountDownLatch(1);
    var secondDone = new CountDownLatch(1);

    Thread firstThread =
        new Thread(
            () -> {
              try {
                synchronized (firstLock) {
                  firstEntered.countDown();
                  assertTrue(
                      secondEntered.await(5, TimeUnit.SECONDS), "第二个运行必须能同时进入自己的临界区");
                }
              } catch (Throwable failure) {
                firstFailure.set(failure);
              } finally {
                firstDone.countDown();
              }
            },
            "stripe-0");
    Thread secondThread =
        new Thread(
            () -> {
              try {
                synchronized (secondLock) {
                  secondEntered.countDown();
                  assertTrue(
                      firstEntered.await(5, TimeUnit.SECONDS), "第一个运行必须能同时保持在自己的临界区");
                }
              } catch (Throwable failure) {
                secondFailure.set(failure);
              } finally {
                secondDone.countDown();
              }
            },
            "stripe-1");
    firstThread.start();
    secondThread.start();
    assertTrue(firstDone.await(10, TimeUnit.SECONDS), "不同槽位不应互相阻塞");
    assertTrue(secondDone.await(10, TimeUnit.SECONDS), "不同槽位不应互相阻塞");
    if (firstFailure.get() != null) throw new AssertionError(firstFailure.get());
    if (secondFailure.get() != null) throw new AssertionError(secondFailure.get());
  }

  private Object lockAt(int stripe) {
    // 通过一个确定落在该槽位的标识取得对应 monitor，用于断言 null 的回退槽位。
    return locks.lockFor(runIdInStripe(stripe));
  }

  /** 找到一个哈希落在指定槽位的运行标识。 */
  private static String runIdInStripe(int stripe) {
    for (int index = 0; index < 10_000; index++) {
      String candidate = "run-" + index;
      if (Math.floorMod(candidate.hashCode(), RunLockRegistry.STRIPES) == stripe) return candidate;
    }
    throw new IllegalStateException("无法为该槽位构造运行标识：" + stripe);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待被中断", interrupted);
    }
  }
}
