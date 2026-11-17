package dev.workbench.framework.workflow.plan;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.domain.AgentRole;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 校验计划的安全边界与当前执行器实际支持的拓扑。 */
class PlanValidatorTest {
  private final PlanValidator validator = new PlanValidator();
  private final PlanFactory factory = new PlanFactory(validator);

  @Test
  void generatedPlansMatchWritePermission() {
    assertEquals(
        List.of(AgentRole.ANALYST, AgentRole.REVIEWER),
        factory.create(false).nodes().stream().map(PlanDefinition.Node::role).toList());
    assertEquals(
        List.of(AgentRole.ANALYST, AgentRole.CODER, AgentRole.REVIEWER),
        factory.create(true).nodes().stream().map(PlanDefinition.Node::role).toList());
  }

  @Test
  void rejectsCyclesMissingDependenciesDuplicatesAndUnauthorizedCoder() {
    var analyst = new PlanDefinition.Node(AgentRole.ANALYST, List.of());
    var coder = new PlanDefinition.Node(AgentRole.CODER, List.of(AgentRole.ANALYST));
    var reviewer = new PlanDefinition.Node(AgentRole.REVIEWER, List.of(AgentRole.CODER));
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validate(new PlanDefinition(List.of(analyst, coder, reviewer)), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validate(new PlanDefinition(List.of(analyst, analyst)), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validate(new PlanDefinition(List.of(analyst, reviewer)), false));
    var cycleA = new PlanDefinition.Node(AgentRole.ANALYST, List.of(AgentRole.REVIEWER));
    var cycleR = new PlanDefinition.Node(AgentRole.REVIEWER, List.of(AgentRole.ANALYST));
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validate(new PlanDefinition(List.of(cycleA, cycleR)), false));
  }

  @Test
  void rejectsAcyclicTopologyNotSupportedByCurrentExecutor() {
    var analyst = new PlanDefinition.Node(AgentRole.ANALYST, List.of());
    var coder = new PlanDefinition.Node(AgentRole.CODER, List.of(AgentRole.ANALYST));
    var independentReviewer =
        new PlanDefinition.Node(AgentRole.REVIEWER, List.of(AgentRole.ANALYST));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validator.validate(
                new PlanDefinition(List.of(analyst, coder, independentReviewer)), true));
  }

  @Test
  void genericWorkflowSeparatesNodeIdentityFromAgentDefinition() {
    var definition = new AgentDefinitionRef("researcher", 1);
    var workflow =
        new WorkflowDefinition(
            List.of(
                new WorkflowDefinition.Node("research_a", definition, List.of()),
                new WorkflowDefinition.Node("research_b", definition, List.of("research_a"))));
    assertEquals(definition, workflow.nodes().getFirst().agent());
    assertEquals(definition, workflow.nodes().getLast().agent());
    assertNotEquals(workflow.nodes().getFirst().id(), workflow.nodes().getLast().id());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkflowDefinition(
                List.of(new WorkflowDefinition.Node("a", definition, List.of("missing")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkflowDefinition(
                List.of(
                    new WorkflowDefinition.Node("a", definition, List.of("b")),
                    new WorkflowDefinition.Node("b", definition, List.of("a")))));
  }
}
