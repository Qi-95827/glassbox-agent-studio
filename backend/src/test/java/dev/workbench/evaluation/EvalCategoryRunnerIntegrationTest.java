package dev.workbench.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.evaluation.dataset.BuiltInMiniPackLoader;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalCategoryRunner;
import dev.workbench.framework.evaluation.EvalExperimentConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 六大执行器真实组件联调：只读取实际证据字典，不依赖伪造分数。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:eval_lab_runners;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-eval-lab-runners"
    })
class EvalCategoryRunnerIntegrationTest {
  private static final EvalExperimentConfig CONFIG =
      new EvalExperimentConfig("mock", null, true, true, true, false, 1, 4, null, null);

  @Autowired List<EvalCategoryRunner> runners;
  @Autowired BuiltInMiniPackLoader packs;

  private EvalCategoryRunner runner(EvalCategory category) {
    return runners.stream().filter(item -> item.category() == category).findFirst().orElseThrow();
  }

  private dev.workbench.framework.evaluation.EvalSample sample(String id) {
    return packs.loadAll().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
  }

  @Test
  void contextCompactionUsesRealCompactorAndReportsTierAndRetention() {
    var execution = runner(EvalCategory.CONTEXT_COMPACTION).run(sample("ctx-01"), CONFIG, "LOCAL");
    assertTrue(((Number) execution.metrics().get("beforeTokens")).intValue() > 0);
    assertNotEquals("L0_OBSERVE_ONLY", execution.metrics().get("compactionTier"));
    assertTrue(((Number) execution.metrics().get("compressionSavedPercent")).doubleValue() > 0);
    assertTrue(execution.evidence().get("retainedNeedles") instanceof List<?>);
  }

  @Test
  void toolRunnerReplaysThroughRealGateway() {
    var execution = runner(EvalCategory.TOOL_MCP).run(sample("tool-01"), CONFIG, "LOCAL");
    @SuppressWarnings("unchecked")
    var calls = (List<Map<String, Object>>) execution.evidence().get("toolCalls");
    assertEquals("SUCCEEDED", calls.getFirst().get("status"), String.valueOf(calls.getFirst().get("error")));
    assertEquals(Boolean.TRUE, execution.metrics().get("schemaValid"));
    assertEquals(1.0, execution.metrics().get("gatewayPassRate"));
  }

  @Test
  void memoryRunnerUsesHybridRetrievalAndNeverLeaksCandidates() {
    var execution = runner(EvalCategory.LONG_TERM_MEMORY).run(sample("mem-01"), CONFIG, "LOCAL");
    assertEquals(1.0, execution.metrics().get("recallAtK"));
    assertEquals(Boolean.FALSE, execution.metrics().get("candidateLeaked"));
    assertTrue(((Number) execution.durationMs()).longValue() >= 0);
  }

  @Test
  void planningRunnerValidatesRealCandidateAndEnvelope() {
    var execution =
        runner(EvalCategory.PLANNING_MULTI_AGENT).run(sample("plan-01"), CONFIG, "LOCAL");
    assertEquals(Boolean.TRUE, execution.metrics().get("planValid"));
    assertEquals(0, ((Number) execution.metrics().get("writeConflicts")).intValue());
    assertEquals(1.0, execution.metrics().get("handoffPassRate"));
    assertTrue(((Number) execution.metrics().get("parallelNodeWidth")).intValue() >= 1);
  }

  @Test
  void safetyRunnerBlocksTraversalThroughRealGateway() {
    var execution =
        runner(EvalCategory.SAFETY_INJECTION).run(sample("safe-01"), CONFIG, "LOCAL");
    assertEquals(1.0, execution.metrics().get("blockRate"));
    assertEquals(0, ((Number) execution.metrics().get("unauthorizedWrites")).intValue());
    assertNotEquals("FAILED", execution.metrics().get("executionError"));
  }

  @Test
  void codingRunnerCreatesRealRunAndTempWorkspace() {
    var execution =
        runner(EvalCategory.CODING_REFLECTION).run(sample("code-01"), CONFIG, "LOCAL");
    assertNotNull(execution.linkedRunId(), String.valueOf(execution.trace().get("executionError")));
    assertEquals(Boolean.TRUE, execution.evidence().get("terminal"));
    assertNotNull(execution.metrics().get("runStatus"));
    assertTrue(execution.trace().containsKey("workspacePath"));
  }
}
