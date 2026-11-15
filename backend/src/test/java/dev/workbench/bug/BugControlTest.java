package dev.workbench.bug;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.workbench.workspace.ApprovalService;
import dev.workbench.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 容器启动之前必须执行运行控制检查。 */
class BugControlTest {
  @Test
  void cancelledRunDoesNotOpenWorkspaceOrStartTest() {
    var workspaces = mock(WorkspaceService.class);
    var database = mock(JdbcTemplate.class);
    var service = new BugService(workspaces, database, mock(ApprovalService.class));
    var stopped = new IllegalStateException("已停止");
    assertSame(
        stopped,
        assertThrows(
            IllegalStateException.class,
            () ->
                service.run(
                    "MOCK-PY-001",
                    "unused",
                    BugService.CheckKind.REPRODUCE,
                    () -> {
                      throw stopped;
                    })));
    verifyNoInteractions(workspaces, database);
  }
}
