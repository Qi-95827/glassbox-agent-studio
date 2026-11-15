package dev.workbench.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.ToolKind;
import dev.workbench.tool.SimulatedTool;
import dev.workbench.tool.ToolContracts;
import dev.workbench.tool.ToolEffect;
import dev.workbench.tool.ToolFingerprint;
import io.agentscope.core.tool.Tool;
import java.util.concurrent.atomic.AtomicReference;

/** 使用标准工具注册机制；只有 AgentScope 收到模型工具调用时才执行。 */
public final class AgentTool {
  private final SimulatedTool delegate;
  private final Scenario scenario;
  private final AgentRuntime runtime;
  private final ToolGateway gateway;

  AgentTool(
      SimulatedTool delegate,
      Scenario scenario,
      ObjectMapper json,
      AgentRuntime runtime,
      AtomicReference<RuntimeException> failure) {
    this.delegate = delegate;
    this.scenario = scenario;
    this.runtime = runtime;
    this.gateway = new ToolGateway(json, runtime, failure);
  }

  @Tool(name = "inspect_simulated_issue", description = "检查模拟问题。结果为模拟数据，不访问真实仓库。", readOnly = true)
  public String inspect() {
    return gateway.execute(
        ToolKind.SIMULATED_INSPECTION,
        ToolContracts.SIMULATED_VERSION,
        ToolFingerprint.of(ToolKind.SIMULATED_INSPECTION, scenario.code()),
        ToolEffect.READ_ONLY,
        "AgentScope 调用了模拟检查工具。",
        result -> result,
        () -> delegate.execute(scenario, runtime::check));
  }
}
