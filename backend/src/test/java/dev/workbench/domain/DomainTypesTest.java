package dev.workbench.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.Handoff;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证枚举编码兼容性，避免本地历史数据和前端响应被无意改变。 */
class DomainTypesTest {
  @Test
  void parsesKnownCodesAndRejectsUnknownValues() {
    assertEquals(Scenario.NORMAL, Scenario.fromCode("normal"));
    assertEquals(ModelMode.DEEPSEEK, ModelMode.fromCode("deepseek"));
    assertThrows(IllegalArgumentException.class, () -> Scenario.fromCode("unexpected"));
    assertThrows(IllegalArgumentException.class, () -> ModelMode.fromCode("unknown"));
  }

  @Test
  void checksCancellationAndInvocationStatus() {
    assertTrue(RunStatus.PENDING.canCancel());
    assertTrue(RunStatus.RUNNING.canCancel());
    assertFalse(RunStatus.COMPLETED.canCancel());
    assertEquals(InvocationStatus.CANCELLED, InvocationStatus.fromRunStatus(RunStatus.CANCELLED));
    assertThrows(
        IllegalArgumentException.class, () -> InvocationStatus.fromRunStatus(RunStatus.PENDING));
  }

  @Test
  void existingHandoffJsonStillRoundTrips() throws Exception {
    String oldJson =
        """
        {"taskId":"task","runId":"run","agentId":"agent","sender":"analysis-agent",
        "receiver":"coordinator","goal":"目标","constraints":["限制"],"status":"COMPLETED",
        "summary":"结果","evidenceRunId":"run","evidenceEventIds":[5],"source":"deepseek-flash"}
        """;
    ObjectMapper mapper = new ObjectMapper();
    Handoff handoff = mapper.readValue(oldJson, Handoff.class);
    assertEquals(HandoffParty.ANALYSIS_AGENT, handoff.sender());
    assertEquals(HandoffSource.DEEPSEEK_FLASH, handoff.source());
    assertEquals(List.of(5L), handoff.evidenceEventIds());
    assertNull(handoff.handoffId());
    assertNull(handoff.schemaVersion());
    assertNull(handoff.testCheckIds());
    assertTrue(mapper.writeValueAsString(handoff).contains("\"source\":\"deepseek-flash\""));
  }
}
