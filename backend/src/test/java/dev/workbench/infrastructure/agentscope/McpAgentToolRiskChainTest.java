package dev.workbench.infrastructure.agentscope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.tool.AgentToolContext;
import dev.workbench.framework.tool.ToolApprovalDecision;
import dev.workbench.framework.tool.ToolRiskAssessment;
import dev.workbench.framework.tool.ToolRiskLevel;
import dev.workbench.infrastructure.mcp.McpClientManager;
import dev.workbench.infrastructure.mcp.McpToolProvider;
import dev.workbench.persistence.entity.UserMcpServerEntity;
import dev.workbench.persistence.entity.UserMcpToolEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 真实联动：{@code McpAgentTool → ToolGateway → ConservativeToolRiskPolicy →
 * AgentRuntime.approvalDecision}。
 *
 * <p>反例对应 `S-06`：网关若拿不到 {@code mcp::} 前缀的工具名，风险策略永远只会给出 MEDIUM， 高危外部写操作就绕过了 Gate
 * 5.5。这里用真实网关与真实风险策略驱动真实 {@code McpAgentTool}， 只把 {@link AgentRuntime} 换成记录型实现（真正的 {@code
 * ControlledAgentRuntime} 联动在 {@code dev.workbench.orchestration.McpApprovalLinkageTest}
 * 中覆盖，因为它是包内可见类）。
 */
class McpAgentToolRiskChainTest {
  private final McpClientManager clients = mock(McpClientManager.class);
  private final AtomicReference<String> approvalToolName = new AtomicReference<>();
  private final AtomicReference<ToolRiskLevel> approvalRisk = new AtomicReference<>();
  private final AtomicReference<ToolKind> startedKind = new AtomicReference<>();

  private AgentRuntime recordingRuntime() {
    return new AgentRuntime() {
      @Override
      public void beforeModel(dev.workbench.domain.ModelKind model) {}

      @Override
      public void modelResult(Integer input, Integer output) {}

      @Override
      public void assemblyResolved(
          dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.Components components) {}

      @Override
      public java.util.UUID beforeTool(ToolKind kind, int version, String fingerprint) {
        startedKind.set(kind);
        return java.util.UUID.randomUUID();
      }

      @Override
      public void check() {}

      @Override
      public void event(EventType type, String message) {}

      @Override
      public void toolResult(String result) {}

      @Override
      public void toolRejected(String safeMessage) {}

      @Override
      public ToolApprovalDecision approvalDecision(
          String toolName, int version, String argsJson, ToolRiskAssessment risk) {
        approvalToolName.set(toolName);
        approvalRisk.set(risk.level());
        return ToolApprovalDecision.approve();
      }

      @Override
      public void verifyModelInput(String input) {}
    };
  }

  private McpAgentTool tool(String slug, String toolName, String riskLevel, String schema) {
    var server = mock(UserMcpServerEntity.class);
    when(server.getSlug()).thenReturn(slug);
    var definition = mock(UserMcpToolEntity.class);
    when(definition.getName()).thenReturn(toolName);
    when(definition.getDescription()).thenReturn("外部工具");
    when(definition.getRiskLevel()).thenReturn(riskLevel);
    when(definition.getInputSchemaJson()).thenReturn(schema);
    var binding = new McpToolProvider.Binding(server, definition);
    var context =
        new AgentToolContext(
            new ObjectMapper().findAndRegisterModules(),
            "ws-1",
            List.of("README.md"),
            dev.workbench.domain.Scenario.NORMAL,
            recordingRuntime(),
            new AtomicReference<>(),
            List.of("README.md"));
    return new McpAgentTool(binding, clients, context);
  }

  private String call(McpAgentTool tool, Map<String, Object> input) {
    return text(tool.callAsync(callParam(input)).block());
  }

  /** ToolResultBlock 的可见正文由其输出块拼接而成。 */
  private static String text(io.agentscope.core.message.ToolResultBlock block) {
    if (block == null || block.getOutput() == null) return "";
    var builder = new StringBuilder();
    for (var output : block.getOutput())
      if (output instanceof io.agentscope.core.message.TextBlock text && text.getText() != null)
        builder.append(text.getText());
    return builder.toString();
  }

  private static io.agentscope.core.tool.ToolCallParam callParam(Map<String, Object> input) {
    var block =
        io.agentscope.core.message.ToolUseBlock.builder()
            .id("call-1")
            .name("mcp::notes::create")
            .input(input)
            .build();
    var param = mock(io.agentscope.core.tool.ToolCallParam.class);
    when(param.getInput()).thenReturn(input);
    when(param.getToolUseBlock()).thenReturn(block);
    return param;
  }

  @Test
  void highRiskMcpWriteReachesApprovalWithExposedNameAndHighRisk() {
    when(clients.call(any(), anyString(), anyString())).thenReturn("{\"ok\":true}");

    var tool = tool("notes", "create", "HIGH", "{\"type\":\"object\",\"required\":[\"title\"]}");
    var result = call(tool, Map.of("title", "会议记录"));

    assertTrue(result.contains("ok"));
    assertEquals(ToolKind.MCP, startedKind.get(), "网关必须按 MCP 工具类型登记调用");
    assertEquals(
        "mcp::notes::create", approvalToolName.get(), "审批必须拿到带 mcp:: 前缀的真实工具名，否则风险策略前缀判定失效");
    assertEquals(ToolRiskLevel.HIGH, approvalRisk.get(), "高危外部写操作必须进 Gate 5.5");
  }

  @Test
  void lowRiskMcpReadStillCarriesExposedNameAndSkipsApproval() {
    when(clients.call(any(), anyString(), anyString())).thenReturn("{\"items\":[]}");

    var tool = tool("notes", "search", "LOW", "{\"type\":\"object\"}");
    call(tool, Map.of("q", "排序"));

    assertNull(approvalToolName.get(), "低风险只读工具不应触发审批");
    verify(clients).call(any(), eq("search"), anyString());
  }

  @Test
  void missingRequiredArgumentFailsBeforeCallingTheServer() {
    var tool = tool("notes", "create", "HIGH", "{\"type\":\"object\",\"required\":[\"title\"]}");

    // 网关把参数校验失败转成拒绝结果（同一套 Gate 检查），不会抛出也未触达外部服务。
    var result = call(tool, Map.of());

    assertTrue(result.contains("REJECTED"), "缺参必须在本地被拒绝：" + result);
    assertTrue(result.contains("INVALID_ARGUMENT"), "拒绝码必须可审计：" + result);
    verify(clients, never()).call(any(), anyString(), anyString());
    assertNull(approvalToolName.get(), "参数非法时不应进入审批流程");
  }

  private static String rootMessage(Throwable error) {
    var messages = new ArrayList<String>();
    for (Throwable current = error; current != null; current = current.getCause())
      if (current.getMessage() != null) messages.add(current.getMessage());
    return String.join(" | ", messages);
  }

  @Test
  void fingerprintDependsOnToolNameSoDifferentToolsDoNotCollide() {
    when(clients.call(any(), anyString(), anyString())).thenReturn("{}");
    var create = tool("notes", "create", "LOW", "{\"type\":\"object\"}");
    var remove = tool("notes", "remove", "LOW", "{\"type\":\"object\"}");

    call(create, Map.of());
    call(remove, Map.of());

    verify(clients).call(any(), eq("create"), anyString());
    verify(clients).call(any(), eq("remove"), anyString());
    // 同一参数 {} 的两个工具都必须真正执行：指纹包含工具名，不会被防循环拦截。
    verify(clients, times(2)).call(any(), anyString(), anyString());
  }
}
