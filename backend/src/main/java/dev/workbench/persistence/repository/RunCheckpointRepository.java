package dev.workbench.persistence.repository;

import jakarta.persistence.EntityManager;

import dev.workbench.persistence.entity.RunCheckpointEntity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedCaseInsensitiveMap;

/** AgentScope 内部状态的历史检查点元数据。动态流程边界由 WorkflowCheckpointRepository 管理。*/
@Repository
@Transactional(readOnly = true)
public class RunCheckpointRepository {
  public static final String AGENT_SCOPE_ENGINE = "AGENTSCOPE";
  private static final String CHECKPOINT_FIELDS =
      """
      c.id, c.runId, c.version, c.stage, c.rounds, c.tools, c.elapsedMs, c.toolResult,
      c.eventId, c.createdAt, c.checksum, c.engine
      """;
  private final EntityManager sessions;

  public RunCheckpointRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  public Optional<CheckpointData> findById(String id) {
    return sessions
        .createQuery(
            "select " + CHECKPOINT_FIELDS + " from RunCheckpointEntity c where c.id = :id",
            CheckpointData.class)
        .setParameter("id", id)
        .getResultList().stream().findFirst();
  }

  public List<CheckpointData> findByRunId(String runId) {
    return sessions
        .createQuery(
            "select "
                + CHECKPOINT_FIELDS
                + " from RunCheckpointEntity c where c.runId = :id order by c.createdAt",
            CheckpointData.class)
        .setParameter("id", runId)
        .getResultList();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(CheckpointData data) {
    sessions.persist(
        new RunCheckpointEntity(
            data.id(),
            data.runId(),
            data.version(),
            data.stage(),
            data.rounds(),
            data.tools(),
            data.elapsedMs(),
            data.toolResult(),
            data.eventId(),
            data.createdAt(),
            data.checksum(),
            data.engine()));
  }

  public record CheckpointData(
      String id,
      String runId,
      Integer version,
      String stage,
      Integer rounds,
      Integer tools,
      Long elapsedMs,
      String toolResult,
      Long eventId,
      String createdAt,
      String checksum,
      String engine) {
    public Map<String, Object> apiFields() {
      var fields = new LinkedCaseInsensitiveMap<Object>();
      fields.put("ID", id);
      fields.put("RUN_ID", runId);
      fields.put("VERSION", version);
      fields.put("STAGE", stage);
      fields.put("ROUNDS", rounds);
      fields.put("TOOLS", tools);
      fields.put("ELAPSED_MS", elapsedMs);
      fields.put("TOOL_RESULT", toolResult);
      fields.put("EVENT_ID", eventId);
      fields.put("CREATED_AT", createdAt);
      fields.put("CHECKSUM", checksum);
      fields.put("ENGINE", engine);
      return fields;
    }
  }
}