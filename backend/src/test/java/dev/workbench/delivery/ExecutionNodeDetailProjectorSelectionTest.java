package dev.workbench.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.workbench.domain.InvocationStatus;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.workflow.plan.WorkflowAttempt;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 装配证据选取必须与前端一致：最近一次「拥有装配快照」的尝试，而不是最大 attemptNo。
 *
 * <p>回归：第 2 次尝试没有快照、第 1 次有时，旧实现取第 2 次后查不到快照，蓝图直接空白；
 * 前端却显示第 1 次，形成前后端口径分叉。
 */
class ExecutionNodeDetailProjectorSelectionTest {
  @Test
  void picksTheLatestAttemptThatActuallyHasAnAssemblySnapshot() {
    var firstAgent = UUID.randomUUID();
    var secondAgent = UUID.randomUUID();
    var first =
        new WorkflowAttempt(
            UUID.randomUUID(), UUID.randomUUID(), 1, firstAgent, InvocationStatus.COMPLETED,
            null, null, null);
    var second =
        new WorkflowAttempt(
            UUID.randomUUID(), UUID.randomUUID(), 2, secondAgent, InvocationStatus.COMPLETED,
            null, null, null);
    var recorded = mock(AgentAssemblySnapshot.Recorded.class);
    when(recorded.agentId()).thenReturn(firstAgent);

    var selected =
        ExecutionNodeDetailProjector.latestAttemptWithAssembly(
            List.of(first, second), List.of(recorded));

    assertEquals(1, selected.number(), "只有第 1 次有快照，必须选第 1 次");
    assertEquals(firstAgent, selected.agentId());
  }

  @Test
  void returnsNullWhenNoAttemptHasASnapshot() {
    var attempt =
        new WorkflowAttempt(
            UUID.randomUUID(), UUID.randomUUID(), 3, UUID.randomUUID(),
            InvocationStatus.COMPLETED, null, null, null);
    assertNull(ExecutionNodeDetailProjector.latestAttemptWithAssembly(List.of(attempt), List.of()));
  }
}
