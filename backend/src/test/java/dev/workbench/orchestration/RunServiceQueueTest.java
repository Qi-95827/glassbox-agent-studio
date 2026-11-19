package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.bug.BugService;
import dev.workbench.domain.RunStatus;
import dev.workbench.framework.context.AgentContextAssembler;
import dev.workbench.infrastructure.agentscope.AgentScopeExecutor;
import dev.workbench.task.RunRecordService;
import dev.workbench.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;

/** 队列明确拒绝时应保留可查看的失败运行，不声称启动过 Agent。 */
class RunServiceQueueTest {
  @Test
  void rejectedQueueLeavesVisibleFailedRun() {
    var repository = mock(RunRecordService.class);
    var creations = mock(TaskCreationService.class);
    var agent = mock(AgentScopeExecutor.class);
    var service =
        new RunService(
            repository,
            creations,
            agent,
            new ObjectMapper(),
            mock(WorkspaceService.class),
            mock(BugService.class),
            mock(dev.workbench.bug.BugIntakeService.class),
            mock(dev.workbench.workspace.RunHandoverService.class),
            mock(dev.workbench.domain.EntryPolicyService.class),
            mock(AgentContextAssembler.class),
            mock(WorkflowScheduler.class));
    service.shutdown();
    String runId = service.create(new CreateTaskRequest("任务", "normal", 4, 2, 30));
    verify(creations).persist(any(), any(), any(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    verify(repository)
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
    var repository = mock(RunRecordService.class);
    var creations = mock(TaskCreationService.class);
    var agent = mock(AgentScopeExecutor.class);
    var service =
        new RunService(
            repository,
            creations,
            agent,
            new ObjectMapper(),
            mock(WorkspaceService.class),
            mock(BugService.class),
            mock(dev.workbench.bug.BugIntakeService.class),
            mock(dev.workbench.workspace.RunHandoverService.class),
            mock(dev.workbench.domain.EntryPolicyService.class),
            mock(AgentContextAssembler.class),
            mock(WorkflowScheduler.class));
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
    when(repository.runSnapshot(runId.toString())).thenReturn(original);
    when(repository.taskDefinition(taskId.toString())).thenReturn(task);
    when(repository.checkedCheckpoint(checkpointId.toString())).thenReturn(checkpoint);
    service.shutdown();
    String first = service.recover(runId.toString(), checkpointId.toString());
    String second = service.recover(runId.toString(), checkpointId.toString());
    assertNotEquals(first, second);
    verify(repository, times(2)).createRecoveryRun(any(), eq(original), eq(checkpoint));
    verify(repository).rejectRecoveryBeforeDispatch(first);
    verify(repository).rejectRecoveryBeforeDispatch(second);
    verifyNoInteractions(agent);
  }
}
