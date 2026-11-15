package dev.workbench.task;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.HandoffDirection;
import dev.workbench.domain.HandoffParty;
import dev.workbench.domain.HandoffStatus;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.orchestration.Handoff;
import dev.workbench.orchestration.plan.PlanNodeStatus;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 新交接写入前校验运行归属、角色方向和实际事件引用；不代替租户授权。 */
@Component
public class HandoffValidator {
  private final JdbcTemplate db;

  public HandoffValidator(JdbcTemplate db) {
    this.db = db;
  }

  public void validate(HandoffDirection direction, Handoff h) {
    if (direction == null
        || h == null
        || h.handoffId() == null
        || (!Integer.valueOf(1).equals(h.schemaVersion())
            && !Integer.valueOf(2).equals(h.schemaVersion()))
        || (Integer.valueOf(1).equals(h.schemaVersion()) && h.testCheckIds() != null)
        || (Integer.valueOf(2).equals(h.schemaVersion()) && h.testCheckIds() == null)
        || h.taskId() == null
        || h.runId() == null
        || h.agentId() == null
        || h.goal() == null
        || h.goal().isBlank()
        || h.summary() == null
        || h.summary().isBlank()
        || h.sender() == null
        || h.receiver() == null
        || h.status() == null
        || h.source() == null) throw new IllegalArgumentException("交接字段或契约版本无效。");
    UUID.fromString(h.taskId());
    UUID.fromString(h.runId());
    UUID.fromString(h.agentId());
    if (h.recipientAgentId() != null) UUID.fromString(h.recipientAgentId());
    if (!validDirection(direction, h)) throw new IllegalArgumentException("交接方向、角色或状态无效。");
    var runRows = db.queryForList("SELECT task_id,parent_id FROM runs WHERE id=?", h.runId());
    if (runRows.size() != 1 || !h.taskId().equals(runRows.getFirst().get("TASK_ID")))
      throw new IllegalArgumentException("交接不属于指定任务运行。");
    String parent = (String) runRows.getFirst().get("PARENT_ID");
    boolean ancestorEvidence = false;
    if (h.evidenceRunId() != null) {
      UUID.fromString(h.evidenceRunId());
      if (!h.runId().equals(h.evidenceRunId()) && !h.evidenceRunId().equals(parent)) {
        ancestorEvidence = isVerifiedAncestor(h.runId(), h.taskId(), h.evidenceRunId());
        if (!ancestorEvidence) throw new IllegalArgumentException("证据来源不是经过复用验证的运行祖先。");
      }
    } else if (!h.evidenceEventIds().isEmpty()) {
      throw new IllegalArgumentException("交接证据缺少来源运行。");
    }
    if (new HashSet<>(h.evidenceEventIds()).size() != h.evidenceEventIds().size())
      throw new IllegalArgumentException("交接证据不能重复。");
    for (Long eventId : h.evidenceEventIds()) {
      if (eventId == null
          || eventId <= 0
          || count(
                  "SELECT COUNT(*) FROM events WHERE id=? AND run_id=? AND"
                      + " event_type='TOOL_FINISHED'",
                  eventId,
                  h.evidenceRunId())
              != 1) throw new IllegalArgumentException("交接引用的工具证据不存在或不属于来源运行。");
    }
    if (h.testCheckIds() != null) {
      if (h.testCheckIds().size() > 20
          || new HashSet<>(h.testCheckIds()).size() != h.testCheckIds().size())
        throw new IllegalArgumentException("交接测试证据过多或重复。");
      for (UUID checkId : h.testCheckIds()) {
        if (checkId == null
            || count(
                    "SELECT COUNT(*) FROM bug_checks b JOIN runs r ON r.id=b.run_id JOIN tasks t ON"
                        + " t.id=r.task_id WHERE b.id=? AND b.run_id=? AND r.task_id=? AND"
                        + " b.bug_id=t.bug_id AND b.workspace_id=t.workspace_id AND b.kind IN"
                        + " ('REPRODUCE','REGRESSION')",
                    checkId.toString(),
                    h.runId(),
                    h.taskId())
                != 1) throw new IllegalArgumentException("交接引用的测试证据不存在或归属不匹配。");
      }
    }
    AgentRole role =
        direction == HandoffDirection.DELEGATION ? role(h.receiver()) : role(h.sender());
    int current =
        count(
            "SELECT COUNT(*) FROM agent_invocations WHERE id=? AND run_id=? AND role_name=?",
            h.agentId(),
            h.runId(),
            role.displayName());
    int previous =
        h.evidenceRunId() != null && (h.evidenceRunId().equals(parent) || ancestorEvidence)
            ? count(
                "SELECT COUNT(*) FROM agent_invocations WHERE id=? AND run_id=? AND role_name=?",
                h.agentId(),
                h.evidenceRunId(),
                role.displayName())
            : 0;
    if (current + previous != 1) throw new IllegalArgumentException("交接来源 Agent 与运行或角色不匹配。");
  }

  /** 远祖证据仅允许当前计划实际复用过的、同任务中的已完成分析来源。 */
  private boolean isVerifiedAncestor(String runId, String taskId, String evidenceRunId) {
    String current = runId;
    var visited = new HashSet<String>();
    while (current != null) {
      if (!visited.add(current) || visited.size() > 1000) return false;
      var rows = db.queryForList("SELECT task_id,parent_id FROM runs WHERE id=?", current);
      if (rows.size() != 1 || !taskId.equals(rows.getFirst().get("TASK_ID"))) return false;
      current = (String) rows.getFirst().get("PARENT_ID");
      if (evidenceRunId.equals(current)) {
        int source =
            count(
                "SELECT COUNT(*) FROM plan_nodes p JOIN agent_invocations a"
                    + " ON a.id=p.source_agent_id JOIN runs r ON r.id=a.run_id"
                    + " WHERE p.run_id=? AND p.role_code=? AND p.status=? AND a.run_id=?"
                    + " AND a.role_name=? AND a.status=? AND r.task_id=?",
                runId,
                AgentRole.ANALYST.name(),
                PlanNodeStatus.REUSED.name(),
                evidenceRunId,
                AgentRole.ANALYST.displayName(),
                InvocationStatus.COMPLETED.name(),
                taskId);
        return source == 1;
      }
    }
    return false;
  }

  private boolean validDirection(HandoffDirection direction, Handoff h) {
    return switch (direction) {
      case DELEGATION ->
          h.sender() == HandoffParty.COORDINATOR
              && h.receiver() == HandoffParty.ANALYSIS_AGENT
              && h.status() == HandoffStatus.ASSIGNED
              && h.agentId().equals(h.recipientAgentId());
      case RESULT ->
          h.sender() != HandoffParty.COORDINATOR
              && h.receiver() == HandoffParty.COORDINATOR
              && h.status() != HandoffStatus.ASSIGNED
              && h.recipientAgentId() == null
              && (h.status() == HandoffStatus.COMPLETED
                  || h.source() == dev.workbench.domain.HandoffSource.SYSTEM);
      case AGENT_TRANSFER ->
          ((h.sender() == HandoffParty.ANALYSIS_AGENT
                      && (h.receiver() == HandoffParty.CODING_AGENT
                          || h.receiver() == HandoffParty.REVIEW_AGENT))
                  || (h.sender() == HandoffParty.CODING_AGENT
                      && h.receiver() == HandoffParty.REVIEW_AGENT))
              && h.status() == HandoffStatus.COMPLETED
              && h.recipientAgentId() != null
              && !h.recipientAgentId().equals(h.agentId());
    };
  }

  private AgentRole role(HandoffParty party) {
    return switch (party) {
      case ANALYSIS_AGENT -> AgentRole.ANALYST;
      case CODING_AGENT -> AgentRole.CODER;
      case REVIEW_AGENT -> AgentRole.REVIEWER;
      case COORDINATOR -> throw new IllegalArgumentException("协调者不是 Agent 实例。");
    };
  }

  private int count(String sql, Object... args) {
    Integer value = db.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }
}
