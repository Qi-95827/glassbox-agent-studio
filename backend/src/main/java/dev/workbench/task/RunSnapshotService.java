package dev.workbench.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.persistence.entity.RunSnapshotEntity;
import dev.workbench.persistence.repository.EventRepository;
import dev.workbench.persistence.repository.RunSnapshotRepository;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 周期保存可核验执行边界：计划版本、事件位置、工作区指纹、累计预算和节点完成数。
 *
 * <p>快照只记录已结算边界；恢复前重新计算摘要并与当前计划、运行限额和工作区比对。损坏、过旧或跨计划的快照都拒绝使用。
 */
@Service
public class RunSnapshotService {
  private final RunSnapshotRepository snapshots;
  private final EventRepository events;
  private final RunRecordService runs;
  private final WorkspaceService workspaces;
  private final ObjectMapper json;

  public RunSnapshotService(
      RunSnapshotRepository snapshots,
      EventRepository events,
      RunRecordService runs,
      WorkspaceService workspaces,
      ObjectMapper json) {
    this.snapshots = snapshots;
    this.events = events;
    this.runs = runs;
    this.workspaces = workspaces;
    this.json = json;
  }

  /** 与节点结算同一事务调用；同一事件位置只保存一次。 */
  public RunSnapshotEntity capture(
      String runId, WorkflowPlan plan, List<PlanNode> nodes, long elapsed) {
    long eventSeq = events.maxRunSeq(runId);
    if (snapshots.existsByRunIdAndEventSeq(runId, eventSeq)) {
      return snapshots.findByRunIdOrderByEventSeqAsc(runId).getLast();
    }
    var run = runs.runSnapshot(runId);
    TaskDefinition task = runs.taskDefinition(run.taskId().toString());
    String workspaceFingerprint = null;
    if (plan.scope().hasWorkspace() && task.workspaceId() != null) {
      try {
        workspaceFingerprint = workspaces.verificationFingerprint(task.workspaceId());
      } catch (RuntimeException ignored) {
        workspaceFingerprint = null;
      }
    }
    int completed =
        (int)
            nodes.stream()
                .filter(node -> node.status() == PlanNodeStatus.COMPLETED)
                .count();
    String budget = budgetSha(run);
    String boundary =
        boundary(
            plan, eventSeq, completed, run.rounds(), run.tools(), elapsed, workspaceFingerprint, budget);
    String time = Instant.now().toString();
    return snapshots.saveAndFlush(
        new RunSnapshotEntity(
            RunRecordService.id(),
            runId,
            plan.id().toString(),
            eventSeq,
            completed,
            run.rounds(),
            run.tools(),
            elapsed,
            workspaceFingerprint,
            budget,
            boundary,
            time));
  }

  /** 恢复前核对最新快照；历史运行没有快照时返回 null，沿用既有检查点校验。 */
  public Verified verifyLatest(String runId, WorkflowPlan plan) {
    List<RunSnapshotEntity> rows = snapshots.findByRunIdOrderByEventSeqAsc(runId);
    if (rows.isEmpty()) return null;
    var latest = rows.getLast();
    if (!plan.id().toString().equals(latest.getPlanVersionId()))
      throw new IllegalStateException("周期快照不属于当前计划版本。");
    var run = runs.runSnapshot(runId);
    String expectedBudget = budgetSha(run);
    if (!expectedBudget.equals(latest.getBudgetSha256()))
      throw new IllegalStateException("运行限额与快照预算不一致，不能恢复。");
    String expected =
        boundary(
            plan,
            latest.getEventSeq(),
            latest.getCompletedNodes(),
            latest.getRounds(),
            latest.getTools(),
            latest.getElapsedMs(),
            latest.getWorkspaceFingerprint(),
            latest.getBudgetSha256());
    if (!expected.equals(latest.getBoundarySha256()))
      throw new IllegalStateException("周期快照摘要不匹配，可能已损坏。");
    return new Verified(
        latest.getId(),
        latest.getEventSeq(),
        latest.getCompletedNodes(),
        latest.getRounds(),
        latest.getTools(),
        latest.getElapsedMs(),
        latest.getWorkspaceFingerprint(),
        latest.getCreatedAt());
  }

  public List<Map<String, Object>> find(String runId) {
    return snapshots.findByRunIdOrderByEventSeqAsc(runId).stream()
        .map(
            row ->
                Map.<String, Object>of(
                    "ID", row.getId(),
                    "PLAN_VERSION_ID", row.getPlanVersionId(),
                    "EVENT_SEQ", row.getEventSeq(),
                    "COMPLETED_NODES", row.getCompletedNodes(),
                    "ROUNDS", row.getRounds(),
                    "TOOLS", row.getTools(),
                    "ELAPSED_MS", row.getElapsedMs(),
                    "WORKSPACE_FINGERPRINT", row.getWorkspaceFingerprint() == null ? "" : row.getWorkspaceFingerprint(),
                    "CREATED_AT", row.getCreatedAt()))
        .toList();
  }

  private String budgetSha(RunSnapshot run) {
    return sha(
        String.join(
            "|",
            Integer.toString(run.maxRounds()),
            Integer.toString(run.maxTools()),
            Integer.toString(run.maxPerTool()),
            String.valueOf(run.maxReportedTokens()),
            String.valueOf(run.maxIdenticalTools()),
            Integer.toString(run.maxSeconds()),
            String.valueOf(run.maxAgentRounds()),
            String.valueOf(run.maxAgentTools())));
  }

  private String boundary(
      WorkflowPlan plan,
      long eventSeq,
      int completed,
      int rounds,
      int tools,
      long elapsed,
      String workspaceFingerprint,
      String budgetSha256) {
    return sha(
        String.join(
            "|",
            "run-snapshot-v1",
            plan.id().toString(),
            plan.graphSha256(),
            Long.toString(eventSeq),
            Integer.toString(completed),
            Integer.toString(rounds),
            Integer.toString(tools),
            Long.toString(elapsed),
            workspaceFingerprint == null ? "" : workspaceFingerprint,
            budgetSha256));
  }

  private static String sha(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public record Verified(
      String id,
      long eventSeq,
      int completedNodes,
      int rounds,
      int tools,
      long elapsedMs,
      String workspaceFingerprint,
      String createdAt) {}
}
