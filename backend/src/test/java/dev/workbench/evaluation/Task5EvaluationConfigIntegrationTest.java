package dev.workbench.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.audit.AuditService;
import dev.workbench.task.RunRecordService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 版本化评测套件、预算配置、不可比原因与管理审计。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task5evaluation;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task5-evaluation"
    })
class Task5EvaluationConfigIntegrationTest {
  @Autowired EvaluationSuiteService suites;
  @Autowired EvaluationService evaluations;
  @Autowired EvaluationRepository repository;
  @Autowired EvaluationComparisonService comparisons;
  @Autowired AuditService audit;

  @Test
  void publishesVersionAndRecordsConfiguredBatch() throws Exception {
    var published = suites.publish(SeedSuite.Variant.LOCAL_AGENT_V2, "tester");
    assertTrue(published.version() >= 2);
    assertEquals(SeedSuite.sha256(published.definitionJson()), published.definitionSha256());

    String batchId =
        evaluations.start(
            new EvaluationConfig(
                SeedSuite.Variant.DYNAMIC_CHECK_V1.name(), null, 5, 5, 30, "tester"));
    var batch = await(batchId);
    assertEquals(SeedSuite.Variant.DYNAMIC_CHECK_V1.name(), batch.suite());
    assertNotNull(batch.suiteVersion());
    assertTrue(batch.configJson().contains("\"maxRounds\":5"));
    assertEquals("tester", batch.requestedBy());
    assertTrue(
        audit.list(100).stream()
            .anyMatch(row -> "EVALUATION_SUITE_PUBLISH".equals(row.get("ACTION"))));
    assertTrue(
        audit.list(100).stream()
            .anyMatch(
                row ->
                    "EVALUATION_START".equals(row.get("ACTION"))
                        && batchId.equals(row.get("TARGET_ID"))));
  }

  @Test
  void differentSuiteOrDefinitionHasExplicitComparisonReason() {
    String baseline = RunRecordService.id(), candidate = RunRecordService.id();
    repository.create(baseline, SeedSuite.Variant.LOCAL_AGENT_V2);
    repository.create(candidate, SeedSuite.Variant.DYNAMIC_CHECK_V1);
    var comparison = comparisons.compare(baseline, candidate);
    assertFalse(comparison.comparableDefinition());
    assertTrue(comparison.comparabilityReason().contains("套件编码不同"));
  }

  @Test
  void differentBudgetIsNotComparable() {
    var version = suites.resolve(SeedSuite.Variant.DYNAMIC_CHECK_V1, null);
    String baseline = RunRecordService.id(), candidate = RunRecordService.id();
    repository.create(
        baseline,
        SeedSuite.Variant.DYNAMIC_CHECK_V1,
        version.version(),
        version.definitionJson(),
        version.definitionSha256(),
        "{\"suite\":\"DYNAMIC_CHECK_V1\",\"maxRounds\":1}",
        "tester");
    repository.create(
        candidate,
        SeedSuite.Variant.DYNAMIC_CHECK_V1,
        version.version(),
        version.definitionJson(),
        version.definitionSha256(),
        "{\"suite\":\"DYNAMIC_CHECK_V1\",\"maxRounds\":4}",
        "tester");
    var comparison = comparisons.compare(baseline, candidate);
    assertFalse(comparison.comparableDefinition());
    assertTrue(comparison.comparabilityReason().contains("预算"));
    assertTrue(comparison.comparabilityReason().contains("1"));
    assertTrue(comparison.comparabilityReason().contains("4"));
  }

  private EvaluationBatch await(String id) throws Exception {
    for (int i = 0; i < 600; i++) {
      var batch = repository.get(id);
      if (!Set.of(EvaluationStatus.RUNNING, EvaluationStatus.CANCELLING).contains(batch.status()))
        return batch;
      Thread.sleep(100);
    }
    fail("评测未在预期时间内结束：" + repository.get(id));
    return null;
  }
}
