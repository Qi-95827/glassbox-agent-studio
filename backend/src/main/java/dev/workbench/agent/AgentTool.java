package dev.workbench.agent;

import dev.workbench.domain.Scenario;
import dev.workbench.framework.tool.SimulatedTool;
import dev.workbench.framework.tool.ToolSpecification;
import java.util.List;

/** 声明模拟检查动作，定期接受网关提供的运行检查。 */
public final class AgentTool {
  private AgentTool() {}

  static ToolSpecification<String> specification(SimulatedTool delegate, Scenario scenario) {
    return new ToolSpecification<>(
        "inspect_simulated_issue",
        "检查模拟问题。结果为模拟数据，不访问真实仓库。",
        List.of(),
        List.of(scenario.code()),
        "AgentScope 调用了模拟检查工具。",
        arguments -> {},
        (arguments, check) -> delegate.execute(scenario, check),
        (arguments, result) -> result);
  }
}
