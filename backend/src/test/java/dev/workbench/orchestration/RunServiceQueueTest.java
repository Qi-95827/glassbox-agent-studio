package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.bug.BugService;
import dev.workbench.domain.RunStatus;
import dev.workbench.framework.context.AgentContextAssembler;
import dev.workbench.infrastructure.agentscope.AgentScopeExecutor;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunCheckpointStore;
import dev.workbench.store.RunCountStore;
import dev.workbench.store.RunCreationStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import dev.workbench.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.planning.EntryPolicyService;

/** 队列明确拒绝时应保留可查看的失败运行，不声称启动过 Agent。 */
class RunServiceQueueTest {
  @Test
  void rejectedQueueLeavesVisibleFailedRun() {
    var runs = mock(RunStateStore.class);
    var creations = mock(TaskCreationService.class);
    var agent = mock(AgentScopeExecutor.class);
    var service =
        new RunService(
            runs,
            mock(TaskRecordService.class),
            mock(RunCreationStore.class),
            mock(AgentAssemblyRecordService.class),
            mock(RunEvidenceRecordService.class),
            mock(RunCheckpointStore.class),
            mock(RunCountStore.class),
            mock(dev.workbench.framework.control.RunControlStore.class),
            creations,
            agent,
            new ObjectMapper(),
            mock(WorkspaceService.class),
            mock(BugService.class),
            mock(dev.workbench.bug.BugIntakeService.class),
            mock(dev.workbench.workspace.RunHandoverService.class),
            mock(dev.workbench.planning.EntryPolicyService.class),
            mock(AgentContextAssembler.class),
            mock(WorkflowScheduler.class),
            java.util.concurrent.Executors.newSingleThreadExecutor());
    service.shutdown();
    String runId = service.create(new CreateTaskRequest("任务", "normal", 4, 2, 30));
    verify(creations).persist(any(), any(), any(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    verify(runs)
        .finishStoppedRun(
            argThat(
                stop ->
                    stop.runId().toString().equals(runId)
                        && stop.agentId() == null
                        && stop.cause() == dev.workbench.domain.StopCause.UNCLASSIFIED_FAILURE
                        && stop.reason().contains("尚未启动 Agent")));
    verifyNoInteractions(agent);
  }

  @Test
  void recoveryOfLegacyRunWithoutPlanIsRejectedAndCreatesNothing() {
    var runs = mock(RunStateStore.class);
    var tasks = mock(TaskRecordService.class);
    var creation = mock(RunCreationStore.class);
    var evidence = mock(RunEvidenceRecordService.class);
    var creations = mock(TaskCreationService.class);
    var agent = mock(AgentScopeExecutor.class);
    var workflows = mock(WorkflowScheduler.class);
    var service =
        new RunService(
            runs,
            tasks,
            creation,
            mock(AgentAssemblyRecordService.class),
            evidence,
            mock(RunCheckpointStore.class),
            mock(RunCountStore.class),
            mock(dev.workbench.framework.control.RunControlStore.class),
            creations,
            agent,
            new ObjectMapper(),
            mock(WorkspaceService.class),
            mock(BugService.class),
            mock(dev.workbench.bug.BugIntakeService.class),
            mock(dev.workbench.workspace.RunHandoverService.class),
            mock(dev.workbench.planning.EntryPolicyService.class),
            mock(AgentContextAssembler.class),
            workflows,
            java.util.concurrent.Executors.newSingleThreadExecutor());
    var runId = java.util.UUID.randomUUID();
    var checkpointId = java.util.UUID.randomUUID();
    var original =
        new dev.workbench.domain.RunSnapshot(
            runId,
            java.util.UUID.randomUUID(),
            null,
            RunStatus.INTERRUPTED,
            0,
            0,
            4,
            2,
            2,
            null,
            null,
            30,
            0,
            null,
            null);
    when(runs.runSnapshot(runId.toString())).thenReturn(original);
    // 收口包 G-2：遗留直跑轨已下线，无工作流计划的历史中断记录不再支持恢复。
    when(workflows.plan(runId.toString())).thenReturn(null);
    service.shutdown();
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> service.recover(runId.toString(), checkpointId.toString()));
    assertTrue(error.getMessage().contains("遗留"), error.getMessage());
    assertTrue(error.getMessage().contains("重新创建任务"), error.getMessage());
    verify(creation, never()).createRecoveryRun(any(), any(), any());
    verifyNoInteractions(agent);
  }

  @Test
  void recoveryRejectsRunThatIsNotInterrupted() {
    var runs = mock(RunStateStore.class);
    var service =
        new RunService(
            runs,
            mock(TaskRecordService.class),
            mock(RunCreationStore.class),
            mock(AgentAssemblyRecordService.class),
            mock(RunEvidenceRecordService.class),
            mock(RunCheckpointStore.class),
            mock(RunCountStore.class),
            mock(dev.workbench.framework.control.RunControlStore.class),
            mock(TaskCreationService.class),
            mock(AgentScopeExecutor.class),
            new ObjectMapper(),
            mock(WorkspaceService.class),
            mock(BugService.class),
            mock(dev.workbench.bug.BugIntakeService.class),
            mock(dev.workbench.workspace.RunHandoverService.class),
            mock(dev.workbench.planning.EntryPolicyService.class),
            mock(AgentContextAssembler.class),
            mock(WorkflowScheduler.class),
            java.util.concurrent.Executors.newSingleThreadExecutor());
    var runId = java.util.UUID.randomUUID();
    when(runs.runSnapshot(runId.toString()))
        .thenReturn(
            new dev.workbench.domain.RunSnapshot(
                runId,
                java.util.UUID.randomUUID(),
                null,
                RunStatus.COMPLETED,
                0,
                0,
                4,
                2,
                2,
                null,
                null,
                30,
                0,
                null,
                null));
    service.shutdown();
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> service.recover(runId.toString(), java.util.UUID.randomUUID().toString()));
    assertTrue(error.getMessage().contains("只有中断的运行可以恢复"), error.getMessage());
  }
}
