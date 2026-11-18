package dev.workbench.bug;

import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.workflow.check.SystemCheckExecutor;
import dev.workbench.framework.workflow.check.SystemCheckRef;
import dev.workbench.framework.workflow.check.SystemCheckRequest;
import dev.workbench.framework.workflow.check.SystemCheckResult;
import org.springframework.stereotype.Component;

/**
 * Bug 复现检查：调用既有固定容器检测，要求本次运行观察到测试失败。
 *
 * <p>只接受与运行、Bug、工作区匹配的检测记录；复现失败、缺少测试、环境不可用或代码版本变化都不能进入下游 修改节点。检测命令由 {@link BugService} 固定，模型不能提供
 * shell 命令。
 */
@Component
public class BugReproductionCheck implements SystemCheckExecutor {
  public static final SystemCheckRef REF = new SystemCheckRef("bug-reproduction", 1);
  private final BugService bugs;

  public BugReproductionCheck(BugService bugs) {
    this.bugs = bugs;
  }

  @Override
  public SystemCheckRef ref() {
    return REF;
  }

  @Override
  public SystemCheckResult run(SystemCheckRequest request) {
    TaskDefinition task = request.task();
    if (!bugs.supports(task.bugId()) || task.workspaceId() == null)
      return SystemCheckResult.blocked("复现检查缺少明确的模拟 Bug 或工作区，不能执行。", "任务未绑定受支持的模拟 Bug 与工作区。");
    if (request.inputs().stream().anyMatch(input -> !input.settled()))
      return SystemCheckResult.blocked("前置节点尚未结算，不能确认复现结果。", "存在未结算输入。");
    var check =
        bugs.runForTask(
            request.runId().toString(),
            task.bugId(),
            task.workspaceId(),
            BugService.CheckKind.REPRODUCE,
            request.guard());
    return BugCheckResults.reproduction(check);
  }
}
