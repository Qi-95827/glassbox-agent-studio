package dev.workbench.observability;

import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import java.util.Map;

/**
 * 一次运行的动态计划与调用台账汇总。
 *
 * <p>{@code nodeStatusCounts} 与 {@code checkStatusCounts} 来自持久化节点和检查记录；{@code modelCalls} 与 {@code
 * toolCalls} 只统计实际启动的调用；{@code unknownTokenCalls} 是缺少输入或输出用量的模型调用数。
 */
public record WorkflowMetrics(
    int nodes,
    Map<PlanNodeStatus, Long> nodeStatusCounts,
    int checks,
    Map<SystemCheckStatus, Long> checkStatusCounts,
    long unexecutedChecks,
    int modelCalls,
    int toolCalls,
    long unknownTokenCalls) {}
