package dev.workbench.evaluation;

import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.PlanService;
import dev.workbench.framework.workflow.plan.WorkflowAttempt;
import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import dev.workbench.task.RunRecordService;
import dev.workbench.task.WorkflowExecutionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 使用系统持久化记录展开固定评测断言，不信任模型自述。 */
@Component
public class SeedCaseEvaluator {
  private final RunRecordService records;
  private final PlanService plans;
  private final WorkflowExecutionService workflows;

  public SeedCaseEvaluator(
      RunRecordService records, PlanService plans, WorkflowExecutionService workflows) {
    this.records = records;
    this.plans = plans;
    this.workflows = workflows;
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
      case DYNAMIC_DUAL_ANALYSIS, DYNAMIC_SHARED_BUDGET ->
          addDynamicChecks(sample, runId, tools.size(), checks);
      case DYNAMIC_CHECK_PASS, DYNAMIC_CHECK_BLOCKED -> addCheckCaseChecks(sample, runId, checks);
    }
    return List.copyOf(checks);
  }

  /** 检查成功与安全阻塞样本：只依据已落库的检查、节点和调用记录断言。 */
  private void addCheckCaseChecks(SeedCase sample, String runId, List<EvaluationCheck> checks) {
    var plan = plans.workflowPlan(runId);
    var nodes = plans.find(runId);
    var checkRecords = workflows.workflowChecks(runId);
    var models = records.models(runId);
    var tools = records.tools(runId);
    var agents = records.agents(runId);
    int expectedNodes = sample == SeedCase.DYNAMIC_CHECK_PASS ? 3 : 5;
    boolean saved =
        plan != null
            && sample.workflowTemplate().equals(plan.templateId())
            && plan.templateVersion() == sample.templateVersion()
            && plan.approvedAt() != null
            && nodes.size() == expectedNodes;
    checks.add(
        new EvaluationCheck(
            EvaluationCheckCode.DYNAMIC_SAVED_PLAN,
            saved,
            "模板 "
                + (plan == null ? "缺失" : plan.templateId() + " v" + plan.templateVersion())
                + "，节点数 "
                + nodes.size()
                + "。"));
    boolean checkStatus =
        checkRecords.size() == 1
            && (sample == SeedCase.DYNAMIC_CHECK_PASS
                ? checkRecords.getFirst().status()
                    == dev.workbench.framework.workflow.check.SystemCheckStatus.PASSED
                : checkRecords.getFirst().status()
                    == dev.workbench.framework.workflow.check.SystemCheckStatus.BLOCKED);
    checks.add(
        new EvaluationCheck(
            EvaluationCheckCode.DYNAMIC_CHECK_STATUS,
            checkStatus,
            "检查记录 "
                + checkRecords.stream()
                    .map(check -> check.check() + "=" + check.status())
                    .toList()));
    boolean modelCalls = models.size() == (sample == SeedCase.DYNAMIC_CHECK_PASS ? 3 : 0);
    checks.add(
        new EvaluationCheck(
            EvaluationCheckCode.DYNAMIC_MODEL_CALLS,
            modelCalls,
            "实际模型调用 " + models.size() + " 次；检查节点不应产生模型调用。"));
    if (sample == SeedCase.DYNAMIC_CHECK_PASS) {
      var check = checkRecords.getFirst();
      boolean evidence =
          check.inputs().size() == 1
              && check.inputs().getFirst().sourceAttemptId() != null
              && check.summary() != null
              && !check.summary().isBlank()
              && tools.size() == 1
              && nodes.stream().allMatch(node -> node.status() == PlanNodeStatus.COMPLETED);
      checks.add(
          new EvaluationCheck(
              EvaluationCheckCode.DYNAMIC_CHECK_EVIDENCE,
              evidence,
              "检查输入来源、摘要、工具调用和节点完成状态："
                  + check.inputs().size()
                  + " 个输入，工具 "
                  + tools.size()
                  + " 次。"));
    } else {
      Object cause = records.run(runId).get("STOP_CAUSE");
      boolean blocked =
          "CHECK_FAILED".equals(String.valueOf(cause))
              && agents.isEmpty()
              && models.isEmpty()
              && tools.isEmpty()
              && nodes.getFirst().status() == PlanNodeStatus.FAILED
              && nodes.stream().skip(1).allMatch(node -> node.status() == PlanNodeStatus.SKIPPED);
      checks.add(
          new EvaluationCheck(
              EvaluationCheckCode.DYNAMIC_CHECK_BLOCKED,
              blocked,
              "停止原因 "
                  + (cause == null ? "未知" : cause)
                  + "；Agent "
                  + agents.size()
                  + "，模型 "
                  + models.size()
                  + "，工具 "
                  + tools.size()
                  + "。"));
      checks.add(
          new EvaluationCheck(
              EvaluationCheckCode.TOOL_NOT_STARTED,
              tools.isEmpty(),
              "阻塞样本不应启动工具，实际 " + tools.size() + " 次。"));
    }
  }

  private void addDynamicChecks(
      SeedCase sample, String runId, int toolCalls, List<EvaluationCheck> checks) {
    var plan = plans.workflowPlan(runId);
    var nodes = plans.find(runId);
    var attempts = workflows.workflowAttempts(runId);
    boolean saved =
        plan != null
            && sample.workflowTemplate().equals(plan.templateId())
            && plan.templateVersion() == 1
            && plan.approvedAt() != null
            && nodes.size() == 3
            && nodes.stream().allMatch(node -> plan.id().equals(node.planVersionId()))
            && !attempts.isEmpty()
            && attempts.stream()
                .noneMatch(attempt -> attempt.startedAt().isBefore(plan.approvedAt()));
    checks.add(
        new EvaluationCheck(
            EvaluationCheckCode.DYNAMIC_SAVED_PLAN,
            saved,
            "已保存动态计划节点数 " + nodes.size() + "，核对模板版本及尝试启动时间。"));
    var requirements = findNode(nodes, "requirements");
    var constraints = findNode(nodes, "constraints");
    var summary = findNode(nodes, "synthesis");
    var first = completedAttempt(requirements, attempts);
    var second = completedAttempt(constraints, attempts);
    boolean independent =
        first != null
            && second != null
            && requirements.definitionRef() != null
            && requirements.definitionRef().equals(constraints.definitionRef())
            && !first.agentId().equals(second.agentId())
            && !first.id().equals(second.id());
    checks.add(
        new EvaluationCheck(
            EvaluationCheckCode.DYNAMIC_INDEPENDENT_INSTANCES,
            independent,
            "两个上游应使用同一版本定义，并分别形成已完成的尝试和实例。"));

    var run = records.runSnapshot(runId);
    var calls = records.models(runId);
    boolean correctCalls =
        calls.size() == sample.maxRounds()
            && run.rounds() == calls.size()
            && calls.stream()
                .allMatch(call -> InvocationStatus.COMPLETED.name().equals(call.get("STATUS")));
    checks.add(
        new EvaluationCheck(
            EvaluationCheckCode.DYNAMIC_MODEL_CALLS,
            correctCalls,
            "预期模型调用 "
                + sample.maxRounds()
                + " 次，实际 "
                + calls.size()
                + " 次，运行轮次 "
                + run.rounds()
                + "。"));
    checks.add(
        new EvaluationCheck(
            EvaluationCheckCode.TOOL_NOT_STARTED,
            toolCalls == 0,
            "纯文本工作流的工具调用数 " + toolCalls + "。"));

    if (sample == SeedCase.DYNAMIC_DUAL_ANALYSIS) {
      var receipts = workflows.workflowHandoffs(runId);
      var receiver = completedAttempt(summary, attempts);
      boolean joined =
          independent
              && receiver != null
              && receipts.size() == 2
              && Set.copyOf(summary.dependencyIds()).equals(Set.of(first.nodeId(), second.nodeId()))
              && received(receipts, first, receiver)
              && received(receipts, second, receiver);
      checks.add(
          new EvaluationCheck(
              EvaluationCheckCode.DYNAMIC_MERGE_RECEIPTS,
              joined,
              "汇合步骤应完成，并保存来自两个上游实际实例的回执。回执数 " + receipts.size() + "。"));
    } else {
      Object cause = records.run(runId).get("STOP_CAUSE");
      boolean stopped =
          independent
              && summary != null
              && summary.status() == PlanNodeStatus.LIMITED
              && StopCause.MODEL_ROUND_LIMIT.name().equals(cause)
              && calls.stream()
                  .noneMatch(
                      call ->
                          summary.agentId() != null
                              && summary.agentId().toString().equals(call.get("AGENT_ID")));
      checks.add(
          new EvaluationCheck(
              EvaluationCheckCode.DYNAMIC_BUDGET_STOP,
              stopped,
              "共享预算耗尽后汇总节点应超限且无模型调用。已记录原因 " + (cause == null ? "未知" : cause) + "。"));
    }
  }

  private static PlanNode findNode(List<PlanNode> nodes, String key) {
    return nodes.stream().filter(node -> key.equals(node.key())).findFirst().orElse(null);
  }

  private static WorkflowAttempt completedAttempt(PlanNode node, List<WorkflowAttempt> attempts) {
    if (node == null || node.status() != PlanNodeStatus.COMPLETED || node.agentId() == null)
      return null;
    return attempts.stream()
        .filter(
            attempt ->
                node.id().equals(attempt.nodeId())
                    && node.agentId().equals(attempt.agentId())
                    && attempt.status() == InvocationStatus.COMPLETED)
        .findFirst()
        .orElse(null);
  }

  private static boolean received(
      List<WorkflowHandoff> receipts, WorkflowAttempt sender, WorkflowAttempt receiver) {
    return receipts.stream()
        .anyMatch(
            receipt ->
                receipt.senderAttemptId().equals(sender.id())
                    && receipt.senderNodeId().equals(sender.nodeId())
                    && receipt.senderAgentId().equals(sender.agentId())
                    && receipt.receiverAttemptId().equals(receiver.id())
                    && receipt.receiverNodeId().equals(receiver.nodeId())
                    && receipt.receiverAgentId().equals(receiver.agentId()));
  }
}
