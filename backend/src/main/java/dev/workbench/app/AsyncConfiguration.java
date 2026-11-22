package dev.workbench.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 统一托管业务异步线程池，禁止各 Service 私建执行器。
 *
 * <p>采用 core == max 并开启 allowCoreThreadTimeOut：既避免有界队列填满前永远停留在单线程的经典陷阱，
 * 也能在空闲时自动回收工作线程；停机时由 Spring ThreadPoolTaskExecutor 统一等待进行中的任务结束。
 */
@Configuration
@EnableAsync
public class AsyncConfiguration {

  @Bean(name = "workflowRunTaskExecutor")
  public ThreadPoolTaskExecutor workflowRunTaskExecutor() {
    return buildExecutor(4, 200, "workflow-run-");
  }

  /** 节点级并行执行器；单运行并发数由调度器按配置再次限制。 */
  @Bean(name = "workflowNodeTaskExecutor")
  public ThreadPoolTaskExecutor workflowNodeTaskExecutor(
      @org.springframework.beans.factory.annotation.Value("${workbench.orchestration.max-parallel-nodes:3}")
          int maxParallelPerRun) {
    return buildExecutor(Math.max(1, maxParallelPerRun) * 4, 200, "workflow-node-");
  }

  @Bean(name = "workflowNodeExecutor", destroyMethod = "")
  public ExecutorService workflowNodeExecutor(
      @Qualifier("workflowNodeTaskExecutor") ThreadPoolTaskExecutor executor) {
    return executor.getThreadPoolExecutor();
  }

  @Bean(name = "workflowRunExecutor", destroyMethod = "")
  public ExecutorService workflowRunExecutor(
      @Qualifier("workflowRunTaskExecutor") ThreadPoolTaskExecutor executor) {
    return executor.getThreadPoolExecutor();
  }

  @Bean(name = "planningTaskExecutor")
  public ThreadPoolTaskExecutor planningTaskExecutor() {
    // 候选计划生成保持单线程有序执行，避免同一用户连续提交草稿时产生旧候选反超新候选的乱序竞态。
    return buildExecutor(1, 100, "planning-");
  }

  @Bean(name = "planningExecutor", destroyMethod = "")
  public ExecutorService planningExecutor(
      @Qualifier("planningTaskExecutor") ThreadPoolTaskExecutor executor) {
    return executor.getThreadPoolExecutor();
  }

  @Bean(name = "evaluationTaskExecutor")
  public ThreadPoolTaskExecutor evaluationTaskExecutor() {
    return buildExecutor(2, 100, "evaluation-");
  }

  @Bean(name = "mcpTaskExecutor")
  public ThreadPoolTaskExecutor mcpTaskExecutor() {
    return buildExecutor(2, 100, "mcp-");
  }

  @Bean(name = "mcpExecutor", destroyMethod = "")
  public ExecutorService mcpExecutor(
      @Qualifier("mcpTaskExecutor") ThreadPoolTaskExecutor executor) {
    return executor.getThreadPoolExecutor();
  }
  @Bean(name = "evaluationExecutor", destroyMethod = "")
  public ExecutorService evaluationExecutor(
      @Qualifier("evaluationTaskExecutor") ThreadPoolTaskExecutor executor) {
    return executor.getThreadPoolExecutor();
  }

  /** SSE 心跳与连接清理使用托管调度器，禁止裸线程。 */
  @Bean(name = "streamTaskScheduler")
  public org.springframework.scheduling.TaskScheduler streamTaskScheduler() {
    var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("run-stream-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
    return scheduler;
  }

  /** 拒绝策略保持 AbortPolicy：调用方必须显式处理队列满，而不是静默降级。 */
  private static ThreadPoolTaskExecutor buildExecutor(int concurrency, int queue, String prefix) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(concurrency);
    executor.setMaxPoolSize(concurrency);
    executor.setQueueCapacity(queue);
    executor.setKeepAliveSeconds(30);
    executor.setAllowCoreThreadTimeOut(true);
    executor.setThreadNamePrefix(prefix);
    executor.setDaemon(true);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(5);
    executor.initialize();
    return executor;
  }
}
