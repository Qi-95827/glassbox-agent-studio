package dev.workbench.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunCountStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import dev.workbench.framework.workflow.check.StaleEvidenceException;
import dev.workbench.persistence.repository.PlanGraphRepository;
import dev.workbench.persistence.repository.RunRepository;
import dev.workbench.persistence.repository.SupplementRepository;
import dev.workbench.planning.PlanService;
import dev.workbench.planning.RecoveryLeaseService;
import dev.workbench.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** 认领后、执行前工作区变化必须使证据失效。 */
class WorkflowWorkspaceGuardTest {
  @Test
  void changedWorkspaceRejectsExecutionBeforeModelOrTool() {
    var workspaces = mock(WorkspaceService.class);
    when(workspaces.verificationFingerprint("ws")).thenReturn("changed");
    var service = service(workspaces);

    var error =
        assertThrows(
            StaleEvidenceException.class,
            () ->
                service.requireWorkspaceCurrent(
                    new WorkflowExecutionService.WorkspaceGuard("ws", "original")));
    assertTrue(error.getMessage().contains("工作区已变化"));
    verify(workspaces).verificationFingerprint("ws");
  }

  @Test
  void unchangedWorkspaceAllowsExecution() {
    var workspaces = mock(WorkspaceService.class);
    when(workspaces.verificationFingerprint("ws")).thenReturn("same");
    service(workspaces)
        .requireWorkspaceCurrent(new WorkflowExecutionService.WorkspaceGuard("ws", "same"));
  }

  private static WorkflowExecutionService service(WorkspaceService workspaces) {
    return new WorkflowExecutionService(
        mock(RunStateStore.class),
        mock(TaskRecordService.class),
        mock(AgentAssemblyRecordService.class),
        mock(RunEvidenceRecordService.class),
        mock(RunCountStore.class),
        mock(dev.workbench.persistence.repository.WorkflowExecutionRepository.class),
        mock(TransactionTemplate.class),
        mock(PlanService.class),
        mock(WorkflowRecoveryService.class),
        workspaces,
        mock(RunRepository.class),
        mock(PlanGraphRepository.class),
        mock(SupplementRepository.class));
  }
}
