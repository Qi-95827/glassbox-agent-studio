package dev.workbench.store;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.domain.RunLimits;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/** 完成通知必须晚于外层事务提交；回滚不得唤醒等待方。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:completion-notify;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-completion-notify"
    })
class RunCompletionNotificationIntegrationTest {
  @Autowired RunRecordService records;
  @Autowired RunCompletionWaiter waiter;
  @Autowired TransactionTemplate tx;

  @Test
  void rollbackDoesNotNotifyAndCommitNotifiesAfterCommit() {
    var taskId = UUID.randomUUID();
    var runId = UUID.randomUUID();
    var task =
        new TaskDefinition(
            taskId,
            "通知验收",
            Scenario.NORMAL,
            ModelMode.MOCK,
            null,
            null,
            false,
            null,
            false,
            List.of(),
            List.of("完成"),
            TaskEntryKind.GENERAL,
            List.of(),
            Map.of());
    records.createTaskAndFirstRun(
        task, runId, new RunLimits(null, null, null, null, null, null, null, null), "created");
    var future = waiter.register(runId.toString());

    tx.executeWithoutResult(
        status -> {
          records.update(runId.toString(), RunStatus.COMPLETED, null, "ok", 0, 0, 1);
          assertFalse(future.isDone(), "外层事务提交前不得通知");
          status.setRollbackOnly();
        });
    assertFalse(future.isDone(), "回滚后不得通知");
    assertEquals(RunStatus.PENDING, records.runSnapshot(runId.toString()).status());

    records.update(runId.toString(), RunStatus.COMPLETED, null, "ok", 0, 0, 1);
    assertTrue(future.isDone(), "提交后必须通知");
  }
}
