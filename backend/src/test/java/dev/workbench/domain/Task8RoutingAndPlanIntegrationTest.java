package dev.workbench.domain;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.framework.workflow.plan.PlanService;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.task.PlanProposalService;
import dev.workbench.task.RunRecordService;
import dev.workbench.task.SourceArtifactService;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 任务 8：规则路由、候选计划校验/调整和既有成果来源校验。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task8routing;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task8-routing"
    })
class Task8RoutingAndPlanIntegrationTest {
  @Autowired RunService runs;
  @Autowired RunRecordService records;
  @Autowired EntryRouteService routes;
  @Autowired PlanService plans;
  @Autowired PlanProposalService proposals;
  @Autowired SourceArtifactService sources;

  @Test
  void fourEntriesRouteToExplainedTemplatesAndPersistDecision() {
    String run =
        runs.create(
            new CreateTaskRequest(
                "梳理需求路由",
                "normal",
                2,
                1,
                30,
                "mock",
                null,
                null,
                false,
                null,
                false,
                null,
                List.of(),
                List.of(),
                null,
                null,
                TaskEntryKind.REQUIREMENT,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null));
    String taskId = records.runSnapshot(run).taskId().toString();
    var saved = routes.find(taskId);
    assertNotNull(saved);
    assertEquals("REQUIREMENT_TEXT_RULE", saved.get("RULE_CODE"));
    assertEquals(EntryRouteService.REQUIREMENT_TEMPLATE, saved.get("TEMPLATE_ID"));
    assertEquals("REQUIREMENT", saved.get("ROUTED_KIND"));
    assertTrue(saved.get("EXPLANATION_JSON").toString().contains("纯文本分析节点"));
  }

  @Test
  void candidatePlanIsValidatedAppliedAndVersionDiffersBeforeApproval() {
    String run =
        runs.create(
            new CreateTaskRequest(
                "设计候选计划",
                "normal",
                3,
                1,
                30,
                "mock",
                null,
                null,
                false,
                null,
                false,
                null,
                List.of(),
                List.of(),
                null,
                null,
                TaskEntryKind.DESIGN,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null));
    var plan = plans.workflowPlan(run);
    var nodes = plans.find(run);
    String baseSha = plan.graphSha256();
    // 反例：缺少节点、未知依赖和超出预算都在保存前被拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () -> proposals.propose(run, List.of(change(nodes.getFirst(), List.of())), baseSha));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            proposals.propose(
                run,
                nodes.stream()
                    .map(
                        node ->
                            change(
                                node,
                                node == nodes.getFirst()
                                    ? List.of("not-a-node")
                                    : node.dependencyIds().stream()
                                        .map(id -> plans.find(run).stream()
                                            .filter(item -> item.id().equals(id))
                                            .findFirst()
                                            .orElseThrow()
                                            .key())
                                        .toList()))
                    .toList(),
                baseSha));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            proposals.propose(
                run,
                nodes.stream()
                    .map(
                        node ->
                            change(
                                node,
                                node == nodes.getFirst()
                                    ? List.of(nodes.get(1).key())
                                    : node == nodes.get(1)
                                        ? List.of(nodes.getFirst().key())
                                        : node.dependencyIds().stream()
                                            .map(id -> plans.find(run).stream()
                                                .filter(item -> item.id().equals(id))
                                                .findFirst()
                                                .orElseThrow()
                                                .key())
                                            .toList()))
                    .toList(),
                baseSha));
    var proposal =
        proposals.propose(
            run,
            nodes.stream()
                .map(
                    node ->
                        new PlanProposalService.NodeChange(
                            node.key(),
                            1,
                            null,
                            node.dependencyIds().stream()
                                .map(
                                    id ->
                                        nodes.stream()
                                            .filter(item -> item.id().equals(id))
                                            .findFirst()
                                            .orElseThrow()
                                            .key())
                                .toList()))
                .toList(),
            baseSha);
    assertFalse(proposal.diff().isEmpty());
    assertEquals("PROPOSED", proposal.status());
    var applied = proposals.apply(run, proposal.id());
    assertNotEquals(baseSha, applied.graphSha256());
    assertEquals(
        3,
        plans.find(run).stream()
            .map(item -> item.budget() == null ? null : item.budget().maxModelCalls())
            .filter(value -> value != null && value == 1)
            .count());
    runs.approvePlan(run, plan.id());
    waitTerminal(run);
    assertEquals("COMPLETED", records.runSnapshot(run).status().name());
  }

  @Test
  void existingArtifactRequiresSettledSourceAndMatchingTaskVersions() {
    String source =
        runs.create(
            new CreateTaskRequest(
                "来源需求成果",
                "normal",
                4,
                2,
                30,
                "mock",
                null,
                null,
                false,
                null,
                false,
                null,
                List.of(),
                List.of(),
                null,
                null,
                TaskEntryKind.REQUIREMENT,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null));
    waitTerminal(source);
    String target =
        runs.create(
            new CreateTaskRequest(
                "设计接入既有成果",
                "normal",
                2,
                1,
                30,
                "mock",
                null,
                null,
                false,
                null,
                false,
                null,
                List.of(),
                List.of(),
                null,
                null,
                TaskEntryKind.DESIGN,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null));
    String targetTask = records.runSnapshot(target).taskId().toString();
    int sourceVersion = records.taskVersion(records.runSnapshot(source).taskId().toString());
    int targetVersion = records.taskVersion(targetTask);
    assertThrows(
        IllegalStateException.class,
        () ->
            sources.attach(
                target, targetVersion + 1, source, SourceArtifactService.Kind.DOCUMENT, sourceVersion));
    var artifact =
        sources.attach(
            target, targetVersion, source, SourceArtifactService.Kind.DOCUMENT, sourceVersion);
    assertTrue(artifact.summary().contains("来源运行状态 COMPLETED"), artifact.summary());
    assertThrows(
        IllegalStateException.class,
        () ->
            sources.attach(
                target, targetVersion, source, SourceArtifactService.Kind.DOCUMENT, sourceVersion));
    sources.requireCurrent(targetTask);
    assertEquals(1, sources.find(targetTask).size());
  }

  private static PlanProposalService.NodeChange change(
      dev.workbench.framework.workflow.plan.PlanNode node, List<String> dependencies) {
    return new PlanProposalService.NodeChange(
        node.key(), node.budget() == null ? null : node.budget().maxModelCalls(), null, dependencies);
  }

  private void waitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (records.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline) fail("运行未在时限内结束：" + runId);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待运行被中断");
      }
    }
  }
}
