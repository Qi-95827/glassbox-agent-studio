package dev.workbench.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 验证业务线程池不会因 core=1 + 有界队列而退化为单线程，且支持空闲回收与优雅停机。 */
class AsyncConfigurationTest {

  @Test
  void workflowExecutorSupportsRealConcurrencyBeforeQueueFills() throws InterruptedException {
    var config = new AsyncConfiguration();
    ThreadPoolTaskExecutor taskExecutor = config.workflowRunTaskExecutor();
    try {
      ThreadPoolExecutor pool = (ThreadPoolExecutor) config.workflowRunExecutor(taskExecutor);
      assertEquals(4, pool.getCorePoolSize());
      assertEquals(4, pool.getMaximumPoolSize());
      assertTrue(pool.allowsCoreThreadTimeOut());

      CountDownLatch bothStarted = new CountDownLatch(2);
      CountDownLatch release = new CountDownLatch(1);
      Runnable worker =
          () -> {
            bothStarted.countDown();
            try {
              release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          };
      pool.submit(worker);
      pool.submit(worker);
      assertTrue(
          bothStarted.await(2, TimeUnit.SECONDS),
          "两个并发任务应在队列未满时同时启动，不应被单核心线程串行阻塞");
      release.countDown();
    } finally {
      taskExecutor.shutdown();
    }
  }
}
