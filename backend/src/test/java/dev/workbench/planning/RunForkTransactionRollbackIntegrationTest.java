package dev.workbench.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.framework.workflow.plan.PlanNodeSpec;
import dev.workbench.framework.workflow.plan.PlanningRunReader;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.persistence.repository.RunForkRepository;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunIterationRecordService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import dev.workbench.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 分叉事务原子性真实数据库故障注入测试。
 *
 * <p>在真实 H2 + Spring {@link TransactionTemplate} 下，把 {@link CostLimitPort#applyPreparedLimit}
 * 注入运行时异常，验证先执行的 {@code persistFromCandidate}、{@code forks.save} 与 {@code evidence.event}
 * 必须整体回滚，数据库不留下任何孤儿任务、运行或分叉记录，且绝不派发。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:fork_tx_rollback;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-fork-tx-rollback"
    })
class RunForkTransactionRollbackIntegrationTest {
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceAuthorizationService authorization;
  @Autowired CandidateRunPersister persister;
  @Autowired PlanningRunReader records;
  @Autowired PlanService plans;
  @Autowired ModelConnectionService connections;
  @Autowired RunEvidenceRecordService evidence;
  @Autowired RunForkRepository forks;
  @Autowired RunIterationRecordService iterations;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate db;

  @Test
  void budgetApplyFailureRollsBackTaskRunAndForkRowsAtomically() {
    String workspaceId = workspaces.createIdeaWorkspace(List.of("sort.js")).id();
    var preparedScope = authorization.prepare(workspaceId, List.of("sort.js"), List.of(), List.of());

    UUID sourceTaskId = UUID.randomUUID();
    UUID sourceRunId = UUID.randomUUID();
    var sourceTask =
        new TaskDefinition(
            sourceTaskId,
            "源任务",
            Scenario.NORMAL,
            ModelMode.MOCK,
            workspaceId,
            "sort.js",
            true,
            null,
            false,
            List.of("不要引入构建工具"),
            List.of("排序可核对"),
            TaskEntryKind.CODE,
            List.of("sort.js"),
            Map.of(),
            Map.of(),
            null,
            "zh-CN");
    var implNode =
        new CandidateRunPlan.Node(
            "impl",
            "修改排序逻辑",
            "implementation",
            new AgentDefinitionRef("workspace-edit", 1),
            List.of(),
            List.of("sort.js"),
            List.of("sort.js"),
            List.of(new PlanNodeSpec.Output(PlanNodeSpec.Kind.FILE, "sort.js", true)),
            List.of("排序正确"),
            null);
    var reviewNode =
        new CandidateRunPlan.Node(
            "review",
            "独立审查改动",
            "review",
            new AgentDefinitionRef("workspace-change-review", 1),
            List.of("impl"),
            List.of(),
            List.of(),
            List.of(new PlanNodeSpec.Output(PlanNodeSpec.Kind.TEXT, null, true)),
            List.of("审查通过"),
            null);
    var sourcePlan =
        new CandidateRunPlan(1, "a".repeat(64), List.of(implNode, reviewNode), List.of());
    persister.persistFromCandidate(
        sourceTask,
        sourceRunId,
        PlanCandidateConfirmationService.defaultRunLimits(),
        "源运行创建",
        sourcePlan,
        preparedScope,
        null);
    db.update("update runs set status = 'COMPLETED' where id = ?", sourceRunId.toString());

    int tasksBefore = count("tasks");
    int runsBefore = count("runs");
    int forksBefore = count("run_forks");
    int eventsBefore = count("events");

    var dispatcher = mock(CandidateRunDispatcher.class);
    CostLimitPort failingCosts =
        new CostLimitPort() {
          @Override
          public void inheritLimit(String parentRunId, String childRunId) {}

          @Override
          public Optional<PreparedInheritedLimit> prepareInheritedLimit(
              String parentRunId, ModelMode childMode) {
            return Optional.of(new PreparedInheritedLimit(5000L, "p1", "{\"mock\":\"p1\"}"));
          }

          @Override
          public void applyPreparedLimit(String childRunId, PreparedInheritedLimit prepared) {
            throw new IllegalStateException("模拟预算落库故障");
          }
        };

    var forkService =
        new RunForkService(
            records,
            plans,
            authorization,
            persister,
            dispatcher,
            connections,
            evidence,
            forks,
            failingCosts,
            iterations,
            tx);

    var error =
        assertThrows(
            IllegalStateException.class,
            () ->
                forkService.fork(
                    sourceRunId.toString(), new RunForkService.Request(null, null, null, null, null)));
    assertTrue(error.getMessage().contains("模拟预算落库故障"));

    assertEquals(tasksBefore, count("tasks"), "任务表必须回滚，不得留下孤儿任务");
    assertEquals(runsBefore, count("runs"), "运行表必须回滚，不得留下孤儿运行");
    assertEquals(forksBefore, count("run_forks"), "分叉表必须回滚，不得留下孤儿分叉记录");
    assertEquals(eventsBefore, count("events"), "事件表必须回滚，不得留下孤儿分叉事件");
    verify(dispatcher, never()).dispatchConfirmed(anyString());
  }

  private int count(String table) {
    Integer n = db.queryForObject("select count(*) from " + table, Integer.class);
    return n == null ? 0 : n;
  }
}
