package dev.workbench.framework.context;

import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import java.util.List;
import java.util.Objects;

/** 实际提交给 Agent 的文本、受信装配代码记录的来源标识，以及本次随 Prompt 一并解析的记忆证据。 */
public record AgentInput(
    String runId,
    String text,
    List<Source> sources,
    List<AgentAssemblySnapshot.MemoryMatchView> memoryMatches) {
  public AgentInput {
    if (text == null) throw new IllegalArgumentException("上下文文本不能为空。");
    sources = List.copyOf(sources);
    memoryMatches = memoryMatches == null ? List.of() : List.copyOf(memoryMatches);
  }

  /** 兼容未携带记忆证据的运行上下文构造。 */
  public AgentInput(String runId, String text, List<Source> sources) {
    this(runId, text, sources, List.of());
  }

  /** 兼容没有运行标识的独立上下文构造。 */
  public AgentInput(String text, List<Source> sources) {
    this(null, text, sources, List.of());
  }

  /** 上下文贡献器返回片段时一并携带同批记忆证据，供执行器直接写入装配快照而不再二次查库。 */
  public AgentInput(
      String text,
      List<Source> sources,
      List<AgentAssemblySnapshot.MemoryMatchView> memoryMatches) {
    this(null, text, sources, memoryMatches);
  }

  public record Source(Kind kind, String id, Integer version) {
    public Source {
      Objects.requireNonNull(kind, "上下文来源类型不能为空");
      if (id == null || !id.matches("[A-Za-z0-9_-]{1,96}") || (version != null && version < 1))
        throw new IllegalArgumentException("上下文来源标识或版本无效。");
    }
  }

  public enum Kind {
    CONTRIBUTOR,
    TASK,
    MEMORY,
    BUG_CHECK,
    HANDOFF,
    WORKFLOW_NODE,
    WORKFLOW_ATTEMPT,
    SYSTEM_CHECK,
    TOOL_CALL
  }
}
