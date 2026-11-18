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
import dev.workbench.framework.tool.ToolSpecification;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.ToolCallParam;
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
    var first = provider(ToolKind.SIMULATED_INSPECTION, 1, used, example("example_extension"));
    var unused = provider(ToolKind.WORKSPACE_READ, 1, used, example("unused_extension"));
    var registry = new AgentToolRegistry(List.of(first, unused));
    var toolkit = registry.assemble(Map.of(ToolKind.SIMULATED_INSPECTION, 1), context());
    assertEquals(List.of(ToolKind.SIMULATED_INSPECTION), used);
    assertEquals(java.util.Set.of("example_extension"), toolkit.getToolNames());
    assertNotNull(toolkit.getTool("example_extension"));
  }

  @Test
  void registeredExtensionAlwaysRecordsAnActualCallAndStructuredResult() throws Exception {
    var context = context();
    var id = UUID.randomUUID();
    when(context.runtime().beforeTool(eq(ToolKind.SIMULATED_INSPECTION), eq(1), anyString()))
        .thenReturn(id);
    var registry =
        new AgentToolRegistry(
            List.of(
                provider(
                    ToolKind.SIMULATED_INSPECTION,
                    1,
                    new ArrayList<>(),
                    example("controlled_extension"))));
    var toolkit = registry.assemble(Map.of(ToolKind.SIMULATED_INSPECTION, 1), context);
    var result = toolkit.callTool(call("controlled_extension")).block();
    assertNotNull(result);
    var value = context.json().readTree(((TextBlock) result.getOutput().getFirst()).getText());
    assertEquals("SUCCEEDED", value.path("status").asText());
    assertEquals("SIMULATED_INSPECTION", value.path("toolId").asText());
    assertEquals(id.toString(), value.path("toolCallId").asText());
    verify(context.runtime()).beforeTool(eq(ToolKind.SIMULATED_INSPECTION), eq(1), anyString());
    verify(context.runtime()).toolResult("模拟扩展完成。");
  }

  @Test
  void budgetRejectionPreventsAllProviderCallbacks() {
    var context = context();
    var stopped =
        new dev.workbench.framework.control.StopExecution(
            dev.workbench.domain.StopCause.TOOL_CALL_LIMIT, "工具总次数达到上限");
    when(context.runtime().beforeTool(eq(ToolKind.SIMULATED_INSPECTION), eq(1), anyString()))
        .thenThrow(stopped);
    var spec =
        new ToolSpecification<String>(
            "controlled_extension",
            "扩展工具",
            List.of(),
            List.of(),
            "开始",
            arguments -> fail("未通过预算校验，不能运行提供者校验"),
            (arguments, check) -> fail("未通过预算校验，不能运行业务动作"),
            (arguments, result) -> fail("没有工具结果"));
    var registry =
        new AgentToolRegistry(
            List.of(provider(ToolKind.SIMULATED_INSPECTION, 1, new ArrayList<>(), spec)));
    var toolkit = registry.assemble(Map.of(ToolKind.SIMULATED_INSPECTION, 1), context);
    assertSame(
        stopped,
        assertThrows(
            dev.workbench.framework.control.StopExecution.class,
            () ->
                toolkit
                    .getTool("controlled_extension")
                    .callAsync(call("controlled_extension"))
                    .block()));
    assertSame(stopped, context.failure().get());
    verify(context.runtime(), never()).toolResult(any());
  }

  @Test
  void rejectsDuplicateMissingAndMismatchedProvidersAndDuplicateNames() {
    var used = new ArrayList<ToolKind>();
    var one = provider(ToolKind.SIMULATED_INSPECTION, 1, used, example("example_extension"));
    assertThrows(IllegalArgumentException.class, () -> new AgentToolRegistry(List.of(one, one)));
    var registry = new AgentToolRegistry(List.of(one));
    assertThrows(
        IllegalStateException.class,
        () -> registry.assemble(Map.of(ToolKind.WORKSPACE_READ, 1), context()));
    assertThrows(
        IllegalStateException.class,
        () -> registry.assemble(Map.of(ToolKind.SIMULATED_INSPECTION, 2), context()));
    assertTrue(used.isEmpty());

    var wrongVersion =
        new AgentToolRegistry(
            List.of(
                provider(ToolKind.SIMULATED_INSPECTION, 2, used, example("example_extension"))));
    assertThrows(
        IllegalStateException.class,
        () -> wrongVersion.assemble(Map.of(ToolKind.SIMULATED_INSPECTION, 1), context()));
    assertTrue(used.isEmpty());

    var missingSpec =
        new AgentToolRegistry(List.of(provider(ToolKind.SIMULATED_INSPECTION, 1, used, null)));
    assertThrows(
        IllegalStateException.class,
        () -> missingSpec.assemble(Map.of(ToolKind.SIMULATED_INSPECTION, 1), context()));
    var workspace =
        new AgentToolContext(
            new ObjectMapper(),
            "work",
            List.of("a.txt"),
            Scenario.NORMAL,
            mock(AgentRuntime.class),
            new AtomicReference<>());
    var sameName =
        new AgentToolRegistry(
            List.of(one, provider(ToolKind.WORKSPACE_READ, 1, used, example("example_extension"))));
    assertThrows(
        IllegalStateException.class,
        () ->
            sameName.assemble(
                Map.of(ToolKind.SIMULATED_INSPECTION, 1, ToolKind.WORKSPACE_READ, 1), workspace));
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

  private static ToolCallParam call(String name) {
    var use =
        ToolUseBlock.builder()
            .id("extension-call")
            .name(name)
            .input(Map.of())
            .content("{}")
            .build();
    return ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).build();
  }

  private static ToolSpecification<String> example(String name) {
    return new ToolSpecification<>(
        name,
        "测试扩展",
        List.of(),
        List.of(),
        "模拟扩展开始。",
        arguments -> {},
        (arguments, check) -> "结果",
        (arguments, result) -> "模拟扩展完成。");
  }

  private static AgentToolProvider provider(
      ToolKind kind, int version, List<ToolKind> used, ToolSpecification<?> specification) {
    return new AgentToolProvider() {
      public ToolKind kind() {
        return kind;
      }

      public int contractVersion(int count) {
        return version;
      }

      public ToolSpecification<?> create(Scope scope) {
        used.add(kind);
        return specification;
      }
    };
  }
}
