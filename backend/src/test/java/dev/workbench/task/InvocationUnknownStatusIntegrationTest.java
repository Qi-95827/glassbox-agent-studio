package dev.workbench.task;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.persistence.repository.InvocationRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import dev.workbench.domain.RunLimits;
import dev.workbench.store.RunRecordService;

/** 历史未知状态文本不能让调用完成判断抛枚举转换异常。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:unknowninvocation;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-unknowninvocation"
    })
class InvocationUnknownStatusIntegrationTest {
  @Autowired RunRecordService runs;
  @Autowired InvocationRepository invocations;
  @Autowired JdbcTemplate db;

  @Test
  void unknownHistoricalStatusIsTreatedAsNotCompletedWithoutThrowing() {
    UUID taskId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    String agentId = UUID.randomUUID().toString();
    var task =
        new TaskDefinition(
            taskId,
            "未知状态校验",
            Scenario.NORMAL,
            ModelMode.MOCK,
            null,
            null,
            false,
            null,
            false,
            List.of(),
            List.of(),
            TaskEntryKind.GENERAL,
            List.of());
    runs.createTaskAndFirstRun(
        task, runId, new RunLimits(4, 2, 2, null, null, 30, null, null), "测试");
    runs.startAgent(agentId, runId.toString(), task.goal(), AgentRole.ANALYST);
    db.update("UPDATE agent_invocations SET status='FUTURE_STATUS' WHERE id=?", agentId);

    assertFalse(invocations.isCompletedAgent(agentId));
    assertNull(invocations.findCompletedAnalysis(runId.toString(), agentId));
  }
}
