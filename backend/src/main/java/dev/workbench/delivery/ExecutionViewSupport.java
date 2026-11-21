package dev.workbench.delivery;

import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.WorkflowAttempt;
import dev.workbench.framework.workflow.plan.WorkflowCheck;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 执行视图投影共用的空值、分组与图遍历工具；不持有业务状态。 */
final class ExecutionViewSupport {
  private ExecutionViewSupport() {}

  static Map<UUID, List<WorkflowAttempt>> groupAttempts(List<WorkflowAttempt> attempts) {
    var result = new LinkedHashMap<UUID, List<WorkflowAttempt>>();
    for (var attempt : attempts)
      result.computeIfAbsent(attempt.nodeId(), key -> new ArrayList<>()).add(attempt);
    return result;
  }

  static Map<UUID, List<WorkflowCheck>> groupChecks(List<WorkflowCheck> checks) {
    var result = new LinkedHashMap<UUID, List<WorkflowCheck>>();
    for (var check : checks)
      result.computeIfAbsent(check.nodeId(), key -> new ArrayList<>()).add(check);
    return result;
  }

  static Optional<WorkflowCheck> latestCheck(List<WorkflowCheck> checks) {
    return checks.stream().max(Comparator.comparingInt(WorkflowCheck::attemptNo));
  }

  /** 从起始节点出发，收集仍待执行的下游节点；用于阻塞影响范围。 */
  static List<String> downstreamPending(List<PlanNode> nodes, UUID start) {
    var result = new LinkedHashSet<String>();
    var queue = new ArrayDeque<UUID>();
    queue.add(start);
    while (!queue.isEmpty()) {
      UUID current = queue.removeFirst();
      for (var node : nodes) {
        if (!node.dependencyIds().contains(current)) continue;
        if (node.status() == PlanNodeStatus.PENDING || node.status() == PlanNodeStatus.SKIPPED) {
          result.add(node.id().toString());
          queue.add(node.id());
        }
      }
    }
    return List.copyOf(result);
  }

  static boolean settled(PlanNodeStatus status) {
    return status == PlanNodeStatus.COMPLETED || status == PlanNodeStatus.REUSED;
  }

  static String statusText(SystemCheckStatus status) {
    return switch (status) {
      case FAILED -> "未通过";
      case BLOCKED -> "无法判定";
      case INTERRUPTED -> "已中断";
      default -> status.name();
    };
  }

  static String label(List<PlanNode> nodes, UUID id) {
    if (id == null) return null;
    return nodes.stream()
        .filter(node -> node.id().equals(id))
        .map(PlanNode::label)
        .findFirst()
        .orElse(null);
  }

  static String nullable(Object value) {
    if (value == null) return null;
    String text = value.toString();
    return text.isBlank() ? null : text;
  }

  static Integer integer(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  static Long longValue(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }

  static String safe(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  static UUID parseUuid(String value, String message) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(message, e);
    }
  }
}
