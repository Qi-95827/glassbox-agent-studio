package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.model.AgentModelProvider;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.framework.tool.AgentToolContext;
import dev.workbench.framework.tool.AgentToolProvider;
import dev.workbench.framework.tool.AgentToolRegistry;
import dev.workbench.framework.tool.ToolGateway;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 检查提供者装配及失败边界，使用受控替身，不读取真实模型密钥。 */
class AgentExtensionRegistryTest {
  @Test
  void toolContextCopiesAuthorizationAndRejectsInconsistentWorkspace() {
    var paths = new ArrayList<>(List.of("one.txt"));
    var runtime = mock(AgentRuntime.class);
    var context =
        new AgentToolContext(
            new ObjectMapper(),
            "workspace",
            paths,
            Scenario.NORMAL,
            runtime,
            new AtomicReference<>());
    paths.add("other.txt");
    assertEquals(List.of("one.txt"), context.authorizedPaths());
    assertThrows(UnsupportedOperationException.class, () -> context.authorizedPaths().clear());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentToolContext(
                new ObjectMapper(),
                null,
                List.of("one.txt"),
                Scenario.NORMAL,
                runtime,
                new AtomicReference<>()));
  }

  @Test
  void onlyPlannedToolIsCreatedAndRegisteredWithAgentScope() {
    var used = new ArrayList<ToolKind>();
    var first = provider(ToolKind.SIMULATED_INSPECTION, 1, used, new ExampleTool());
    var unused = provider(ToolKind.WORKSPACE_READ, 1, used, new ExampleTool());
    var registry = new AgentToolRegistry(List.of(first, unused));
    var toolkit = registry.assemble(Map.of(ToolKind.SIMULATED_INSPECTION, 1), context());
    assertEquals(List.of(ToolKind.SIMULATED_INSPECTION), used);
    assertEquals(java.util.Set.of("example_extension"), toolkit.getToolNames());
    assertNotNull(toolkit.getTool("example_extension"));
  }

  @Test
  void extensionCanUseSharedGatewayForRuntimeAndStructuredResult() throws Exception {
    var context = context();
    when(context.runtime().beforeTool(eq(ToolKind.SIMULATED_INSPECTION), eq(1), anyString()))
        .thenReturn(UUID.randomUUID());
    var registry =
        new AgentToolRegistry(
            List.of(
                provider(
                    ToolKind.SIMULATED_INSPECTION,
                    1,
                    new ArrayList<>(),
                    new ControlledExampleTool(context))));
    var toolkit = registry.assemble(Map.of(ToolKind.SIMULATED_INSPECTION, 1), context);
    var call =
        io.agentscope.core.message.ToolUseBlock.builder()
            .id("extension-call")
            .name("controlled_extension")
            .input(Map.of())
            .content("{}")
            .build();
    var result =
        toolkit
            .callTool(
                io.agentscope.core.tool.ToolCallParam.builder()
                    .toolUseBlock(call)
                    .input(Map.of())
                    .build())
            .block();
    assertNotNull(result);
    var value =
        new ObjectMapper()
            .readTree(
                ((io.agentscope.core.message.TextBlock) result.getOutput().getFirst()).getText());
    if (value.isTextual()) value = new ObjectMapper().readTree(value.asText());
    assertEquals("SUCCEEDED", value.path("status").asText());
    assertEquals("SIMULATED_INSPECTION", value.path("toolId").asText());
    verify(context.runtime()).beforeTool(eq(ToolKind.SIMULATED_INSPECTION), eq(1), anyString());
    verify(context.runtime()).toolResult("模拟扩展完成。");
  }

  @Test
  void rejectsDuplicateMissingMismatchedAndMultiToolProviders() {
    var used = new ArrayList<ToolKind>();
    var one = provider(ToolKind.SIMULATED_INSPECTION, 1, used, new ExampleTool());
    assertThrows(IllegalArgumentException.class, () -> new AgentToolRegistry(List.of(one, one)));
    var registry = new AgentToolRegistry(List.of(one));
    assertThrows(
        IllegalStateException.class,
        () -> registry.assemble(Map.of(ToolKind.WORKSPACE_READ, 1), context()));
    assertThrows(
        IllegalStateException.class,
        () -> registry.assemble(Map.of(ToolKind.SIMULATED_INSPECTION, 2), context()));
    assertTrue(used.isEmpty());
    var multi =
        new AgentToolRegistry(
            List.of(provider(ToolKind.SIMULATED_INSPECTION, 1, used, new MultipleTools())));
    assertThrows(
        IllegalStateException.class,
        () -> multi.assemble(Map.of(ToolKind.SIMULATED_INSPECTION, 1), context()));
    var overwriting =
        new AgentToolRegistry(
            List.of(one, provider(ToolKind.WORKSPACE_READ, 1, used, new OverwritingTools())));
    assertThrows(
        RuntimeException.class,
        () ->
            overwriting.assemble(
                Map.of(ToolKind.SIMULATED_INSPECTION, 1, ToolKind.WORKSPACE_READ, 1), context()));
  }

  @Test
  void selectsOnlyRequestedModelAndRejectsInvalidProviders() {
    var model = mock(Model.class);
    var fake = mock(AgentModelProvider.class);
    when(fake.mode()).thenReturn(ModelMode.MOCK);
    when(fake.create(any())).thenReturn(model);
    var registry = new AgentModelRegistry(List.of(fake));
    var request = request(ModelMode.MOCK);
    assertSame(model, registry.create(request));
    verify(fake).create(request);
    assertThrows(IllegalStateException.class, () -> registry.create(request(ModelMode.DEEPSEEK)));
    assertThrows(IllegalArgumentException.class, () -> new AgentModelRegistry(List.of(fake, fake)));
    when(fake.create(any())).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> registry.create(request));
  }

  @Test
  void executionRequestRejectsScopeMismatchAndCopiesInputs() {
    var files = new ArrayList<>(List.of("one.txt"));
    var runtime = mock(AgentRuntime.class);
    var request =
        new AgentExecutionRequest(
            AgentRole.ANALYST,
            UUID.randomUUID().toString(),
            "目标",
            "workspace",
            files,
            false,
            Scenario.NORMAL,
            ModelMode.MOCK,
            CheckpointStage.BEFORE_TOOL,
            null,
            runtime);
    files.add("other.txt");
    assertEquals(List.of("one.txt"), request.fileScopes());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentExecutionRequest(
                AgentRole.ANALYST,
                UUID.randomUUID().toString(),
                "目标",
                null,
                List.of(),
                true,
                Scenario.NORMAL,
                ModelMode.MOCK,
                CheckpointStage.BEFORE_TOOL,
                null,
                runtime));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentExecutionRequest(
                AgentRole.ANALYST,
                UUID.randomUUID().toString(),
                "目标",
                "workspace",
                List.of(),
                false,
                Scenario.NORMAL,
                ModelMode.MOCK,
                CheckpointStage.BEFORE_TOOL,
                null,
                runtime));
  }

  private static AgentExecutionRequest request(ModelMode mode) {
    return new AgentExecutionRequest(
        AgentRole.ANALYST,
        UUID.randomUUID().toString(),
        "目标",
        null,
        List.of(),
        false,
        Scenario.NORMAL,
        mode,
        CheckpointStage.BEFORE_TOOL,
        null,
        mock(AgentRuntime.class));
  }

  private static AgentToolContext context() {
    return new AgentToolContext(
        new ObjectMapper().findAndRegisterModules(),
        null,
        List.of(),
        Scenario.NORMAL,
        mock(AgentRuntime.class),
        new AtomicReference<>());
  }

  private static AgentToolProvider provider(
      ToolKind kind, int version, List<ToolKind> used, Object tool) {
    return new AgentToolProvider() {
      public ToolKind kind() {
        return kind;
      }

      public int contractVersion(int count) {
        return version;
      }

      public Object create(AgentToolContext context) {
        used.add(kind);
        return tool;
      }
    };
  }

  public static class ExampleTool {
    @Tool(name = "example_extension", description = "测试扩展")
    public String run() {
      return "ok";
    }
  }

  /** 演示扩展工具复用网关，而非自行绕过预算和调用记录。 */
  public static class ControlledExampleTool {
    private final ToolGateway gateway;

    ControlledExampleTool(AgentToolContext context) {
      this.gateway = context.gateway();
    }

    @Tool(name = "controlled_extension", description = "受控的测试扩展", readOnly = true)
    public String run() {
      return gateway.execute(
          ToolKind.SIMULATED_INSPECTION,
          1,
          dev.workbench.framework.tool.ToolFingerprint.of(ToolKind.SIMULATED_INSPECTION),
          dev.workbench.framework.tool.ToolEffect.READ_ONLY,
          "模拟扩展开始。",
          result -> "模拟扩展完成。",
          () -> "结果");
    }
  }

  public static class MultipleTools {
    @Tool(name = "first_extension", description = "第一个测试扩展")
    public String first() {
      return "ok";
    }

    @Tool(name = "second_extension", description = "第二个测试扩展")
    public String second() {
      return "ok";
    }
  }

  /** 模拟错误扩展重复声明既有工具名并额外注册另一个名称。 */
  public static class OverwritingTools {
    @Tool(name = "example_extension", description = "覆盖已有名称")
    public String overwrite() {
      return "bad";
    }

    @Tool(name = "extra_extension", description = "额外工具")
    public String extra() {
      return "bad";
    }
  }
}
