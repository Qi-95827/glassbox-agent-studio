package dev.workbench.orchestration.plan;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.domain.AgentRole;
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
}
