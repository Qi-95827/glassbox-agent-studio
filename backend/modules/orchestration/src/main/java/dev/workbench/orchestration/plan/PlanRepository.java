package dev.workbench.orchestration.plan;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.InvocationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 固定顺序协作计划的持久化操作；写操作由调用方提供事务。 */
@Repository
public class PlanRepository {
  private final JdbcTemplate db;
  private final PlanFactory factory;

  public PlanRepository(JdbcTemplate db, PlanFactory factory) {
    this.db = db;
    this.factory = factory;
  }

  /** 根据服务端的写入授权创建固定顺序计划。 */
  public void initialize(String runId, boolean includeCoder) {
    var plan = factory.create(includeCoder);
    int sequence = 0;
    for (var node : plan.nodes()) {
      AgentRole prerequisite =
          node.dependencies().isEmpty() ? null : node.dependencies().getFirst();
      insert(runId, ++sequence, node.role(), prerequisite);
    }
  }

  private void insert(String runId, int sequence, AgentRole role, AgentRole prerequisite) {
    db.update(
        "INSERT INTO plan_nodes(id,run_id,sequence_no,role_code,prerequisite_role,status)"
            + " VALUES(?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        runId,
        sequence,
        role.name(),
        prerequisite == null ? null : prerequisite.name(),
        PlanNodeStatus.PENDING.name());
  }

  /** 使用条件更新阻止重复启动或跳过尚未完成的前置步骤。 */
  public void start(String runId, String agentId, AgentRole role, String time) {
    int changed =
        db.update(
            "UPDATE plan_nodes SET status=?,agent_id=?,started_at=? WHERE run_id=? AND role_code=?"
                + " AND status=? AND (prerequisite_role IS NULL OR EXISTS (SELECT 1 FROM plan_nodes"
                + " p WHERE p.run_id=? AND p.role_code=plan_nodes.prerequisite_role AND"
                + " p.status IN (?,?)))",
            PlanNodeStatus.RUNNING.name(),
            agentId,
            time,
            runId,
            role.name(),
            PlanNodeStatus.PENDING.name(),
            runId,
            PlanNodeStatus.COMPLETED.name(),
            PlanNodeStatus.REUSED.name());
    if (changed != 1) throw new IllegalStateException("步骤不存在、已启动或前置步骤尚未完成。");
  }

  /** 复用已校验的历史分析，不伪造本运行中的 Agent 调用。 */
  public void reuseAnalysis(String runId, String sourceAgentId, String time) {
    int changed =
        db.update(
            "UPDATE plan_nodes SET status=?,source_agent_id=?,finished_at=? WHERE run_id=? AND"
                + " role_code=? AND status=?",
            PlanNodeStatus.REUSED.name(),
            sourceAgentId,
            time,
            runId,
            AgentRole.ANALYST.name(),
            PlanNodeStatus.PENDING.name());
    if (changed != 1) throw new IllegalStateException("分析步骤不能复用。");
  }

  /** 调用与步骤共享终态；调用方应确保与 Agent 调用更新处于同一事务。 */
  public void finish(String agentId, InvocationStatus status, String time) {
    if (status == InvocationStatus.RUNNING) throw new IllegalArgumentException("调用尚未结束。");
    int changed =
        db.update(
            "UPDATE plan_nodes SET status=?,finished_at=? WHERE agent_id=? AND status=?",
            PlanNodeStatus.valueOf(status.name()).name(),
            time,
            agentId,
            PlanNodeStatus.RUNNING.name());
    if (changed != 1) throw new IllegalStateException("步骤不存在或已经结束。");
  }

  /** 终止运行时保留已结束步骤；在途步骤结果未知，未启动的步骤标记跳过。 */
  public void settle(String runId, String time) {
    db.update(
        "UPDATE plan_nodes SET status=?,finished_at=? WHERE run_id=? AND status=?",
        PlanNodeStatus.INTERRUPTED.name(),
        time,
        runId,
        PlanNodeStatus.RUNNING.name());
    db.update(
        "UPDATE plan_nodes SET status=?,finished_at=? WHERE run_id=? AND status=?",
        PlanNodeStatus.SKIPPED.name(),
        time,
        runId,
        PlanNodeStatus.PENDING.name());
  }

  public List<PlanNode> find(String runId) {
    return db.query(
        "SELECT * FROM plan_nodes WHERE run_id=? ORDER BY sequence_no",
        (row, index) -> {
          String prerequisite = row.getString("prerequisite_role");
          String agent = row.getString("agent_id");
          String sourceAgent = row.getString("source_agent_id");
          String started = row.getString("started_at");
          String finished = row.getString("finished_at");
          return new PlanNode(
              UUID.fromString(row.getString("id")),
              UUID.fromString(row.getString("run_id")),
              row.getInt("sequence_no"),
              AgentRole.valueOf(row.getString("role_code")),
              prerequisite == null ? null : AgentRole.valueOf(prerequisite),
              PlanNodeStatus.valueOf(row.getString("status")),
              agent == null ? null : UUID.fromString(agent),
              sourceAgent == null ? null : UUID.fromString(sourceAgent),
              started == null ? null : Instant.parse(started),
              finished == null ? null : Instant.parse(finished));
        },
        runId);
  }
}
