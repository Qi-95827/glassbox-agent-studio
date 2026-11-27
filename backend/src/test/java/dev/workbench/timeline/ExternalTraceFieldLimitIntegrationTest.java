package dev.workbench.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.persistence.entity.ExternalTraceRunEntity;
import dev.workbench.persistence.entity.ExternalTraceSpanEntity;
import dev.workbench.persistence.repository.ExternalTraceRunRepository;
import dev.workbench.persistence.repository.ExternalTraceSpanRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 外部 Trace 短字段必须严格落在列宽内：租户名/来源 80、nodeKey 64、status 32。
 *
 * <p>回归：`maskAndTruncate` 的 256 字保底让这些短字段失去上限，H2/PG 的 varchar 直接溢出。
 * 这里用真实 H2 建表 + 真实 `saveAndFlush`，证明 120/90/50 字符输入落库成功且被截断到列宽。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:external_trace_limits;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-trace-limits"
    })
class ExternalTraceFieldLimitIntegrationTest {
  private static final String SECRET = "sk-live-9f8e7d6c5b4a3210";

  @Autowired ExternalTraceService service;
  @Autowired ExternalTraceRunRepository runs;
  @Autowired ExternalTraceSpanRepository spans;

  @Test
  void longSourceNodeKeyAndStatusAreTruncatedToColumnWidthAndPersisted() {
    String longSource = "tenant-" + SECRET + "-" + "s".repeat(120);
    var created =
        service.create(
            new ExternalTraceService.CreateRequest("外部 Trace 名称", longSource));

    assertTrue(created.source().length() <= 80, created.source());
    assertFalse(created.source().contains(SECRET), created.source());

    String longNodeKey = "node-" + SECRET + "-" + "n".repeat(90);
    String longStatus = ("status-" + SECRET + "-" + "t".repeat(50));
    service.append(
        created.id(),
        List.of(
            new ExternalTraceService.SpanInput(
                "NODE",
                "节点名称",
                longNodeKey,
                longStatus,
                null,
                null,
                Map.of("note", "ok"))));

    ExternalTraceRunEntity run = runs.findById(created.id()).orElseThrow();
    assertTrue(run.getSource().length() <= 80, run.getSource());
    assertFalse(run.getSource().contains(SECRET), run.getSource());

    List<ExternalTraceSpanEntity> stored = spans.findByRunIdOrderBySeqAsc(created.id());
    assertEquals(1, stored.size());
    ExternalTraceSpanEntity span = stored.getFirst();
    assertTrue(span.getNodeKey().length() <= 64, "nodeKey 必须不超过 64：" + span.getNodeKey());
    assertTrue(span.getStatus().length() <= 32, "status 必须不超过 32：" + span.getStatus());
    assertFalse(span.getNodeKey().contains(SECRET), span.getNodeKey());
    assertFalse(span.getStatus().contains(SECRET), span.getStatus());
    assertTrue(span.getNodeKey().startsWith("node-"), span.getNodeKey());
  }
}
