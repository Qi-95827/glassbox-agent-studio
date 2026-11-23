package dev.workbench.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Benchmark Hub：URL 白名单、体积保护与离线 JSONL 兜底。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:eval_hub;DB_CLOSE_DELAY=-1",
      "workbench.eval.datasets-root=${java.io.tmpdir}/agent-workbench-eval-hub-test",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-eval-hub-workspace"
    })
class EvalBenchmarkHubIntegrationTest {
  @Autowired EvalBenchmarkHubService hub;

  @Test
  void rejectsArbitraryOrNonHttpsUrlsAndAcceptsCatalogEntry() {
    var pack = hub.requirePack("bfcl-v3-simple");
    assertDoesNotThrow(() -> hub.validateSourceUrl(pack, pack.downloadUrl()));
    assertThrows(
        IllegalArgumentException.class,
        () -> hub.validateSourceUrl(pack, "http://127.0.0.1:8088/internal"));
    assertThrows(
        IllegalArgumentException.class,
        () -> hub.validateSourceUrl(pack, "https://evil.example.com/data.jsonl"));
    assertThrows(
        IllegalArgumentException.class,
        () -> hub.validateSourceUrl(pack, pack.downloadUrl() + "&offset=999"));
  }

  @Test
  void offlineImportNormalizesAndPersistsOutsideGit() throws Exception {
    String jsonl =
        "{\"id\":\"bfcl-test-1\",\"question\":\"Read the authorized file and report its first line.\",\"function\":[{\"name\":\"workspace_read\"}]}\n";
    var result = hub.importJsonl("bfcl-v3-simple", jsonl);
    assertEquals("IMPORTED", result.status());
    assertEquals(1, result.sampleCount());
    assertNotNull(result.sha256());
    assertEquals(64, result.sha256().length());
    Path file =
        Path.of(System.getProperty("java.io.tmpdir"), "agent-workbench-eval-hub-test", "bfcl-v3-simple", "samples.jsonl");
    assertTrue(Files.exists(file), "标准化样本必须落盘到 data/eval-datasets 根目录");
    var samples = hub.samplesForRun(java.util.List.of("bfcl-v3-simple"));
    assertEquals(1, samples.size());
    assertEquals("hub:bfcl-v3-simple", samples.getFirst().sourcePack());
    assertEquals(Boolean.TRUE, samples.getFirst().setup().get("hubAdapted"));
  }

  @Test
  void unknownPackIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> hub.requirePack("not-in-catalog"));
  }
}
