package dev.workbench.infrastructure.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.control.MultiTierContextCompactor;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblyPlanner;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentRoleRegistry;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.context.AgentInput;
import dev.workbench.framework.model.AgentModelProvider;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.framework.model.ModelClient;
import dev.workbench.framework.skill.SkillRegistry;
import dev.workbench.framework.tool.ToolApprovalDecision;
import dev.workbench.framework.tool.ToolRiskAssessment;
import dev.workbench.infrastructure.mcp.McpClientManager;
import dev.workbench.infrastructure.mcp.McpToolProvider;
import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.entity.UserMcpServerEntity;
import dev.workbench.persistence.entity.UserMcpToolEntity;
import dev.workbench.persistence.repository.UserMcpServerRepository;
import dev.workbench.persistence.repository.UserMcpToolRepository;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentStateStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * 穿过真实 {@link AgentScopeExecutor} 与真实（受控数据源的）{@link McpToolProvider}，
 * 验证逐 MCP 工具的真实准入/拒绝决定被写进 {@link AgentAssemblySnapshot.Components#mcpToolDecisions()}。
 *
 * <p>反例：此前只发一条 {@code TOOL_REJECTED} 事件日志，装配快照里没有任何逐工具明细，
 * 玻璃盒无法回答「这个外部工具为什么没给我」。
 */
class McpToolAdmissionAssemblyTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final AtomicReference<AgentAssemblySnapshot.Components> captured = new AtomicReference<>();

  @Test
  void analystNodeAdmitsLowRiskAndStripsHighRiskMcpTool() throws Exception {
    var components = execute(AgentRole.ANALYST);

    var decisions = components.mcpToolDecisions();
    assertEquals(2, decisions.size(), "两个外部工具都必须留下准入明细");
    var search = decisions.getFirst();
    assertEquals("mcp::notes::search", search.exposedName());
    assertEquals("LOW", search.riskLevel());
    assertTrue(search.allowed());
    assertEquals("", search.reasonCode());
    var create = decisions.get(1);
    assertEquals("mcp::notes::create", create.exposedName());
    assertEquals("HIGH", create.riskLevel());
    assertFalse(create.allowed());
    assertEquals("ANALYST_HIGH_RISK_MCP_FORBIDDEN", create.reasonCode());

    assertEquals(
        1,
        components.tools().getOrDefault(ToolKind.MCP, 0),
        "只有真正注册的外部工具才会暴露 ToolKind.MCP");
  }

  @Test
  void reviewerNodeStripsEveryMcpTool() throws Exception {
    var components = execute(AgentRole.REVIEWER);

    var decisions = components.mcpToolDecisions();
    assertEquals(2, decisions.size());
    for (var decision : decisions) {
      assertFalse(decision.allowed(), "评审角色不得装配任何外部工具：" + decision);
      assertEquals("REVIEWER_MCP_FORBIDDEN", decision.reasonCode());
    }
    assertFalse(
        components.tools().containsKey(ToolKind.MCP),
        "零注册时不得暴露 ToolKind.MCP，否则运行时 beforeTool 会误判越权");
  }

  @Test
  void legacySnapshotJsonWithoutMcpDecisionsStillDeserializes() throws Exception {
    // 历史快照没有 mcpToolDecisions 字段，反序列化必须归一为空列表而不是 null。
    String legacy =
        "{\"version\":1,\"components\":{\"definition\":{\"id\":\"analyst\",\"version\":1},"
            + "\"definitionSha256\":\""
            + "a".repeat(64)
            + "\",\"model\":{\"mode\":\"MOCK\",\"name\":\"mock\",\"configurationVersion\":1},"
            + "\"maxIterations\":null,\"maxFrameworkRetries\":1,\"skills\":[],\"tools\":{},"
            + "\"authorization\":{\"policyVersion\":1,\"profile\":\"ANALYST\",\"profileVersion\":1,"
            + "\"workspaceId\":null,\"fileCount\":0,\"fileScopeSha256\":null,\"writeAuthorized\":false},"
            + "\"input\":{\"sha256\":\""
            + "b".repeat(64)
            + "\",\"sources\":[]},\"privateTools\":[]},"
            + "\"budget\":{\"maxIterations\":1}}";

    // 只校验 components 段能被解析：整体快照还依赖 budget 结构，这里用同构的最小载荷即可。
    var tree = json.readTree(legacy).get("components");
    var components = json.treeToValue(tree, AgentAssemblySnapshot.Components.class);

    assertNotNull(components.mcpToolDecisions(), "缺失字段必须归一为空列表");
    assertTrue(components.mcpToolDecisions().isEmpty());
  }

  private AgentAssemblySnapshot.Components execute(AgentRole role) throws Exception {
    // 工具注册表只为提供空 Toolkit：本用例关注的是外部 MCP 工具的准入决定，不重复验证内置工具契约。
    var toolRegistry = mock(AgentToolRegistry.class);
    when(toolRegistry.assemble(any(), any())).thenReturn(new io.agentscope.core.tool.Toolkit());
    when(toolRegistry.assemblePrivate(any(), any())).thenReturn(new io.agentscope.core.tool.Toolkit());
    var executor =
        new AgentScopeExecutor(
            json,
            new io.agentscope.core.state.InMemoryAgentStateStore(),
            new AgentAssemblyPlanner(new AgentRoleRegistry(), new SkillRegistry()),
            new AgentModelRegistry(List.of(mockProvider())),
            toolRegistry,
            new AgentRoleRegistry(),
            new SkillRegistry(),
            new MultiTierContextCompactor(),
            provider(null),
            provider(mcpProvider()),
            provider(mock(McpClientManager.class)),
            provider(null));

    String agentId = UUID.randomUUID().toString();
    var request =
        new AgentExecutionRequest(
            role,
            agentId,
            new AgentInput("run-1", "分析空状态缺失问题。", List.of()),
            null,
            List.of(),
            false,
            Scenario.NORMAL,
            ModelMode.MOCK,
            CheckpointStage.BEFORE_TOOL,
            null,
            recordingRuntime(),
            null,
            null,
            List.of(),
            null,
            "zh-CN",
            null);

    executor.execute(request);
    var components = captured.get();
    assertNotNull(components, "装配结果必须真实回调到运行控制");
    return components;
  }

  /** 受控数据源的真实 {@link McpToolProvider}：一个低风险只读工具 + 一个高风险写工具。 */
  private static McpToolProvider mcpProvider() {
    var servers = mock(UserMcpServerRepository.class);
    var tools = mock(UserMcpToolRepository.class);
    var ownership = mock(OwnershipService.class);
    when(ownership.taskIdOfRun("run-1")).thenReturn("task-1");
    when(ownership.owner(OwnershipService.TASK, "task-1")).thenReturn(Optional.of("LOCAL"));

    var server = mock(UserMcpServerEntity.class);
    when(server.getSlug()).thenReturn("notes");
    when(server.getId()).thenReturn(UUID.randomUUID().toString());
    when(servers.findByOwnerScopeKeyAndEnabledTrueOrderByUpdatedAtDesc("LOCAL"))
        .thenReturn(List.of(server));

    var low = tool("search", "LOW");
    var high = tool("create", "HIGH");
    when(tools.findByServerIdAndEnabledTrueOrderByNameAsc(any())).thenReturn(List.of(low, high));
    return new McpToolProvider(servers, tools, ownership);
  }

  private static UserMcpToolEntity tool(String name, String riskLevel) {
    var entity = mock(UserMcpToolEntity.class);
    when(entity.getName()).thenReturn(name);
    when(entity.getDescription()).thenReturn("外部工具");
    when(entity.getRiskLevel()).thenReturn(riskLevel);
    when(entity.getInputSchemaJson()).thenReturn("{\"type\":\"object\"}");
    return entity;
  }

  private static AgentModelProvider mockProvider() {
    return new AgentModelProvider() {
      @Override
      public ModelMode mode() {
        return ModelMode.MOCK;
      }

      @Override
      public ModelClient create(AgentExecutionRequest request) {
        return new AgentScopeModelClient(new FinalTextModel());
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> stub = mock(ObjectProvider.class);
    when(stub.getIfAvailable()).thenReturn(value);
    return stub;
  }

  /** 只回一句最终答复的确定性模型，让装配阶段真实跑完。 */
  private static final class FinalTextModel implements Model {
    @Override
    public String getModelName() {
      return "final-text";
    }

    @Override
    public Flux<ChatResponse> stream(
        List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
      return Flux.just(
          ChatResponse.builder()
              .content(List.of(TextBlock.builder().text("分析完成：空状态缺少提示文案。").build()))
              .build());
    }
  }

  private AgentRuntime recordingRuntime() {
    return new AgentRuntime() {
      @Override
      public void beforeModel(dev.workbench.domain.ModelKind model) {}

      @Override
      public void modelResult(Integer input, Integer output) {}

      @Override
      public void assemblyResolved(AgentAssemblySnapshot.Components components) {
        captured.set(components);
      }

      @Override
      public UUID beforeTool(ToolKind kind, int version, String fingerprint) {
        return UUID.randomUUID();
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
        return ToolApprovalDecision.approve();
      }

      @Override
      public void verifyModelInput(String input) {}
    };
  }
}
