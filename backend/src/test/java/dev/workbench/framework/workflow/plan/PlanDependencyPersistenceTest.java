package dev.workbench.framework.workflow.plan;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.domain.AgentRole;
import dev.workbench.framework.agent.definition.AgentDefinitionRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 使用独立 H2 验证持久化依赖和条件启动，不依赖真实模型。 */
class PlanDependencyPersistenceTest {
  private JdbcTemplate db;
  private PlanRepository plans;
  private DriverManagerDataSource source;

  @BeforeEach
  void setup() throws Exception {
    source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    try (var connection = source.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
    }
    db = new JdbcTemplate(source);
    plans = new PlanRepository(db, new PlanFactory(new PlanValidator()));
  }

  @Test
  void failingDependencyInsertRollsBackNodesInSameTransaction() {
    String run = UUID.randomUUID().toString();
    db.execute(
        "ALTER TABLE plan_node_dependencies ADD CONSTRAINT reject_test_edge CHECK"
            + " (node_id='impossible')");
    var transactions =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> transactions.executeWithoutResult(status -> plans.initialize(run, false)));
    assertEquals(
        0, db.queryForObject("SELECT COUNT(*) FROM plan_nodes WHERE run_id=?", Integer.class, run));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM plan_node_dependencies WHERE run_id=?", Integer.class, run));
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
    var afterRestart = new PlanRepository(db, new PlanFactory(new PlanValidator()));
    assertEquals(2, afterRestart.find(run).getLast().dependencyIds().size());
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
