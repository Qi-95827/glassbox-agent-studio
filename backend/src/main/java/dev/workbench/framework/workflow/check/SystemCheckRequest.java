package dev.workbench.framework.workflow.check;

import dev.workbench.domain.TaskDefinition;
import java.util.List;
import java.util.UUID;

/**
 * 一次系统检查的只读输入；执行器不得访问未在此给出的来源。
 *
 * <p>{@code guard} 由运行控制提供，用于在启动外部动作前和等待期间响应取消、暂停与时间限额。执行器必须 直接调用它，不能吞掉其停止异常。
 */
public record SystemCheckRequest(
    UUID runId,
    TaskDefinition task,
    UUID nodeId,
    String key,
    String label,
    SystemCheckRef check,
    List<SystemCheckInput> inputs,
    Runnable guard) {
  public SystemCheckRequest {
    if (runId == null || task == null || nodeId == null || check == null)
      throw new IllegalArgumentException("系统检查请求缺少运行、任务、节点或检查引用。");
    if (guard == null) throw new IllegalArgumentException("系统检查请求缺少运行控制。");
    inputs = List.copyOf(inputs);
  }
}
