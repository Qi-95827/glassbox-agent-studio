package dev.workbench.framework.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.Scenario;
import dev.workbench.framework.agent.execution.AgentRuntime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 单次 Agent 调用内的可信工具依赖，授权集合不可由模型改变。 */
public record AgentToolContext(
    ObjectMapper json,
    String workspaceId,
    List<String> authorizedPaths,
    Scenario scenario,
    AgentRuntime runtime,
    AtomicReference<RuntimeException> failure) {
  public AgentToolContext {
    Objects.requireNonNull(json, "工具 JSON 处理器不能为空");
    Objects.requireNonNull(scenario, "运行场景不能为空");
    Objects.requireNonNull(runtime, "运行控制不能为空");
    Objects.requireNonNull(failure, "工具失败状态不能为空");
    if (authorizedPaths == null
        || (workspaceId == null && !authorizedPaths.isEmpty())
        || (workspaceId != null && authorizedPaths.isEmpty())) {
      throw new IllegalArgumentException("工具上下文的工作区授权范围无效。");
    }
    authorizedPaths = List.copyOf(authorizedPaths);
  }
}
