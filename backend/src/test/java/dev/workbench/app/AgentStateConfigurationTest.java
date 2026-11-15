package dev.workbench.app;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 使用隔离数据库验证框架存储的重建读取和版本冲突。 */
class AgentStateConfigurationTest {
  @Test
  void frameworkStateSurvivesStoreRecreationAndRejectsStaleVersion() {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:session_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var store = new AgentStateConfiguration().agentStateStore(dataSource);
    var initial = AgentState.builder().sessionId("session-one").summary("第一版").build();
    assertEquals(1L, store.saveIfVersion(null, "session-one", "agent_state", initial, 0));
    var reopened = new AgentStateConfiguration().agentStateStore(dataSource);
    var loaded = reopened.getVersioned(null, "session-one", "agent_state", AgentState.class);
    assertEquals(1L, loaded.version());
    assertEquals("第一版", loaded.value().getSummary());
    var updated = AgentState.builder().sessionId("session-one").summary("第二版").build();
    assertEquals(2L, reopened.saveIfVersion(null, "session-one", "agent_state", updated, 1));
    assertEquals(
        AgentStateStore.UNVERSIONED,
        store.saveIfVersion(null, "session-one", "agent_state", initial, 1));
    assertEquals(
        "第二版",
        store.get(null, "session-one", "agent_state", AgentState.class).orElseThrow().getSummary());
    assertTrue(store.get(null, "session-two", "agent_state", AgentState.class).isEmpty());
    assertTrue(store.get("other-user", "session-one", "agent_state", AgentState.class).isEmpty());
  }
}
