package dev.workbench.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.task.RunRecordService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 可管理评测数据集：草稿、发布、失败案例脱敏、批次绑定和结果回溯。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task5dataset;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task5-dataset"
    })
class ManagedEvaluationDatasetIntegrationTest {
  @Autowired ManagedEvaluationDatasetService datasets;
  @Autowired RunService runs;
  @Autowired RunRecordService records;

  @Test
  void draftPublishImmutableVersionAndBatchBindsActualSamples() {
    String key = "MANAGED_UI";
    datasets.upsert(key, sample("NORMAL_UI", "正常样本", "normal", "COMPLETED"));
    datasets.upsert(key, sample("FAIL_UI", "失败场景", "fail", "FAILED"));
    var published = datasets.publish(key, "local-user");
    assertEquals(1, published.version());
    assertEquals(2, published.sampleCount());
    String sha = published.definitionSha256();

    datasets.upsert(key, sample("NORMAL_UI", "正常样本-修改", "normal", "COMPLETED"));
    assertEquals(1, datasets.versions(key).size());
    assertEquals(sha, datasets.versions(key).getFirst().definitionSha256());
    assertEquals("正常样本-修改", datasets.draft(key).stream().filter(s -> s.code().equals("NORMAL_UI")).findFirst().orElseThrow().title());

    var batch = datasets.start(key, 1, new ManagedEvaluationDatasetService.BatchConfig(4, 2, 30));
    waitBatch(batch.id());
    var detail = datasets.detail(batch.id());
    assertEquals("COMPLETED", detail.status());
    assertEquals(2, detail.cases().size());
    var normal =
        detail.cases().stream().filter(item -> item.sampleCode().equals("NORMAL_UI")).findFirst().orElseThrow();
    assertEquals("PASSED", normal.status());
    assertNotNull(normal.runId());
    assertEquals("正常样本", normal.title(), "批次必须绑定发布时的样本快照");
    assertEquals(
        "COMPLETED", records.runSnapshot(normal.runId()).status().name());
  }

  @Test
  void failedRunRequiresConfirmationAndIsRedactedBeforeDraft() {
    String source =
        runs.create(
            new CreateTaskRequest(
                "失败录入 C:\\secret folder\\secret file.py 联系 alice@example.com 或 /tmp/secret folder/secret file.py",
                "fail",
                2,
                1,
                30,
                "mock"));
    waitRun(source);
    assertThrows(
        IllegalArgumentException.class,
        () -> datasets.fromFailure("FROM_FAILURES", source, false, "REDACTED_CASE"));
    var saved = datasets.fromFailure("FROM_FAILURES", source, true, "REDACTED_CASE");
    assertFalse(saved.goal().contains("C:\\secret"));
    assertFalse(saved.goal().contains("secret file.py"));
    assertFalse(saved.goal().contains("alice@example.com"));
    assertTrue(saved.goal().contains("[已脱敏路径]"));
    assertEquals("FAILED", saved.expectedStatus());
    assertTrue(saved.redacted());
  }

  private static ManagedEvaluationDatasetService.SampleInput sample(
      String code, String title, String scenario, String expected) {
    return new ManagedEvaluationDatasetService.SampleInput(
        code, title, "评测样本 " + title, scenario, expected, 4, 2, "MANUAL", null);
  }

  private void waitRun(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (records.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline) fail("运行未结束：" + runId);
      sleep();
    }
  }

  private void waitBatch(String batchId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while ("RUNNING".equals(datasets.detail(batchId).status())) {
      if (System.nanoTime() > deadline) fail("评测批次未结束：" + batchId);
      sleep();
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("等待被中断");
    }
  }
}
