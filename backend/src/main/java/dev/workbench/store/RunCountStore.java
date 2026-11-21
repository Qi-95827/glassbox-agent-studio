package dev.workbench.store;

import dev.workbench.domain.RunStatus;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.control.RunControlStore;
import dev.workbench.persistence.repository.InvocationRepository;
import dev.workbench.persistence.repository.RunCounts;
import dev.workbench.persistence.repository.RunRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

/** 运行计数和状态概况；按当前运行及恢复祖先累计。 */
@Service
public class RunCountStore {
  private final RunRepository runRows;
  private final InvocationRepository invocationRows;

  public RunCountStore(RunRepository runRows, InvocationRepository invocationRows) {
    this.runRows = runRows;
    this.invocationRows = invocationRows;
  }

  /** 仅用于当前实例限额；已开始调用无论最终状态都计数。 */
  public int countAgentModelCalls(String agentId) {
    return invocationRows.countAgentModelCalls(agentId);
  }

  public int countAgentToolCalls(String agentId) {
    return invocationRows.countAgentToolCalls(agentId);
  }

  /** 累计节点的全部尝试，所有已记录启动的调用占用预算。 */
  public int countNodeModelCalls(String runId, UUID nodeId) {
    return invocationRows.countNodeModelCalls(runId, nodeId.toString());
  }

  public int countNodeToolCalls(String runId, UUID nodeId) {
    return invocationRows.countNodeToolCalls(runId, nodeId.toString());
  }

  /** 按记录顺序倒查当前运行及恢复祖先，遇到不同调用立即停止。 */
  public int consecutiveToolAttempts(String runId, ToolKind kind, String fingerprint, int limit) {
    int count = 0;
    var visited = new HashSet<String>();
    String current = runId;
    while (current != null && count < limit) {
      if (!visited.add(current)) throw new IllegalStateException("运行恢复关系存在循环。");
      var rows = invocationRows.findRecentFingerprints(current, limit - count);
      for (var row : rows) {
        if (!kind.name().equals(row.kind()) || !fingerprint.equals(row.fingerprint())) return count;
        if (++count >= limit) return count;
      }
      UUID parent = snapshot(current).parentId();
      current = parent == null ? null : parent.toString();
    }
    return count;
  }

  /** 已报告用量和未知用量次数；未知不能按零解释。 */
  public RunControlStore.ReportedTokenUsage reportedTokens(String runId) {
    long known = 0, unknown = 0;
    var visited = new HashSet<String>();
    String current = runId;
    while (current != null) {
      if (!visited.add(current)) throw new IllegalStateException("运行恢复关系存在循环。");
      var usage = invocationRows.summarizeTokens(current);
      known += usage.known();
      unknown += usage.unknownCalls();
      UUID parent = snapshot(current).parentId();
      current = parent == null ? null : parent.toString();
    }
    return new RunControlStore.ReportedTokenUsage(known, unknown);
  }

  /** 包含本运行和恢复祖先中的已启动调用，避免恢复时同种工具限额归零。 */
  public int countToolCalls(String runId, ToolKind kind) {
    int count = 0;
    var visited = new HashSet<String>();
    String current = runId;
    while (current != null) {
      if (!visited.add(current)) throw new IllegalStateException("运行恢复关系存在循环。");
      count += invocationRows.countToolCalls(current, kind.recordName());
      UUID parent = snapshot(current).parentId();
      current = parent == null ? null : parent.toString();
    }
    return count;
  }

  public Map<String, Object> overview() {
    return runRows.countByStatus().apiFields();
  }

  /** 运行概况按当前会话可读取的任务过滤，不返回全库状态计数。 */
  public Map<String, Object> overview(List<String> taskIds) {
    if (taskIds == null || taskIds.isEmpty())
      return new RunCounts(0L, 0L, 0L).apiFields();
    return runRows
        .countByStatusForTasks(taskIds, RunStatus.RUNNING.name(), RunStatus.COMPLETED.name())
        .apiFields();
  }

  private dev.workbench.domain.RunSnapshot snapshot(String id) {
    return runRows
        .findRunById(id)
        .orElseThrow(() -> new EmptyResultDataAccessException(1))
        .snapshot();
  }
}
