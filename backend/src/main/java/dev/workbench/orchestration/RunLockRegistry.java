package dev.workbench.orchestration;

import java.util.Objects;

/**
 * 按运行标识分段的进程内锁注册表。
 *
 * <p>收口前全平台共用一把 {@code stateLock}：不同 {@code runId} 的状态迁移、节点认领、取消与恢复全部串行排队，
 * 一个慢运行会阻塞其他所有运行。这里改为固定 {@value #STRIPES} 个槽位的分段锁：
 *
 * <ul>
 *   <li>同一 {@code runId} 的哈希恒定命中同一把 monitor 锁，串行语义与原来完全一致；
 *   <li>不同 {@code runId} 只要落在不同槽位就真正并发，互不阻塞；
 *   <li>槽位数组在构造时一次性创建，运行结束不回收任何对象，因此零内存泄漏、零对象回收竞态。
 * </ul>
 *
 * <p>槽位数量固定为 64 是刻意选择：既能显著降低无关运行互相碰撞的概率，又不会让监控对象表膨胀。
 */
final class RunLockRegistry {
  /** 固定槽位数量。 */
  static final int STRIPES = 64;

  private final Object[] stripes = new Object[STRIPES];

  RunLockRegistry() {
    for (int index = 0; index < STRIPES; index++) stripes[index] = new Object();
  }

  /**
   * 返回该运行独有的分段锁。
   *
   * @param runId 运行标识；{@code null} 回退到 0 号槽位，调用方无需先判空
   */
  Object lockFor(String runId) {
    if (runId == null) return stripes[0];
    return stripes[Math.floorMod(Objects.hashCode(runId), STRIPES)];
  }

  /** 该运行固定命中的槽位序号，供测试与诊断断言使用。 */
  int stripeOf(String runId) {
    if (runId == null) return 0;
    return Math.floorMod(Objects.hashCode(runId), STRIPES);
  }
}
