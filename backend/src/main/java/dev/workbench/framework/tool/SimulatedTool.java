package dev.workbench.framework.tool;

import dev.workbench.domain.Scenario;
import org.springframework.stereotype.Component;

/** 仅用于演示的无外部副作用工具，不访问仓库或网络。 */
@Component
public class SimulatedTool {
  /** 定期检查取消与预算，让慢速场景能够测试中断传播。 */
  public String execute(Scenario scenario, Runnable check) throws InterruptedException {
    int steps = scenario == Scenario.SLOW ? 100 : 5;
    for (int i = 0; i < steps; i++) {
      Thread.sleep(100);
      check.run();
    }
    if (scenario == Scenario.FAIL) throw new IllegalStateException("模拟工具返回失败，未产生分析结果。");
    return "模拟检查发现需要进一步核对输入和预期行为";
  }
}
