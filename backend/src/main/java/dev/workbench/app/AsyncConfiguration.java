package dev.workbench.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** 统一托管业务异步线程池，禁止各 Service 私建执行器。 */
@Configuration
@EnableAsync
public class AsyncConfiguration {

  @Bean(name = "workflowRunExecutor", destroyMethod = "shutdown")
  public ExecutorService workflowRunExecutor() {
    return pool(1, 4, 200, "workflow-run-");
  }

  @Bean(name = "planningExecutor", destroyMethod = "shutdown")
  public ExecutorService planningExecutor() {
    return pool(1, 2, 100, "planning-");
  }

  @Bean(name = "evaluationExecutor", destroyMethod = "shutdown")
  public ExecutorService evaluationExecutor() {
    return pool(1, 2, 100, "evaluation-");
  }

  private static ExecutorService pool(int core, int max, int queue, String prefix) {
    AtomicInteger sequence = new AtomicInteger();
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    return new GracefulExecutor(
        core,
        max,
        30,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(queue),
        factory,
        new ThreadPoolExecutor.AbortPolicy());
  }

  /** 拒绝策略保持 AbortPolicy：调用方必须显式处理队列满，而不是静默降级。 */
  private static final class GracefulExecutor extends ThreadPoolExecutor {
    private GracefulExecutor(
        int core,
        int max,
        long keepAlive,
        TimeUnit unit,
        java.util.concurrent.BlockingQueue<Runnable> queue,
        ThreadFactory factory,
        RejectedExecutionHandler handler) {
      super(core, max, keepAlive, unit, queue, factory, handler);
    }

    @Override
    public void shutdown() {
      super.shutdown();
      try {
        if (!awaitTermination(30, TimeUnit.SECONDS)) super.shutdownNow();
      } catch (InterruptedException e) {
        super.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }
}
