package dev.workbench.infrastructure.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.tool.AgentToolContext;
import dev.workbench.framework.tool.ToolEffect;
import dev.workbench.framework.tool.ToolGateway;
import dev.workbench.infrastructure.mcp.McpClientManager;
import dev.workbench.infrastructure.mcp.McpToolProvider;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 将 MCP 工具包装为标准 AgentScope 工具，强制经过 Tool Gateway 六道安检门与 Gate 5.5。 */
final class McpAgentTool extends ToolBase {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final McpToolProvider.Binding binding;
  private final McpClientManager clients;
  private final AgentToolContext context;

  McpAgentTool(McpToolProvider.Binding binding, McpClientManager clients, AgentToolContext context) {
    super(
        ToolBase.builder()
            .name(binding.exposedName())
            .description(
                (binding.tool().getDescription() == null ? "" : binding.tool().getDescription())
                    + " [MCP "
                    + binding.server().getSlug()
                    + "]")
            .inputSchema(schema(binding.tool().getInputSchemaJson()))
            .concurrencySafe(false)
            .readOnly(!"HIGH".equals(binding.riskLevel())));
    this.binding = binding;
    this.clients = clients;
    this.context = context;
  }

  @Override
  public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
    return Mono.fromCallable(
            () ->
                ToolResultBlock.text(invoke(call.getInput()))
                    .withIdAndName(call.getToolUseBlock().getId(), getName()))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private String invoke(Map<String, Object> input) throws Exception {
    String argsJson = JSON.writeValueAsString(input == null ? Map.of() : input);
    ToolEffect effect = "HIGH".equals(binding.riskLevel()) ? ToolEffect.WRITE : ToolEffect.READ_ONLY;
    var gateway = new ToolGateway(context.json(), context.runtime(), context.failure());
    return gateway.execute(
        ToolKind.MCP,
        1,
        dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.sha256(argsJson),
        argsJson,
        effect,
        "调用 MCP 工具 " + binding.exposedName(),
        value -> value == null ? "" : value.substring(0, Math.min(500, value.length())),
        () -> requireObject(argsJson),
        null,
        McpAgentTool::requireObject,
        edited -> clients.call(binding.server(), binding.tool().getName(), edited),
        () -> clients.call(binding.server(), binding.tool().getName(), argsJson));
  }

  private static void requireObject(String argsJson) {
    try {
      if (!JSON.readTree(argsJson).isObject()) throw new IllegalArgumentException("MCP 参数必须是 JSON 对象。");
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("MCP 参数不是有效 JSON。", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> schema(String raw) {
    if (raw == null || raw.isBlank()) return Map.of("type", "object", "properties", Map.of());
    try {
      return JSON.readValue(raw, Map.class);
    } catch (Exception e) {
      return new LinkedHashMap<>(Map.of("type", "object", "properties", Map.of()));
    }
  }
}
