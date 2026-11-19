package dev.workbench.bug;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.workbench.persistence.repository.BugCheckCaseRepository;
import dev.workbench.persistence.repository.BugCheckRepository;
import dev.workbench.persistence.repository.BugFeedbackRepository;
import dev.workbench.workspace.ApprovalService;
import dev.workbench.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** 容器启动之前必须执行运行控制检查。 */
class BugControlTest {
  @Test
  void cancelledRunDoesNotOpenWorkspaceOrStartTest() {
    var workspaces = mock(WorkspaceService.class);
    var bugChecks = mock(BugCheckRepository.class);
    var bugCases = mock(BugCheckCaseRepository.class);
    var feedbacks = mock(BugFeedbackRepository.class);
    var approvals = mock(ApprovalService.class);
    var tx = mock(TransactionTemplate.class);
    var service = new BugService(workspaces, bugChecks, bugCases, feedbacks, approvals, tx);
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
    verifyNoInteractions(workspaces, bugChecks, bugCases, feedbacks, approvals, tx);
  }
}
