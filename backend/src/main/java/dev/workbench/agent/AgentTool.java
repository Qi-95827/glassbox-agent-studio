package dev.workbench.agent;

import dev.workbench.domain.Scenario;
import dev.workbench.framework.prompt.PromptResourceLoader;
import dev.workbench.framework.tool.SimulatedTool;
import dev.workbench.framework.tool.ToolSpecification;
import java.util.List;

/** 声明模拟检查动作，定期接受网关提供的运行检查。 */
public final class AgentTool {
  private AgentTool() {}

  static ToolSpecification<String> specification(SimulatedTool delegate, Scenario scenario) {
    return specification(delegate, scenario, null);
  }

  static ToolSpecification<String> specification(
      SimulatedTool delegate, Scenario scenario, String locale) {
    var copy = PromptResourceLoader.tool(locale, "simulated_inspection");
    boolean english = PromptResourceLoader.isEnglish(locale);
    return new ToolSpecification<>(
        "inspect_simulated_issue",
        copy.description(),
        List.of(),
        List.of(scenario.code()),
        english
            ? "AgentScope called the simulated inspection tool."
            : "AgentScope 调用了模拟检查工具。",
        arguments -> {},
        (arguments, check) -> delegate.execute(scenario, check),
        (arguments, result) -> result);
  }
}
