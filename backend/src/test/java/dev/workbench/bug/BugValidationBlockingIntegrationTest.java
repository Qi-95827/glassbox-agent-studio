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

/** 兼容 Bug 流程缺少 tests 目录时必须是执行阻塞，而不是待用户审查。 */
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
    String id =
        runs.create(
            new CreateTaskRequest(
                "兼容 Bug 流程验证条件不足",
                "normal",
                4,
                2,
                30,
                "mock",
                workspace.id(),
                "calculator.py",
                false,
                "MOCK-PY-001"));
    Map<String, Object> run = awaitTerminal(id);
    assertEquals("BLOCKED", run.get("STATUS"), String.valueOf(run.get("REASON")));
    assertEquals("VALIDATION_MISSING", run.get("STOP_CAUSE"));
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
