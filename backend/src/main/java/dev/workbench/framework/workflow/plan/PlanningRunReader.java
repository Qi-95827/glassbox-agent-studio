package dev.workbench.framework.workflow.plan;

import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.TaskDefinition;

/** 规划确认所需的运行/任务只读视图；由存储层实现，避免 planning 直接依赖 task。 */
public interface PlanningRunReader {
  RunSnapshot runSnapshot(String runId);

  TaskDefinition taskDefinition(String taskId);
}
