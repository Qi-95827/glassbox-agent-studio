package dev.workbench.external;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 外部动作对账：外部已成功、本地未知时只补证据，不重复执行。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task8external;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task8-external"
    })
class ExternalActionReconciliationIntegrationTest {
  @Autowired ExternalActionService actions;
  @Autowired InMemoryExternalActionAdapter adapter;

  @Test
  void unknownResultIsReconciledWithoutReplayAndKeepsCheckHistory() {
    String key = "issue:sync:" + java.util.UUID.randomUUID();
    var prepared =
        actions.prepare(
            null, key, "ISSUE_CREATE", "approval-1", "https://tracker.example/42", "v7", "payload");
    assertEquals("APPROVED", prepared.status());
    var dispatched = actions.markDispatched(key);
    assertEquals("DISPATCHED", dispatched.status());
    assertEquals(1, dispatched.attempts());

    adapter.register(key, ExternalActionAdapter.Outcome.UNKNOWN, "平台查询超时");
    var unknown = actions.reconcile(key);
    assertEquals(ExternalActionService.NEEDS_MANUAL_REVIEW, unknown.status());
    assertTrue(unknown.needsManualReview());
    assertEquals(1, unknown.attempts(), "查询不能作为重复执行");
    assertEquals(1, unknown.checks().size());

    // 外部实际已成功，但本地第一次没有收到结果；查询后只补记证据。
    adapter.register(key, ExternalActionAdapter.Outcome.SUCCEEDED, "平台返回 issue 42 已创建");
    var succeeded = actions.reconcile(key);
    assertEquals("SUCCEEDED", succeeded.status());
    assertTrue(succeeded.resultEvidence().contains("issue 42"));
    assertEquals(1, succeeded.attempts());
    assertEquals(2, succeeded.checks().size());

    var again = actions.reconcile(key);
    assertEquals("SUCCEEDED", again.status());
    assertEquals(1, again.attempts(), "已确认动作再次查询不得重放");
    assertEquals(2, again.checks().size(), "已确认后重复查询不再增加历史");

    var duplicate =
        assertThrows(
            IllegalStateException.class,
            () ->
                actions.prepare(
                    null, key, "ISSUE_CREATE", "approval-2", "https://tracker.example/43", "v8", "payload"));
    assertTrue(duplicate.getMessage().contains("已经登记"));
  }

  @Test
  void notFoundAndUnknownRemainManualReviewWithHistory() {
    String key = "issue:unknown:" + java.util.UUID.randomUUID();
    actions.prepare(null, key, "ISSUE_UPDATE", "approval-3", "https://tracker.example/9", "v2", "payload");
    actions.markDispatched(key);
    adapter.register(key, ExternalActionAdapter.Outcome.NOT_FOUND, "查询不到目标");
    assertEquals("NOT_FOUND", actions.reconcile(key).status());
    adapter.register(key, ExternalActionAdapter.Outcome.UNKNOWN, "查询仍失败");
    var unknown = actions.reconcile(key);
    assertEquals(ExternalActionService.NEEDS_MANUAL_REVIEW, unknown.status());
    assertEquals(2, unknown.checks().size());
  }
}
