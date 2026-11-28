package dev.workbench.bug;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.RunStatus;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.store.RunRecordService;
import dev.workbench.workspace.WorkspaceService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 统一工作流后，模拟 Bug 缺少验证条件时必须是执行阻塞，而不是待用户审查。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:bug-validation-blocked;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-bug-validation-blocked"
    })
class BugValidationBlockingIntegrationTest {
  @Autowired RunService runs;
  @Autowired WorkspaceService workspaces;
  @Autowired RunRecordService records;

  @Test
  void missingValidationConditionsWritesValidationStopCauseAndExecutionBlockedCategory() {
    var workspace = workspaces.createIdeaWorkspace(List.of("calculator.py"));
    // 收口包 G-2：模拟 Bug 必须走 Bug 入口并显式授权写入，才会物化为标准 bug-fix 工作流。
    String id =
        runs.create(
            new CreateTaskRequest(
                "模拟 Bug 流程验证条件不足",
                "normal",
                4,
                2,
                30,
                "mock",
                workspace.id(),
                "calculator.py",
                true,
                "MOCK-PY-001",
                false,
                null,
                null,
                null,
                null,
                null,
                dev.workbench.domain.TaskEntryKind.BUG));
    Map<String, Object> run = awaitTerminal(id);
    assertEquals("BLOCKED", run.get("STATUS"), String.valueOf(run.get("REASON")));
    assertEquals("CHECK_FAILED", run.get("STOP_CAUSE"));
    assertEquals("EXECUTION_BLOCKED", run.get("BLOCK_CATEGORY"));
  }

  private Map<String, Object> awaitTerminal(String id) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (true) {
      var run = records.run(id);
      if (!RunStatus.valueOf(String.valueOf(run.get("STATUS"))).canCancel()) return run;
      if (System.nanoTime() >= deadline) fail("运行未结束：" + id);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
  }
}
