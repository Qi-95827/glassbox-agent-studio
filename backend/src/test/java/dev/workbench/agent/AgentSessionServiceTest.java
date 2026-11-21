package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.workbench.store.RunRecordService;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.VersionedState;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import dev.workbench.infrastructure.agentscope.AgentSessionService;
import dev.workbench.framework.agent.session.AgentSessionInfo;
import dev.workbench.framework.agent.session.AgentSessionStatus;
import dev.workbench.store.RunRecordService;

/** 观察信息必须区分缺失和读取失败，不能返回状态正文。 */
class AgentSessionServiceTest {
  @Test
  void distinguishesSavedMissingAndUnavailable() {
    var runs = mock(RunRecordService.class);
    var states = mock(AgentStateStore.class);
    UUID saved = UUID.randomUUID(), missing = UUID.randomUUID(), failed = UUID.randomUUID();
    when(runs.agents("run"))
        .thenReturn(
            List.of(
                Map.of("ID", saved.toString()),
                Map.of("ID", missing.toString()),
                Map.of("ID", failed.toString())));
    when(states.getVersioned(
            isNull(), eq(saved.toString()), eq("agent_state"), eq(AgentState.class)))
        .thenReturn(
            new VersionedState<>(
                AgentState.builder().sessionId(saved.toString()).summary("隐私内容").build(), 3));
    when(states.getVersioned(
            isNull(), eq(missing.toString()), eq("agent_state"), eq(AgentState.class)))
        .thenReturn(new VersionedState<>(null, 0));
    when(states.getVersioned(
            isNull(), eq(failed.toString()), eq("agent_state"), eq(AgentState.class)))
        .thenThrow(new IllegalStateException("读取失败"));
    var result = new AgentSessionService(runs, states).forRun("run");
    assertEquals(
        List.of(
            AgentSessionStatus.SAVED, AgentSessionStatus.NOT_SAVED, AgentSessionStatus.UNAVAILABLE),
        result.stream().map(AgentSessionInfo::status).toList());
    assertEquals(3L, result.getFirst().version());
    assertNull(result.get(1).version());
    assertFalse(result.toString().contains("隐私内容"));
    verify(runs).runSnapshot("run");
  }
}
