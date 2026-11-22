package dev.workbench.framework.context;

import java.util.List;
import java.util.Objects;

/** 实际提交给 Agent 的文本与受信装配代码记录的来源标识。 */
public record AgentInput(String runId, String text, List<Source> sources) {
  public AgentInput {
    if (text == null) throw new IllegalArgumentException("上下文文本不能为空。");
    sources = List.copyOf(sources);
  }

  /** 兼容没有运行标识的独立上下文构造。 */
  public AgentInput(String text, List<Source> sources) {
    this(null, text, sources);
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
