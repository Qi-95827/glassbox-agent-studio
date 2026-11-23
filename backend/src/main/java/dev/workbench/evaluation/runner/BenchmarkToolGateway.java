package dev.workbench.evaluation.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.tool.ConservativeToolRiskPolicy;
import dev.workbench.framework.tool.ToolEffect;
import dev.workbench.framework.tool.ToolGateway;
import dev.workbench.framework.trace.GlassboxModels.GatewayCheck;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/** 将公开集声明的工具送入生产网关，执行回调只产生评测回执。 */
final class BenchmarkToolGateway {
  private final ObjectMapper json;
  private final EvalAgentRuntime runtime = new EvalAgentRuntime(1, false);

  BenchmarkToolGateway(ObjectMapper json) {
    this.json = json;
  }

  record Call(
      String name,
      JsonNode arguments,
      boolean executed,
      boolean denied,
      boolean duplicateDenied,
      List<GatewayCheck> checks) {}

  Call execute(
      String name, JsonNode args, String registeredName, ToolEffect effect, Runnable validate) {
    int before = runtime.gatewayChecks().size();
    var gateway =
        new ToolGateway(json, runtime, new AtomicReference<>(), new ConservativeToolRiskPolicy());
    String status;
    try {
      String argsJson = AgentEvalCase.json(canonical(args));
      String receipt =
          gateway.execute(
              ToolKind.MCP,
              registeredName,
              1,
              AgentAssemblySnapshot.sha256(name + "|" + argsJson),
              argsJson,
              effect,
              "公开集工具决策",
              value -> value,
              validate,
              null,
              null,
              null,
              () -> "evaluation_fixture");
      status = AgentEvalCase.decision(receipt).path("status").asText();
    } catch (RuntimeException e) {
      status = "ERROR";
    }
    var recorded = runtime.gatewayChecks();
    var checks = recorded.size() > before ? (List<?>) recorded.getLast().get("checks") : List.of();
    var typed =
        checks.stream()
            .filter(GatewayCheck.class::isInstance)
            .map(GatewayCheck.class::cast)
            .toList();
    boolean duplicate =
        typed.stream()
            .anyMatch(c -> "BUDGET_AND_LOOP".equals(c.gate()) && "DENY".equals(c.status()));
    return new Call(
        name,
        args,
        "SUCCEEDED".equals(status),
        "REJECTED".equals(status) || duplicate,
        duplicate,
        typed);
  }

  private static Object canonical(JsonNode node) {
    if (node.isObject()) {
      var fields = new TreeMap<String, Object>();
      node.fields()
          .forEachRemaining(field -> fields.put(field.getKey(), canonical(field.getValue())));
      return fields;
    }
    if (node.isArray()) return node.valueStream().map(BenchmarkToolGateway::canonical).toList();
    return node.isMissingNode() ? Map.of() : node;
  }
}
