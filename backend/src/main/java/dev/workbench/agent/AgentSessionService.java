package dev.workbench.agent;

import dev.workbench.task.RunRecordService;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 只读取 AgentScope 会话状态元数据，不允许由该接口修改或恢复会话。 */
@Service
public class AgentSessionService {
  private final RunRecordService runs;
  private final AgentStateStore states;

  public AgentSessionService(RunRecordService runs, AgentStateStore states) {
    this.runs = runs;
    this.states = states;
  }

  public List<AgentSessionInfo> forRun(String runId) {
    runs.runSnapshot(runId);
    return runs.agents(runId).stream()
        .map(row -> describe(UUID.fromString(row.get("ID").toString())))
        .toList();
  }

  private AgentSessionInfo describe(UUID id) {
    try {
      var saved = states.getVersioned(null, id.toString(), "agent_state", AgentState.class);
      if (saved.value() == null)
        return new AgentSessionInfo(id, AgentSessionStatus.NOT_SAVED, null);
      Long version = saved.version() > 0 ? saved.version() : null;
      return new AgentSessionInfo(id, AgentSessionStatus.SAVED, version);
    } catch (RuntimeException e) {
      // 无法读取不能解释成尚未保存；详细异常不向页面泄露会话内容。
      return new AgentSessionInfo(id, AgentSessionStatus.UNAVAILABLE, null);
    }
  }
}
