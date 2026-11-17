package dev.workbench.framework.context;

import dev.workbench.bug.BugService;
import dev.workbench.domain.TaskDefinition;
import java.util.Objects;

/** 供上下文来源读取的当前任务及本次证据；不授予额外权限。 */
public record AgentContextInput(TaskDefinition task, String runId, BugService.Check initialCheck) {
  public AgentContextInput {
    Objects.requireNonNull(task, "任务不能为空");
    if (runId == null || runId.isBlank()) throw new IllegalArgumentException("运行标识不能为空。");
  }
}
