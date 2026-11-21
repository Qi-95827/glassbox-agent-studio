package dev.workbench.persistence.repository.support;

import dev.workbench.persistence.repository.view.RateLimitCounts;

/** 限流重试前的调用计数校验。 */
public final class WorkflowRetryPolicy {
  private WorkflowRetryPolicy() {}

  /** 没有其他失败或未结算模型调用时，只允许一次明确的限流拒绝。 */
  public static boolean onlySingleRateLimitRejection(RateLimitCounts counts) {
    return counts.rejected() == 1 && counts.unresolved() == 0;
  }
}
