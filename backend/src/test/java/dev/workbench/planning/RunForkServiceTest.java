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
  private final dev.workbench.planning.CostLimitPort costs =
      mock(dev.workbench.planning.CostLimitPort.class);

  private RunForkService service;
  private final UUID sourceId = UUID.randomUUID();
  private final UUID taskId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new RunForkService(
            records, plans, authorization, creations, runs, connections, evidence, forks, costs);
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

  @Test
  void forkInheritsMemoryBudgetsAndPlatformCostLimit() {
    when(records.runSnapshot(sourceId.toString())).thenReturn(snapshot(RunStatus.COMPLETED));
    when(records.taskDefinition(taskId.toString())).thenReturn(richTask(ModelMode.MOCK, true));
    var node = planNode();
    when(plans.find(sourceId.toString())).thenReturn(List.of(node));
    when(plans.specsByNode(sourceId.toString())).thenReturn(Map.of(node.id(), spec(node.id())));
    when(plans.revisionEdges(sourceId.toString())).thenReturn(List.of());

    var forked =
        service.fork(sourceId.toString(), new RunForkService.Request(null, null, null, null, null));

    var captor = org.mockito.ArgumentCaptor.forClass(TaskDefinition.class);
    verify(creations)
        .persistFromCandidate(captor.capture(), any(), any(), anyString(), any(), any(), any());
    var forkTask = captor.getValue();
    assertTrue(forkTask.memoryEnabled(), "记忆开关必须继承，不能被默认关掉");
    assertEquals(richTask(ModelMode.MOCK, true).roleBudgets(), forkTask.roleBudgets(), "角色预算不得被清空");
    assertEquals(richTask(ModelMode.MOCK, true).toolBudgets(), forkTask.toolBudgets(), "工具预算不得被清空");
    verify(costs).inheritLimit(sourceId.toString(), forked.runId());
  }

  @Test
  void byokForkInheritsSourceConnectionAndDoesNotInheritPlatformCostLimit() {
    when(records.runSnapshot(sourceId.toString())).thenReturn(snapshot(RunStatus.COMPLETED));
    when(records.taskDefinition(taskId.toString())).thenReturn(richTask(ModelMode.USER, true));
    var node = planNode();
    when(plans.find(sourceId.toString())).thenReturn(List.of(node));
    when(plans.specsByNode(sourceId.toString())).thenReturn(Map.of(node.id(), spec(node.id())));
    when(plans.revisionEdges(sourceId.toString())).thenReturn(List.of());

    service.fork(sourceId.toString(), new RunForkService.Request(null, null, null, null, null));
    var captor = org.mockito.ArgumentCaptor.forClass(TaskDefinition.class);
    verify(creations)
        .persistFromCandidate(captor.capture(), any(), any(), anyString(), any(), any(), any());
    // 未显式覆盖时必须沿用源连接，而不是悄悄换成当前默认连接。
    assertEquals("conn-src", captor.getValue().modelConnectionId());
    // BYOK 走用户自己的额度，不继承平台费用上限。
    verify(costs, never()).inheritLimit(anyString(), anyString());

    // 用户显式覆盖：只改变连接这一个变量。
    when(connections.requireEnabledOwned("conn-new")).thenReturn("conn-new");
    service.fork(
        sourceId.toString(), new RunForkService.Request(null, null, null, "user", "conn-new"));
    var overrideCaptor = org.mockito.ArgumentCaptor.forClass(TaskDefinition.class);
    verify(creations, org.mockito.Mockito.times(2))
        .persistFromCandidate(
            overrideCaptor.capture(), any(), any(), anyString(), any(), any(), any());
    var overrideTask = overrideCaptor.getAllValues().get(1);
    assertEquals("conn-new", overrideTask.modelConnectionId());
    assertEquals(captor.getValue().roleBudgets(), overrideTask.roleBudgets());
    verify(costs, never()).inheritLimit(anyString(), anyString());
  }

  private TaskDefinition richTask(ModelMode mode, boolean withBudgets) {
    return new TaskDefinition(
        taskId,
        "把排序网页做出来",
        Scenario.NORMAL,
        mode,
        "ws-1",
        "sort.js",
        true,
        null,
        true,
        List.of("不要引入新依赖"),
        List.of("页面能排序"),
        TaskEntryKind.CODE,
        List.of("sort.js"),
        withBudgets
            ? Map.of(dev.workbench.domain.AgentRole.CODER, new dev.workbench.domain.RoleBudget(4, 2))
            : Map.of(),
        withBudgets ? Map.of(dev.workbench.domain.ToolKind.WORKSPACE_WRITE, 2) : Map.of(),
        mode == ModelMode.USER ? "conn-src" : null,
        "zh-CN");
  }

  private static dev.workbench.framework.workflow.plan.PlanNode planNode() {
    return new dev.workbench.framework.workflow.plan.PlanNode(
        UUID.randomUUID(),
        UUID.randomUUID(),
        0,
        dev.workbench.domain.AgentRole.CODER,
        null,
        dev.workbench.framework.workflow.plan.PlanNodeStatus.PENDING,
        null,
        null,
        null,
        java.time.Instant.parse("2026-01-01T00:00:00Z"),
        List.of(),
        new dev.workbench.framework.agent.definition.AgentDefinitionRef("workspace-edit", 1),
        "c".repeat(64),
        UUID.randomUUID(),
        "edit",
        "按授权修改",
        "implementation",
        true,
        true,
        new dev.workbench.framework.workflow.plan.WorkflowNodeBudget(4, 2),
        dev.workbench.framework.workflow.plan.PlanNodeKind.AGENT,
        null);
  }

  private static dev.workbench.framework.workflow.plan.PlanNodeSpec spec(UUID nodeId) {
    return new dev.workbench.framework.workflow.plan.PlanNodeSpec(
        nodeId,
        "d".repeat(64),
        "edit",
        List.of("sort.js"),
        List.of("sort.js"),
        List.of(
            new dev.workbench.framework.workflow.plan.PlanNodeSpec.Output(
                dev.workbench.framework.workflow.plan.PlanNodeSpec.Kind.FILE, "sort.js", true)),
        List.of("页面能排序"),
        "e".repeat(64));
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
