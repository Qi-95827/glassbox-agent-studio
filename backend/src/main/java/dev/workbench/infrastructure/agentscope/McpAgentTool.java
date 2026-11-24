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

  McpAgentTool(
      McpToolProvider.Binding binding, McpClientManager clients, AgentToolContext context) {
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
    ToolEffect effect =
        "HIGH".equals(binding.riskLevel()) ? ToolEffect.WRITE : ToolEffect.READ_ONLY;
    var gateway = new ToolGateway(context.json(), context.runtime(), context.failure());
    // 必须使用带 toolName 的重载：否则网关拿到的是枚举名 "MCP"，风险策略的 mcp:: 前缀判定失效。
    return gateway.execute(
        ToolKind.MCP,
        binding.exposedName(),
        1,
        fingerprint(argsJson),
        argsJson,
        effect,
        "调用 MCP 工具 " + binding.exposedName(),
        value -> value == null ? "" : value.substring(0, Math.min(500, value.length())),
        () -> requireSchema(argsJson),
        null,
        edited -> requireSchema(edited),
        edited -> clients.call(binding.server(), binding.tool().getName(), edited),
        () -> clients.call(binding.server(), binding.tool().getName(), argsJson));
  }

  /** 调用指纹必须包含工具名：不同 MCP 工具用同样的 `{}` 参数不能被判成重复调用而误拦截。 */
  private String fingerprint(String argsJson) {
    return dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.sha256(
        binding.exposedName() + '\u0000' + argsJson);
  }

  /** 按 inputSchema 的 required 列表补全必选参数校验：缺参直接失败，不把半成品请求发给外部服务。 */
  private void requireSchema(String argsJson) {
    requireObject(argsJson);
    var required = requiredNames(binding.tool().getInputSchemaJson());
    if (required.isEmpty()) return;
    try {
      var node = JSON.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
      for (String name : required)
        if (!node.has(name) || node.get(name).isNull())
          throw new IllegalArgumentException("MCP 工具缺少必选参数：" + name);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("MCP 参数不是有效 JSON。", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static java.util.List<String> requiredNames(String schemaJson) {
    if (schemaJson == null || schemaJson.isBlank()) return java.util.List.of();
    try {
      var schema = JSON.readValue(schemaJson, java.util.Map.class);
      Object required = schema.get("required");
      if (!(required instanceof java.util.List<?> items)) return java.util.List.of();
      var names = new java.util.ArrayList<String>(items.size());
      for (Object item : items) if (item instanceof String name) names.add(name);
      return names;
    } catch (Exception e) {
      return java.util.List.of();
    }
  }

  private static void requireObject(String argsJson) {
    try {
      if (!JSON.readTree(argsJson).isObject())
        throw new IllegalArgumentException("MCP 参数必须是 JSON 对象。");
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
