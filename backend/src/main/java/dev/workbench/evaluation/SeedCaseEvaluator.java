package dev.workbench.evaluation;

import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunStatus;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.PlanRepository;
import dev.workbench.task.RunRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 使用系统持久化记录展开固定评测断言，不信任模型自述。 */
@Component
public class SeedCaseEvaluator {
  private final RunRepository records;
  private final PlanRepository plans;

  public SeedCaseEvaluator(RunRepository records, PlanRepository plans) {
    this.records = records;
    this.plans = plans;
  }

  public List<EvaluationCheck> evaluate(SeedCase sample, String runId, RunStatus status) {
    var checks = new ArrayList<EvaluationCheck>();
    checks.add(
        new EvaluationCheck(
            EvaluationCheckCode.RUN_STATUS,
            status == sample.expected(),
            "预期运行状态：" + sample.expected().name() + "；实际：" + status.name()));
    var tools = records.tools(runId);
    switch (sample) {
      case NORMAL -> {
        var nodes = plans.find(runId);
        checks.add(
            new EvaluationCheck(
                EvaluationCheckCode.PLAN_NODE_COUNT,
                nodes.size() == 2,
                "预期计划节点数：2；实际：" + nodes.size()));
        long incomplete =
            nodes.stream().filter(n -> n.status() != PlanNodeStatus.COMPLETED).count();
        checks.add(
            new EvaluationCheck(
                EvaluationCheckCode.PLAN_NODES_COMPLETED,
                incomplete == 0,
                "未完成的计划节点数：" + incomplete));
        long successes =
            tools.stream()
                .filter(t -> InvocationStatus.COMPLETED.name().equals(t.get("STATUS")))
                .count();
        checks.add(
            new EvaluationCheck(
                EvaluationCheckCode.TOOL_SUCCESS, successes > 0, "成功工具调用数：" + successes));
      }
      case TOOL_FAILURE -> {
        long failures =
            tools.stream()
                .filter(t -> InvocationStatus.FAILED.name().equals(t.get("STATUS")))
                .count();
        checks.add(
            new EvaluationCheck(
                EvaluationCheckCode.TOOL_FAILURE, failures > 0, "失败工具调用数：" + failures));
      }
      case TOOL_LIMIT -> {
        checks.add(
            new EvaluationCheck(
                EvaluationCheckCode.TOOL_NOT_STARTED, tools.isEmpty(), "已启动工具调用数：" + tools.size()));
        int counted = records.runSnapshot(runId).tools();
        checks.add(
            new EvaluationCheck(
                EvaluationCheckCode.RUN_TOOL_COUNT_ZERO, counted == 0, "运行记录中的工具计数：" + counted));
      }
      case REPEAT_LIMIT -> {
        Object rawCause = records.run(runId).get("STOP_CAUSE");
        dev.workbench.domain.StopCause actualCause = null;
        if (rawCause instanceof String code) {
          try {
            actualCause = dev.workbench.domain.StopCause.valueOf(code);
          } catch (IllegalArgumentException ignored) {
            // 未识别的历史编码不能作为已知原因展示或通过断言。
          }
        }
        boolean stoppedByRepeat = actualCause == dev.workbench.domain.StopCause.REPEATED_TOOL_LIMIT;
        checks.add(
            new EvaluationCheck(
                EvaluationCheckCode.REPEAT_STOP_CAUSE,
                stoppedByRepeat,
                "预期停止原因：REPEATED_TOOL_LIMIT；实际："
                    + (actualCause == null ? "未记录或未知" : actualCause.name())));
        checks.add(
            new EvaluationCheck(
                EvaluationCheckCode.REPEAT_TOOL_COUNT,
                tools.size() == 2,
                "预期已启动工具调用数：2；实际：" + tools.size()));
      }
    }
    return List.copyOf(checks);
  }
}
