package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblyPlanner;
import dev.workbench.framework.agent.definition.AgentDefinition;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.agent.definition.AgentDefinitionRegistry;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.model.AgentModelProvider;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.framework.skill.SkillId;
import dev.workbench.framework.skill.SkillRegistry;
import dev.workbench.framework.tool.AgentToolRegistry;
import dev.workbench.infrastructure.agentscope.AgentScopeExecutor;
import dev.workbench.infrastructure.model.DemoModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;

/** 新定义必须通过实际 AgentScope 装配路径，同时沿用旧权限边界。 */
class AgentDefinitionRegistryTest {
  private final AgentRoleRegistry roles = new AgentRoleRegistry();
  private final SkillRegistry skills = new SkillRegistry();

  @Test
  void trustedDefinitionRunsThroughAgentScopeWithoutAddingAnAgentRole() throws Exception {
    var reference = new AgentDefinitionRef("focused-review", 1);
    var candidate =
        new AgentDefinition(
            reference,
            "focused-review",
            "仅审查已有证据，不授予工具权限。",
            AgentRole.REVIEWER,
            List.of(),
            List.of(SkillId.REVIEW_EVIDENCE));
    var definitions = new AgentDefinitionRegistry(roles, skills, List.of(() -> candidate));
    var planner = new AgentAssemblyPlanner(roles, definitions, skills);
    var modelProvider =
        new AgentModelProvider() {
          public ModelMode mode() {
            return ModelMode.MOCK;
          }

          public io.agentscope.core.model.Model create(AgentExecutionRequest request) {
            return new DemoModel(request.role(), false, request.scenario(), List.of());
          }
        };
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:definition-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var stateStore = new JdbcAgentStateStore(dataSource, new H2Dialect(), true);
    var executor =
        new AgentScopeExecutor(
            new ObjectMapper(),
            stateStore,
            planner,
            new AgentModelRegistry(List.of(modelProvider)),
            new AgentToolRegistry(List.of()));
    var runtime = mock(AgentRuntime.class);
    var request =
        new AgentExecutionRequest(
            AgentRole.REVIEWER,
            UUID.randomUUID().toString(),
            "analysis-agent evidenceEventIds: []",
            null,
            List.of(),
            false,
            Scenario.NORMAL,
            ModelMode.MOCK,
            CheckpointStage.BEFORE_TOOL,
            null,
            runtime,
            reference);

    assertTrue(executor.execute(request).contains("审查完成"));
    var sequence = inOrder(runtime);
    sequence
        .verify(runtime)
        .assemblyResolved(
            argThat(
                components ->
                    reference.equals(components.definition())
                        && definitions.fingerprint(reference).equals(components.definitionSha256())
                        && components.skills().size() == 1
                        && components.tools().isEmpty()
                        && "mock".equals(components.model().name())));
    sequence.verify(runtime).beforeModel(any());
    clearInvocations(runtime);
    var wrongDigest =
        new AgentExecutionRequest(
            AgentRole.REVIEWER,
            UUID.randomUUID().toString(),
            "analysis-agent evidenceEventIds: []",
            null,
            List.of(),
            false,
            Scenario.NORMAL,
            ModelMode.MOCK,
            CheckpointStage.BEFORE_TOOL,
            null,
            runtime,
            reference,
            "0".repeat(64));
    assertThrows(IllegalStateException.class, () -> executor.execute(wrongDigest));
    verify(runtime, never()).beforeModel(any());
  }

  @Test
  void sameDefinitionUsesIndependentAgentScopeSessionsForDifferentInstances() throws Exception {
    var reference = new AgentDefinitionRef("isolated-review", 1);
    var definition =
        new AgentDefinition(
            reference, "review", "审查当前输入", AgentRole.REVIEWER, List.of(), List.of());
    var definitions = new AgentDefinitionRegistry(roles, skills, List.of(() -> definition));
    var observedInputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
    Model model =
        new Model() {
          public String getModelName() {
            return "isolation-test";
          }

          public Flux<ChatResponse> stream(
              List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            observedInputs.add(
                messages.stream()
                    .map(Msg::getTextContent)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.joining("\n")));
            return Flux.just(
                ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("已完成").build()))
                    .build());
          }
        };
    var provider =
        new AgentModelProvider() {
          public ModelMode mode() {
            return ModelMode.MOCK;
          }

          public Model create(AgentExecutionRequest request) {
            return model;
          }
        };
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:isolation-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var executor =
        new AgentScopeExecutor(
            new ObjectMapper(),
            new JdbcAgentStateStore(source, new H2Dialect(), true),
            new AgentAssemblyPlanner(roles, definitions, skills),
            new AgentModelRegistry(List.of(provider)),
            new AgentToolRegistry(List.of()));
    for (String input : List.of("仅第一实例可见的标记-A", "仅第二实例可见的标记-B")) {
      var request =
          new AgentExecutionRequest(
              AgentRole.REVIEWER,
              UUID.randomUUID().toString(),
              input,
              null,
              List.of(),
              false,
              Scenario.NORMAL,
              ModelMode.MOCK,
              CheckpointStage.BEFORE_TOOL,
              null,
              mock(AgentRuntime.class),
              reference);
      assertEquals("已完成", executor.execute(request));
    }
    assertEquals(2, observedInputs.size());
    assertTrue(observedInputs.get(0).contains("标记-A"));
    assertFalse(observedInputs.get(0).contains("标记-B"));
    assertTrue(observedInputs.get(1).contains("标记-B"));
    assertFalse(observedInputs.get(1).contains("标记-A"));
  }

  @Test
  void rejectsUnregisteredVersionsDuplicateDefinitionsAndPermissionEscalation() {
    var reference = new AgentDefinitionRef("focused-review", 1);
    var candidate =
        new AgentDefinition(reference, "review", "审查证据", AgentRole.REVIEWER, List.of(), List.of());
    var registry = new AgentDefinitionRegistry(roles, skills, List.of(() -> candidate));
    var changedContent =
        new AgentDefinition(
            reference, "review", "不同的审查提示", AgentRole.REVIEWER, List.of(), List.of());
    var changedRegistry = new AgentDefinitionRegistry(roles, skills, List.of(() -> changedContent));
    assertNotEquals(registry.fingerprint(reference), changedRegistry.fingerprint(reference));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.resolve(new AgentDefinitionRef("focused-review", 2)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentDefinitionRegistry(roles, skills, List.of(() -> candidate, () -> candidate)));
    var overprivileged =
        new AgentDefinition(
            new AgentDefinitionRef("unsafe-review", 1),
            "review",
            "审查证据",
            AgentRole.REVIEWER,
            List.of(ToolKind.WORKSPACE_WRITE),
            List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentDefinitionRegistry(roles, skills, List.of(() -> overprivileged)));
    var planner = new AgentAssemblyPlanner(roles, registry, skills);
    assertThrows(
        IllegalArgumentException.class,
        () -> planner.plan(AgentRole.CODER, reference, true, 1, true));
  }

  @Test
  void copiesListsAndAppliesTaskAuthorizationToDeclaredTools() {
    var tools = new ArrayList<>(List.of(ToolKind.WORKSPACE_READ, ToolKind.WORKSPACE_WRITE));
    var candidate =
        new AgentDefinition(
            new AgentDefinitionRef("limited-coder", 1),
            "coding",
            "处理代码",
            AgentRole.CODER,
            tools,
            List.of(SkillId.AUTHORIZED_EDIT));
    tools.clear();
    var registry = new AgentDefinitionRegistry(roles, skills, List.of(() -> candidate));
    var planner = new AgentAssemblyPlanner(roles, registry, skills);
    var plan = planner.plan(AgentRole.CODER, candidate.ref(), true, 1, false);
    assertEquals(java.util.Map.of(ToolKind.WORKSPACE_READ, 1), plan.toolVersions());
    assertThrows(UnsupportedOperationException.class, () -> candidate.tools().clear());
    assertThrows(IllegalArgumentException.class, () -> new AgentDefinitionRef("INVALID", 1));
  }
}
