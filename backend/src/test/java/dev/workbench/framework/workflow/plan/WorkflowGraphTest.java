package dev.workbench.framework.workflow.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证多前置任务图的拓扑和就绪语义，不把就绪误称为已执行。 */
class WorkflowGraphTest {
  @Test
  void forkAndJoinWaitForEveryPrerequisite() {
    var graph =
        new WorkflowGraph(
            List.of(
                new WorkflowGraph.Node("analyze-a", List.of()),
                new WorkflowGraph.Node("analyze-b", List.of()),
                new WorkflowGraph.Node("review", List.of("analyze-a", "analyze-b"))));
    assertEquals(
        List.of("analyze-a", "analyze-b"),
        graph.ready(
            Map.of(
                "analyze-a",
                PlanNodeStatus.PENDING,
                "analyze-b",
                PlanNodeStatus.PENDING,
                "review",
                PlanNodeStatus.PENDING)));
    assertEquals(
        List.of(),
        graph.ready(
            Map.of(
                "analyze-a",
                PlanNodeStatus.COMPLETED,
                "analyze-b",
                PlanNodeStatus.RUNNING,
                "review",
                PlanNodeStatus.PENDING)));
    assertEquals(
        List.of("review"),
        graph.ready(
            Map.of(
                "analyze-a",
                PlanNodeStatus.REUSED,
                "analyze-b",
                PlanNodeStatus.COMPLETED,
                "review",
                PlanNodeStatus.PENDING)));
    assertTrue(
        graph
            .ready(
                Map.of(
                    "analyze-a",
                    PlanNodeStatus.COMPLETED,
                    "analyze-b",
                    PlanNodeStatus.FAILED,
                    "review",
                    PlanNodeStatus.PENDING))
            .isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> graph.ready(Map.of("review", PlanNodeStatus.PENDING)));
  }

  @Test
  void rejectsMissingRepeatedAndCyclicDependencies() {
    var a = new WorkflowGraph.Node("a", List.of());
    assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(List.of(a, a)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowGraph(List.of(new WorkflowGraph.Node("a", List.of("missing")))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowGraph(List.of(a, new WorkflowGraph.Node("b", List.of("a", "a")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkflowGraph(
                List.of(
                    new WorkflowGraph.Node("a", List.of("b")),
                    new WorkflowGraph.Node("b", List.of("a")))));
    assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph.Node("../bad", List.of()));
    assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(List.of()));
  }
}
