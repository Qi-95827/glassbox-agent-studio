package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.workbench.control.RunControl;
import dev.workbench.control.StopExecution;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.ToolKind;
import dev.workbench.task.RunRepository;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 单工具零额度在调用记录创建及文件读取前生效。 */
class WorkspaceSearchBudgetTest {
  @Test
  void zeroSearchBudgetPreventsStartingSearch() {
    UUID runId = UUID.randomUUID();
    var snapshot =
        new RunSnapshot(
            runId,
            UUID.randomUUID(),
            null,
            RunStatus.RUNNING,
            0,
            0,
            5,
            5,
            5,
            null,
            null,
            30,
            0,
            null,
            null);
    var repository = mock(RunRepository.class);
    var control =
        new RunControl(
            runId.toString(),
            snapshot,
            repository,
            new Object(),
            Set.of(),
            Set.of(),
            Map.of(),
            Map.of(ToolKind.WORKSPACE_SEARCH, 0));
    var stopped =
        assertThrows(
            StopExecution.class,
            () -> control.beginTool("agent", ToolKind.WORKSPACE_SEARCH, 1, "fingerprint"));
    assertEquals(StopCause.SPECIFIC_TOOL_LIMIT, stopped.stopCause());
    assertEquals(0, control.tools());
    verify(repository, never())
        .startTool(anyString(), anyString(), anyString(), anyString(), anyInt());
  }
}
