package dev.workbench.task;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.planning.PlanService;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import dev.workbench.store.RunRecordService;
import dev.workbench.planning.RecoveryLeaseService;
import dev.workbench.planning.RunSnapshotService;

/** 周期快照、损坏检测和恢复租约的并发/过期语义。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task6snapshots;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task6-snapshots"
    })
class RunSnapshotAndLeaseIntegrationTest {
  @Autowired RunService runs;
  @Autowired RunRecordService records;
  @Autowired PlanService plans;
  @Autowired RunSnapshotService snapshots;
  @Autowired RecoveryLeaseService leases;
  @Autowired JdbcTemplate db;

  @Test
  void periodicSnapshotSupportsVerifiedRecoveryAndRejectsCorruption() {
    String run = textRun();
    waitTerminal(run);
    var list = snapshots.find(run);
    assertFalse(list.isEmpty(), "动态工作流应在已结算边界保存周期快照");
    var verified = snapshots.verifyLatest(run, plans.workflowPlan(run));
    assertEquals(
        list.getLast().get("EVENT_SEQ").toString(), Long.toString(verified.eventSeq()));

    String latestId = list.getLast().get("ID").toString();
    db.update("update run_snapshots set boundary_sha256=? where id=?", "0".repeat(64), latestId);
    assertThrows(
        IllegalStateException.class,
        () -> snapshots.verifyLatest(run, plans.workflowPlan(run)));
  }

  @Test
  void recoveryLeaseRejectsConcurrentOwnerAllowsExpiryAndRelease() {
    String run = textRun();
    waitTerminal(run);
    var first = leases.acquire(run, "process-a", Duration.ofSeconds(60));
    assertThrows(
        IllegalStateException.class,
        () -> leases.acquire(run, "process-b", Duration.ofSeconds(60)));
    db.update(
        "update recovery_leases set expires_at=? where run_id=?",
        java.time.Instant.now().minusSeconds(1).toString(),
        run);
    var takeover = leases.acquire(run, "process-b", Duration.ofSeconds(60));
    assertNotEquals(first.token(), takeover.token());
    leases.release(run, takeover.token());
    assertEquals("RELEASED", leases.find(run).get("STATUS"));
    var third = leases.acquire(run, "process-c", Duration.ofSeconds(60));
    assertEquals("ACTIVE", leases.find(run).get("STATUS"));
    leases.release(run, third.token());
  }

  private String textRun() {
    return runs.create(
        new CreateTaskRequest(
            "周期快照验证",
            "normal",
            4,
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
            false,
            null));
  }

  private void waitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (records.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline) fail("运行未结束：" + runId);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
  }
}
