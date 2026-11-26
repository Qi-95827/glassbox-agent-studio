package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.workbench.auth.AuthService;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.context.AgentContextInput;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.persistence.entity.RunIterationEntity;
import dev.workbench.persistence.repository.RunData;
import dev.workbench.persistence.repository.RunIterationRepository;
import dev.workbench.persistence.repository.RunRepository;
import dev.workbench.persistence.repository.TaskRepository;
import dev.workbench.planning.CandidatePlanCopyService;
import dev.workbench.planning.CandidateRunDispatcher;
import dev.workbench.planning.PlanService;
import dev.workbench.store.RunCreationStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunIterationRecordService;
import dev.workbench.store.TaskRecordService;
import dev.workbench.task.CostService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 同任务迭代的拒绝边界与上下文注入。
 *
 * <p>这里只验证不需要真实数据库的判定顺序：源运行必须已结束、调改意见必填限长、宿主机全局密钥只有管理员可用， 以及「没有迭代记录的运行」不会被注入任何额外要求。
 */
class RunIterationContextTest {
  private final TaskRecordService tasks = mock(TaskRecordService.class);
  private final RunRepository runRows = mock(RunRepository.class);
  private final TaskRepository taskRows = mock(TaskRepository.class);
  private final PlanService plans = mock(PlanService.class);
  private final CandidatePlanCopyService planCopies = mock(CandidatePlanCopyService.class);
  private final WorkspaceAuthorizationService authorization =
      mock(WorkspaceAuthorizationService.class);
  private final RunCreationStore runCreation = mock(RunCreationStore.class);
  private final CandidateRunDispatcher dispatcher = mock(CandidateRunDispatcher.class);
  private final ModelConnectionService connections = mock(ModelConnectionService.class);
  private final RunEvidenceRecordService evidence = mock(RunEvidenceRecordService.class);
  private final RunIterationRepository iterationRows = mock(RunIterationRepository.class);
  private final CostService costs = mock(CostService.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);

  private RunIterationService service;
  private final String runId = UUID.randomUUID().toString();
  private final String taskId = UUID.randomUUID().toString();

  @BeforeEach
  void setUp() {
    service =
        new RunIterationService(
            tasks,
            runRows,
            taskRows,
            plans,
            planCopies,
            authorization,
            runCreation,
            dispatcher,
            connections,
            evidence,
            new RunIterationRecordService(iterationRows),
            costs,
            tx);
    when(runRows.findRunById(runId)).thenReturn(Optional.of(run("COMPLETED")));
    when(tasks.taskDefinition(taskId)).thenReturn(task(null));
   when(plans.find(runId)).thenReturn(List.of());
  }

  @Test
  void refusesARunThatHasNotFinishedWithoutCreatingAnything() {
    when(runRows.findRunById(runId)).thenReturn(Optional.of(run("RUNNING")));
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> service.iterate(runId, new RunIterationService.Request("补充说明", null, null, null)));
    assertTrue(error.getMessage().contains("只有已结束的运行才能发起调改迭代"));
    verify(runCreation, never()).createIterationRunFromPlan(any(), any(), any(), any(), any());
    verify(dispatcher, never()).dispatchConfirmed(anyString());
  }

  @Test
  void requiresNonBlankFeedbackAndEnforcesTheLengthLimit() {
    var blank =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.iterate(runId, new RunIterationService.Request("   ", null, null, null)));
    assertTrue(blank.getMessage().contains("请填写调改意见"));

    String tooLong = "改".repeat(2001);
    var longError =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.iterate(runId, new RunIterationService.Request(tooLong, null, null, null)));
    assertTrue(longError.getMessage().contains("最多 2000 字"));
    verify(dispatcher, never()).dispatchConfirmed(anyString());
  }

  @Test
  void masksSecretsBeforeTheyReachThePersistedIterationRecord() {
    when(iterationRows.findByTaskIdOrderByRoundIndexAsc(taskId)).thenReturn(List.of());
    when(taskRows.lockForUpdate(taskId)).thenReturn(true);
    when(runRows.findByTaskId(taskId)).thenReturn(List.of());
    var realTx = new TransactionTemplate(transactionManager());
    var realService =
        new RunIterationService(
            tasks,
            runRows,
            taskRows,
            plans,
            planCopies,
            authorization,
            runCreation,
            dispatcher,
            connections,
            evidence,
            new RunIterationRecordService(iterationRows),
            costs,
            realTx);

    var result =
        realService.iterate(
            runId,
            new RunIterationService.Request("请把 key=sk-abcdefghijklmnopq 换成环境变量", null, null, null));

    assertEquals(2, result.roundIndex());
    assertEquals(runId, result.parentRunId());
    var saved = org.mockito.ArgumentCaptor.forClass(RunIterationEntity.class);
    verify(iterationRows).save(saved.capture());
    assertFalse(saved.getValue().getFeedback().contains("sk-abcdefghijklmnopq"), "凭据必须脱敏");
    assertTrue(saved.getValue().getFeedback().contains("换成环境变量"));
    verify(dispatcher).dispatchConfirmed(result.runId());
  }

  @Test
  void ordinaryUserCannotBorrowTheHostDeepSeekKey() {
    org.mockito.Mockito.doThrow(new AuthService.AccessDeniedException("宿主机全局 DeepSeek 密钥仅限管理员使用。"))
        .when(connections)
        .requireLegacyKeyAccess();
    var error =
        assertThrows(
            AuthService.AccessDeniedException.class,
            () ->
                service.iterate(
                    runId,
                    new RunIterationService.Request("用宿主机密钥再跑一轮", "deepseek", null, null)));
    assertTrue(error.getMessage().contains("仅限管理员"));
    verify(runCreation, never()).createIterationRunFromLegacyPlan(any(), any(), any(), any(), any(Boolean.class));
  }

  @Test
  void iterationContributorOnlyActsOnRunsThatWereStartedByAnIteration() {
    var row =
        new RunIterationEntity(
            runId,
            taskId,
            "parent-run",
            2,
            "code",
            "mock",
            null,
            "补充回滚步骤",
            "上一轮交付了检查清单",
            "2026-01-01T00:00:00Z");
    when(iterationRows.findByRunId(runId)).thenReturn(Optional.of(row));
    var contributor =
        new BuiltInContextContributors()
            .iterationContext(new RunIterationRecordService(iterationRows));
    var section = contributor.contribute(new AgentContextInput(task(null), runId, null));
    assertTrue(section.text().contains("第 2 轮"));
    assertTrue(section.text().contains("补充回滚步骤"));
    assertTrue(section.text().contains("上一轮交付了检查清单"));
    assertTrue(section.text().contains("code"));

    when(iterationRows.findByRunId("first-round")).thenReturn(Optional.empty());
    var empty =
        contributor.contribute(new AgentContextInput(task(null), "first-round", null));
    assertEquals("", empty.text(), "首轮运行不得被注入任何额外要求");
  }

  @Test
  void effectiveTaskAppliesIterationModelOverrideWithoutMutatingBaseTask() {
    var row =
        new RunIterationEntity(
            runId,
            taskId,
            "parent-run",
            2,
            null,
            "user",
            "conn-byok-2",
            "改用自带模型复核",
            null,
            "2026-01-01T00:00:00Z");
    when(iterationRows.findByRunId(runId)).thenReturn(Optional.of(row));
    var recordService = new RunIterationRecordService(iterationRows);
    TaskDefinition base = task(null);
    TaskDefinition effective = WorkflowScheduler.effectiveTask(runId, base, recordService);
    assertEquals(ModelMode.USER, effective.modelMode());
    assertEquals("conn-byok-2", effective.modelConnectionId());
    assertEquals(ModelMode.MOCK, base.modelMode(), "原始任务定义必须保持不可变");
    assertNull(base.modelConnectionId());

    when(iterationRows.findByRunId("first-round")).thenReturn(Optional.empty());
    assertSame(base, WorkflowScheduler.effectiveTask("first-round", base, recordService));
  }

  @Test
  void refusesIterationWhileAnotherRunInSameTaskIsCancelling() {
    when(iterationRows.findByTaskIdOrderByRoundIndexAsc(taskId)).thenReturn(List.of());
    when(taskRows.lockForUpdate(taskId)).thenReturn(true);
    when(runRows.findByTaskId(taskId)).thenReturn(List.of(run("CANCELLING")));
    var realTx = new TransactionTemplate(transactionManager());
    var realService =
        new RunIterationService(
            tasks,
            runRows,
            taskRows,
            plans,
            planCopies,
            authorization,
            runCreation,
            dispatcher,
            connections,
            evidence,
            new RunIterationRecordService(iterationRows),
            costs,
            realTx);
    var error =
        assertThrows(
            IllegalStateException.class,
            () ->
                realService.iterate(
                    runId, new RunIterationService.Request("补充说明", null, null, null)));
    assertTrue(error.getMessage().contains("已有进行中的运行"));
    verify(dispatcher, never()).dispatchConfirmed(anyString());
  }

  private org.springframework.transaction.PlatformTransactionManager transactionManager() {
    var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
    when(manager.getTransaction(any()))
        .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
    return manager;
  }

  private RunData run(String status) {
    return new RunData(
        runId,
        taskId,
        null,
        status,
        null,
        "上一轮结论",
        0,
        0,
        12,
        12,
        240,
        0L,
        "2026-01-01T00:00:00Z",
        "2026-01-01T00:00:00Z",
        6,
        null,
        null,
        null,
        null,
        "UNKNOWN",
        null);
  }

  private TaskDefinition task(String workspaceId) {
    return new TaskDefinition(
        UUID.fromString(taskId),
        "输出一份发布检查清单",
        Scenario.NORMAL,
        ModelMode.MOCK,
        workspaceId,
        null,
        false,
        null,
        false,
        List.of(),
        List.of("包含回滚步骤"),
        TaskEntryKind.GENERAL,
        List.of(),
        Map.of(),
        Map.of(),
        null,
        "zh-CN");
  }
}
