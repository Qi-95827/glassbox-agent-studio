package dev.workbench.framework.workflow.plan;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.agent.definition.AgentDefinitionRegistry;
import dev.workbench.persistence.repository.PlanGraphRepository;
import dev.workbench.persistence.repository.PlanStateRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 使用生产 JPA 计划路径验证持久化依赖和条件启动，不依赖真实模型。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:plandep;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-plandep"
    })
class PlanDependencyPersistenceTest {
  @Autowired JdbcTemplate db;
  @Autowired PlanService plans;
  @Autowired PlanGraphRepository graphStore;
  @Autowired PlanStateRepository states;
  @Autowired TransactionTemplate tx;

  @Test
  void failingDependencyInsertRollsBackNodesInSameTransaction() {
    String run = UUID.randomUUID().toString();
    String constraint = "reject_test_edge_" + UUID.randomUUID().toString().replace("-", "");
    db.execute(
        "ALTER TABLE plan_node_dependencies ADD CONSTRAINT "
            + constraint
            + " CHECK (run_id <> '"
            + run
            + "')");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> tx.executeWithoutResult(status -> plans.initialize(run, false)));
    } finally {
      db.execute("ALTER TABLE plan_node_dependencies DROP CONSTRAINT " + constraint);
    }
    assertEquals(
        0, db.queryForObject("SELECT COUNT(*) FROM plan_nodes WHERE run_id=?", Integer.class, run));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM plan_node_dependencies WHERE run_id=?", Integer.class, run));
  }

  @Test
  void unregisteredSystemCheckRejectsPlanBeforeAnyRecordIsWritten() {
    var definitions =
        new AgentDefinitionRegistry(
            new dev.workbench.agent.AgentRoleRegistry(),
            new dev.workbench.framework.skill.SkillRegistry(),
            List.of(
                () ->
                    new dev.workbench.framework.agent.definition.AgentDefinition(
                        new AgentDefinitionRef("test-reader", 1),
                        "测试读取",
                        "只读取授权文件。",
                        AgentRole.ANALYST,
                        List.of(dev.workbench.domain.ToolKind.WORKSPACE_READ),
                        List.of())));
    var specStore =
        org.mockito.Mockito.mock(dev.workbench.persistence.repository.PlanNodeSpecRepository.class);
    org.mockito.Mockito.when(specStore.findByRunId(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of());
    var custom =
        new PlanService(
            states,
            new PlanFactory(new PlanValidator()),
            definitions,
            new dev.workbench.framework.workflow.check.SystemCheckRegistry(
                List.of(new dev.workbench.framework.workflow.check.AuthorizedReadEvidenceCheck())),
            graphStore,
            specStore,
            new com.fasterxml.jackson.databind.ObjectMapper());
    var template =
        new WorkflowTemplate(
            "bad-check",
            1,
            WorkflowTemplate.Scope.WORKSPACE_READ_ONLY,
            List.of(
                new WorkflowTemplate.Step(
                    "inspect",
                    "读取授权文件",
                    "analysis",
                    new AgentDefinitionRef("test-reader", 1),
                    List.of()),
                WorkflowTemplate.Step.systemCheck(
                    "check",
                    "未知检查",
                    "verification",
                    new dev.workbench.framework.workflow.check.SystemCheckRef("unknown-check", 1),
                    List.of("inspect"))));
    var task =
        new dev.workbench.domain.TaskDefinition(
            UUID.randomUUID(),
            "目标",
            dev.workbench.domain.Scenario.NORMAL,
            dev.workbench.domain.ModelMode.MOCK,
            "ws-1",
            "a.txt",
            false,
            null,
            false,
            List.of(),
            List.of(),
            dev.workbench.domain.TaskEntryKind.GENERAL,
            List.of("a.txt"));
    String run = UUID.randomUUID().toString();
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                tx.executeWithoutResult(
                    status ->
                        custom.initializeWorkflow(
                            run, template, task, true, "2026-01-01T00:00:00Z")));
    assertTrue(failure.getMessage().contains("未注册的系统检查"));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM workflow_plans WHERE run_id=?", Integer.class, run));
    assertEquals(
        0, db.queryForObject("SELECT COUNT(*) FROM plan_nodes WHERE run_id=?", Integer.class, run));
  }

  @Test
  void persistsDefinitionReferencesBeforeAnyAgentStartsAndRejectsPartialReferences() {
    String run = UUID.randomUUID().toString();
    plans.initialize(run, false);
    var nodes = plans.find(run);
    assertEquals(
        AgentDefinitionRegistry.builtinReference(AgentRole.ANALYST),
        nodes.getFirst().definitionRef());
    assertEquals(
        AgentDefinitionRegistry.builtinReference(AgentRole.REVIEWER),
        nodes.getLast().definitionRef());
    assertNull(nodes.getFirst().agentId());
    assertTrue(nodes.getFirst().definitionSha256().matches("[0-9a-f]{64}"));
    db.update(
        "UPDATE plan_nodes SET definition_version=NULL WHERE id=?",
        nodes.getFirst().id().toString());
    assertThrows(IllegalStateException.class, () -> plans.find(run));
  }

  @Test
  void startsByNodeIdAndRejectsWrongRunAndRepeatedClaim() {
    String run = UUID.randomUUID().toString();
    String other = UUID.randomUUID().toString();
    plans.initialize(run, false);
    plans.initialize(other, false);
    var first = plans.find(run).getFirst();
    var otherNode = plans.find(other).getFirst();
    assertThrows(
        IllegalStateException.class,
        () ->
            plans.startNode(
                run, otherNode.id(), UUID.randomUUID().toString(), "2026-11-17T00:00:00Z"));
    String agent = UUID.randomUUID().toString();
    plans.startNode(run, first.id(), agent, "2026-11-17T00:00:00Z");
    assertEquals(UUID.fromString(agent), plans.find(run).getFirst().agentId());
    assertThrows(
        IllegalStateException.class,
        () ->
            plans.startNode(run, first.id(), UUID.randomUUID().toString(), "2026-11-17T00:00:00Z"));
  }

  @Test
  void handoffSenderMustBeCompletedDirectDependencyOrExplicitlyReusedSource() {
    String run = UUID.randomUUID().toString();
    String analyst = UUID.randomUUID().toString();
    String coder = UUID.randomUUID().toString();
    String time = "2026-11-17T00:00:00Z";
    plans.initialize(run, true);
    plans.start(run, analyst, AgentRole.ANALYST, time);
    assertThrows(
        IllegalStateException.class,
        () -> plans.requireDirectDependencySender(run, AgentRole.CODER, UUID.fromString(analyst)));
    plans.finish(analyst, InvocationStatus.COMPLETED, time);
    plans.requireDirectDependencySender(run, AgentRole.CODER, UUID.fromString(analyst));
    plans.start(run, coder, AgentRole.CODER, time);
    plans.finish(coder, InvocationStatus.COMPLETED, time);
    plans.requireDirectDependencySender(run, AgentRole.REVIEWER, UUID.fromString(coder));
    assertThrows(
        IllegalStateException.class,
        () ->
            plans.requireDirectDependencySender(run, AgentRole.REVIEWER, UUID.fromString(analyst)));
    var nodes = plans.find(run);
    db.update(
        "INSERT INTO plan_node_dependencies(run_id,node_id,prerequisite_id) VALUES(?,?,?)",
        run,
        nodes.getLast().id().toString(),
        nodes.getFirst().id().toString());
    plans.requireDirectDependencySender(run, AgentRole.REVIEWER, UUID.fromString(analyst));
    plans.requireDirectDependencySender(run, AgentRole.REVIEWER, UUID.fromString(coder));
    assertThrows(
        IllegalStateException.class,
        () -> plans.requireDirectDependencySender(run, AgentRole.ANALYST, UUID.fromString(coder)));

    String recovered = UUID.randomUUID().toString();
    plans.initialize(recovered, false);
    plans.reuseAnalysis(recovered, analyst, time);
    plans.requireDirectDependencySender(recovered, AgentRole.REVIEWER, UUID.fromString(analyst));
    assertThrows(
        IllegalStateException.class,
        () ->
            plans.requireDirectDependencySender(recovered, AgentRole.REVIEWER, UUID.randomUUID()));
  }

  @Test
  void persistsMultiplePrerequisitesAndRequiresAllToFinish() {
    String run = UUID.randomUUID().toString();
    plans.initialize(run, true);
    var nodes = plans.find(run);
    var analyst = nodes.get(0);
    var coder = nodes.get(1);
    var reviewer = nodes.get(2);
    assertEquals(List.of(analyst.id()), coder.dependencyIds());
    assertEquals(List.of(coder.id()), reviewer.dependencyIds());
    db.update(
        "INSERT INTO plan_node_dependencies(run_id,node_id,prerequisite_id) VALUES(?,?,?)",
        run,
        reviewer.id().toString(),
        analyst.id().toString());
    assertEquals(2, plans.find(run).getLast().dependencyIds().size());
    assertEquals(2, plans.find(run).getLast().dependencyIds().size());
    db.update("UPDATE plan_nodes SET status='COMPLETED' WHERE id=?", coder.id().toString());
    assertThrows(
        IllegalStateException.class,
        () ->
            plans.start(
                run, UUID.randomUUID().toString(), AgentRole.REVIEWER, "2026-11-17T00:00:00Z"));
    db.update("UPDATE plan_nodes SET status='COMPLETED' WHERE id=?", analyst.id().toString());
    plans.start(run, UUID.randomUUID().toString(), AgentRole.REVIEWER, "2026-11-17T00:00:00Z");
    assertEquals(PlanNodeStatus.RUNNING, plans.find(run).getLast().status());
  }

  @Test
  void rejectsMissingLegacyDependencyAndCrossRunReferences() {
    String one = UUID.randomUUID().toString();
    String two = UUID.randomUUID().toString();
    plans.initialize(one, false);
    plans.initialize(two, false);
    var first = plans.find(one);
    var other = plans.find(two);
    db.update(
        "DELETE FROM plan_node_dependencies WHERE run_id=? AND node_id=?",
        one,
        first.getLast().id().toString());
    assertThrows(IllegalStateException.class, () -> plans.find(one));
    assertThrows(
        IllegalStateException.class,
        () ->
            plans.start(
                one, UUID.randomUUID().toString(), AgentRole.REVIEWER, "2026-11-17T00:00:00Z"));
    db.update(
        "INSERT INTO plan_node_dependencies(run_id,node_id,prerequisite_id) VALUES(?,?,?)",
        one,
        first.getLast().id().toString(),
        first.getFirst().id().toString());
    db.update(
        "INSERT INTO plan_node_dependencies(run_id,node_id,prerequisite_id) VALUES(?,?,?)",
        one,
        first.getLast().id().toString(),
        other.getFirst().id().toString());
    assertThrows(IllegalStateException.class, () -> plans.find(one));
  }

  @Test
  void concurrentStartsCannotClaimOneNodeTwice() throws Exception {
    String run = UUID.randomUUID().toString();
    plans.initialize(run, false);
    var gate = new CountDownLatch(1);
    Callable<Boolean> attempt =
        () -> {
          gate.await();
          try {
            plans.start(
                run, UUID.randomUUID().toString(), AgentRole.ANALYST, "2026-11-17T00:00:00Z");
            return true;
          } catch (IllegalStateException denied) {
            return false;
          }
        };
    try (var threads = Executors.newFixedThreadPool(2)) {
      var first = threads.submit(attempt);
      var second = threads.submit(attempt);
      gate.countDown();
      assertNotEquals(first.get(), second.get());
    }
    assertEquals(PlanNodeStatus.RUNNING, plans.find(run).getFirst().status());
  }
}
