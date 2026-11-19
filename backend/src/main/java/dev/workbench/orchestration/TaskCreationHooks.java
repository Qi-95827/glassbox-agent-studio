package dev.workbench.orchestration;

import org.springframework.stereotype.Component;

/**
 * 创建事务的测试注入点。
 *
 * <p>独立成 Bean，避免 CGLIB 事务代理与测试直接改写目标对象字段不一致；生产默认不执行任何动作。
 */
@Component
public class TaskCreationHooks {
  private volatile Runnable beforeEntryPolicySave = () -> {};

  public void runBeforeEntryPolicySave() {
    beforeEntryPolicySave.run();
  }

  public void setBeforeEntryPolicySave(Runnable hook) {
    this.beforeEntryPolicySave = hook == null ? () -> {} : hook;
  }

  public void reset() {
    this.beforeEntryPolicySave = () -> {};
  }
}
