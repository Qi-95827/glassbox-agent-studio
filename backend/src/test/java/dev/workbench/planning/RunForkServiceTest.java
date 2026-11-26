package dev.workbench.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.workbench.domain.ModelMode;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.workflow.plan.PlanningRunReader;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.persistence.repository.RunForkRepository;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 分叉的前置条件：只从已结束运行分叉，只复制存在的节点，授权变化即拒绝，且不改写源运行。 */
class RunForkServiceTest {
  private final PlanningRunReader records = mock(PlanningRunReader.class);
  private final PlanService plans = mock(PlanService.class);
  private final WorkspaceAuthorizationService authorization =
      mock(WorkspaceAuthorizationService.class);
  private final CandidateRunPersister creations = mock(CandidateRunPersister.class);
  private final CandidateRunDispatcher runs = mock(CandidateRunDispatcher.class);
  private final ModelConnectionService connections = mock(ModelConnectionService.class);
  private final RunEvidenceRecordService evidence = mock(RunEvidenceRecordService.class);
  private final RunForkRepository forks = mock(RunForkRepository.class);

  private RunForkService service;
  private final UUID sourceId = UUID.randomUUID();
  private final UUID taskId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new RunForkService(
            records, plans, authorization, creations, runs, connections, evidence, forks);
    when(records.taskDefinition(taskId.toString())).thenReturn(task());
    when(plans.find(sourceId.toString())).thenReturn(List.of());
    when(authorization.prepare(anyString(), any(), any(), any()))
        .thenReturn(
            new WorkspaceAuthorizationService.Prepared(
                List.of("sort.js"), List.of(), List.of(), List.of("sort.js")));
  }

  @Test
  void refusesToForkARunThatHasNotFinished() {
    when(records.runSnapshot(sourceId.toString())).thenReturn(snapshot(RunStatus.RUNNING));
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> service.fork(sourceId.toString(), new RunForkService.Request(null, null, null, null, null)));
    assertTrue(error.getMessage().contains("只有已结束的运行才能分叉"));
    verify(creations, never()).persistFromCandidate(any(), any(), any(), anyString(), any(), any(), any());
  }

  @Test
  void refusesANodeKeyThatTheSourcePlanDoesNotContain() {
    when(records.runSnapshot(sourceId.toString())).thenReturn(snapshot(RunStatus.COMPLETED));
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.fork(
                    sourceId.toString(),
                    new RunForkService.Request("ghost", null, null, null, null)));
    assertTrue(error.getMessage().contains("源运行没有节点 ghost"));
  }

  @Test
  void refusesWhenTheSourcePlanHasNoCopyableNodes() {
    when(records.runSnapshot(sourceId.toString())).thenReturn(snapshot(RunStatus.COMPLETED));
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> service.fork(sourceId.toString(), new RunForkService.Request(null, null, null, null, null)));
    assertTrue(error.getMessage().contains("没有可复制的计划节点"));
    verify(creations, never()).persistFromCandidate(any(), any(), any(), anyString(), any(), any(), any());
  }

  @Test
  void refusesWhenWorkspaceAuthorizationNoLongerCoversTheFrozenScope() {
    when(records.runSnapshot(sourceId.toString())).thenReturn(snapshot(RunStatus.COMPLETED));
    when(authorization.prepare(anyString(), any(), any(), any()))
        .thenReturn(
            new WorkspaceAuthorizationService.Prepared(
                List.of(), List.of(), List.of(), List.of("other.js")));
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> service.fork(sourceId.toString(), new RunForkService.Request(null, null, null, null, null)));
    assertTrue(error.getMessage().contains("工作区授权已变化"));
    verify(creations, never()).persistFromCandidate(any(), any(), any(), anyString(), any(), any(), any());
  }

  @Test
  void ordinaryUserMayForkWithTheirOwnByokConnection() {
    when(records.runSnapshot(sourceId.toString())).thenReturn(snapshot(RunStatus.COMPLETED));
    when(connections.requireEnabledOwned("conn-1")).thenReturn("conn-1");
    // 走到计划复制这一步就说明模型校验已通过；没有计划是另一条独立拒绝原因。
    var error =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.fork(
                    sourceId.toString(),
                    new RunForkService.Request(null, null, null, "user", "conn-1")));
    assertTrue(error.getMessage().contains("没有可复制的计划节点"));
    verify(connections).requireEnabledOwned("conn-1");
    // 自带 Key 不得触发宿主机全局密钥特权校验。
    verify(connections, never()).requireLegacyKeyAccess();
  }

  @Test
  void deepseekModeRequiresTheLegacyKeyPrivilegeAndIsRejectedWithoutIt() {
    when(records.runSnapshot(sourceId.toString())).thenReturn(snapshot(RunStatus.COMPLETED));
    org.mockito.Mockito.doThrow(
            new dev.workbench.auth.AuthService.AccessDeniedException("当前账号没有宿主机 DeepSeek 密钥特权。"))
        .when(connections)
        .requireLegacyKeyAccess();
    var error =
        assertThrows(
            dev.workbench.auth.AuthService.AccessDeniedException.class,
            () ->
                service.fork(
                    sourceId.toString(),
                    new RunForkService.Request(null, null, null, "deepseek", null)));
    assertTrue(error.getMessage().contains("特权"));
    verify(creations, never()).persistFromCandidate(any(), any(), any(), anyString(), any(), any(), any());
  }

  @Test
  void expandedWholeWorkspaceScopeIsVerifiedThroughDirectoryScopeNotTheManualLimit() {
    when(records.runSnapshot(sourceId.toString())).thenReturn(snapshot(RunStatus.COMPLETED));
    when(records.taskDefinition(taskId.toString())).thenReturn(wideTask(25));
    when(authorization.reverifyDirectories(anyString(), any(), any(), any()))
        .thenThrow(new IllegalStateException("整仓范围已变化"));
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> service.fork(sourceId.toString(), new RunForkService.Request(null, null, null, null, null)));
    assertTrue(error.getMessage().contains("整仓范围已变化"));
    // 超过手工上限的范围必须走目录复验，而不是被 20 个文件的显式上限误杀。
    verify(authorization).reverifyDirectories(eq("ws-1"), eq(List.of(".")), any(), any());
    verify(authorization, never()).prepare(anyString(), any(), any(), any());
  }

  private RunSnapshot snapshot(RunStatus status) {
    return new RunSnapshot(
        sourceId, taskId, null, status, 0, 0, 12, 12, 6, null, 3, 240, 0L, 10, 10);
  }

  private TaskDefinition wideTask(int files) {
    var scopes = new java.util.ArrayList<String>();
    for (int index = 1; index <= files; index += 1) scopes.add("src/file-" + index + ".js");
    return new TaskDefinition(
        taskId,
        "把整仓文档补齐",
        Scenario.NORMAL,
        ModelMode.MOCK,
        "ws-1",
        scopes.getFirst(),
        true,
        null,
        false,
        List.of(),
        List.of("整仓可编译"),
        TaskEntryKind.CODE,
        List.copyOf(scopes),
        Map.of(),
        Map.of(),
        null,
        "zh-CN");
  }

  private TaskDefinition task() {
    return new TaskDefinition(
        taskId,
        "把排序网页做出来",
        Scenario.NORMAL,
        ModelMode.MOCK,
        "ws-1",
        "sort.js",
        true,
        null,
        false,
        List.of("不要引入新依赖"),
        List.of("页面能排序"),
        TaskEntryKind.CODE,
        List.of("sort.js"),
        Map.of(),
        Map.of(),
        null,
        "zh-CN");
  }
}
