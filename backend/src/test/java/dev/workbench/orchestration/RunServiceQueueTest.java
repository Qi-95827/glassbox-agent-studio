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
  void rejectedRecoveryCanBeRequestedAgain() {
    var runs = mock(RunStateStore.class);
    var tasks = mock(TaskRecordService.class);
    var creation = mock(RunCreationStore.class);
    var evidence = mock(RunEvidenceRecordService.class);
    var creations = mock(TaskCreationService.class);
    var agent = mock(AgentScopeExecutor.class);
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
            mock(WorkflowScheduler.class),
            java.util.concurrent.Executors.newSingleThreadExecutor());
    var runId = java.util.UUID.randomUUID();
    var taskId = java.util.UUID.randomUUID();
    var checkpointId = java.util.UUID.randomUUID();
    var original =
        new dev.workbench.domain.RunSnapshot(
            runId,
            taskId,
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
    var task =
        new dev.workbench.domain.TaskDefinition(
            taskId,
            "任务",
            dev.workbench.domain.Scenario.NORMAL,
            dev.workbench.domain.ModelMode.MOCK,
            null,
            null,
            false,
            null,
            false,
            java.util.List.of(),
            java.util.List.of(),
            dev.workbench.domain.TaskEntryKind.GENERAL);
    var checkpoint =
        new dev.workbench.domain.CheckpointSnapshot(
            checkpointId, runId, dev.workbench.domain.CheckpointStage.BEFORE_TOOL, 0, 0, 0, null);
    when(runs.runSnapshot(runId.toString())).thenReturn(original);
    when(tasks.taskDefinition(taskId.toString())).thenReturn(task);
    when(evidence.checkedCheckpoint(checkpointId.toString())).thenReturn(checkpoint);
    service.shutdown();
    String first = service.recover(runId.toString(), checkpointId.toString());
    String second = service.recover(runId.toString(), checkpointId.toString());
    assertNotEquals(first, second);
    verify(creation, times(2)).createRecoveryRun(any(), eq(original), eq(checkpoint));
    verify(creation).rejectRecoveryBeforeDispatch(first);
    verify(creation).rejectRecoveryBeforeDispatch(second);
    verifyNoInteractions(agent);
  }
}
