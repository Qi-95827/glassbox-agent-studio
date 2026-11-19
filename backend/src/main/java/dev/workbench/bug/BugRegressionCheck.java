package dev.workbench.bug;

import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.workflow.check.SystemCheckExecutor;
import dev.workbench.framework.workflow.check.SystemCheckRef;
import dev.workbench.framework.workflow.check.SystemCheckRequest;
import dev.workbench.framework.workflow.check.SystemCheckResult;
import org.springframework.stereotype.Component;

/**
 * Bug 回归检查：在授权修复后重新执行既有固定检测，要求当前代码版本通过。
 *
 * <p>{@link BugService} 会在执行前后核对工作区版本；期间代码变化时结果标记为过期，不能作为通过证据。
 */
@Component
public class BugRegressionCheck implements SystemCheckExecutor {
  public static final SystemCheckRef REF = new SystemCheckRef("bug-regression", 1);
  private final BugService bugs;
  private final BugIntakeService intakes;

  public BugRegressionCheck(BugService bugs, BugIntakeService intakes) {
    this.bugs = bugs;
    this.intakes = intakes;
  }

  @Override
  public SystemCheckRef ref() {
    return REF;
  }

  @Override
  public SystemCheckResult run(SystemCheckRequest request) {
    TaskDefinition task = request.task();
    if (!bugs.supports(task.bugId()) || task.workspaceId() == null)
      return SystemCheckResult.blocked("回归检查缺少明确的模拟 Bug 或工作区，不能执行。", "任务未绑定受支持的模拟 Bug 与工作区。");
    if (!task.writeEnabled()) return SystemCheckResult.blocked("回归检查需要显式写入授权。", "任务未授权修改文件。");
    if (request.inputs().stream().anyMatch(input -> !input.settled()))
      return SystemCheckResult.blocked("前置节点尚未结算，不能确认回归结果。", "存在未结算输入。");
    var check =
        bugs.runForTask(
            request.runId().toString(),
            task.bugId(),
            task.workspaceId(),
            BugService.CheckKind.REGRESSION,
            request.guard());
    intakes.regression(check);
    return BugCheckResults.regression(check);
  }
}
