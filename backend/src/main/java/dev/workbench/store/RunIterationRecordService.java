package dev.workbench.store;

import dev.workbench.persistence.entity.RunIterationEntity;
import dev.workbench.persistence.repository.RunIterationRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同任务迭代请求的读写入口。
 *
 * <p>调改请求按运行标识一对一保存，供三处消费：轮次列表的 {@code feedbackSummary}、本轮 Agent 上下文里的调改要求，
 * 以及审计追溯。写入只发生在新建运行的同一事务内，源运行的记录不会被改写。
 */
@Service
public class RunIterationRecordService {
  private final RunIterationRepository rows;

  public RunIterationRecordService(RunIterationRepository rows) {
    this.rows = rows;
  }

  /** 一轮调改请求的只读投影；正文已脱敏并按上限截断。 */
  public record Note(
      String runId,
      int roundIndex,
      String feedback,
      String previousSummary,
      String targetNodeKey,
      String modelMode,
      String modelConnectionId,
      String createdAt) {}

  @Transactional
  public void record(
      String runId,
      String taskId,
      String parentRunId,
      int roundIndex,
      String targetNodeKey,
      String modelMode,
      String modelConnectionId,
      String feedback,
      String previousSummary,
      String createdAt) {
    rows.save(
        new RunIterationEntity(
            runId,
            taskId,
            parentRunId,
            roundIndex,
            targetNodeKey,
            modelMode,
            modelConnectionId,
            feedback,
            previousSummary,
            createdAt));
  }

  @Transactional(readOnly = true)
  public Optional<Note> note(String runId) {
    if (runId == null || runId.isBlank()) return Optional.empty();
    return rows.findByRunId(runId).map(RunIterationRecordService::toNote);
  }

  @Transactional(readOnly = true)
  public List<Note> notes(String taskId) {
    if (taskId == null || taskId.isBlank()) return List.of();
    return rows.findByTaskIdOrderByRoundIndexAsc(taskId).stream()
        .map(RunIterationRecordService::toNote)
        .toList();
  }

  @Transactional(readOnly = true)
  public int latestRoundIndex(String taskId) {
    return rows.findFirstByTaskIdOrderByRoundIndexDesc(taskId).map(row -> row.getRoundIndex()).orElse(1);
  }

  private static Note toNote(RunIterationEntity row) {
    return new Note(
        row.getRunId(),
        row.getRoundIndex(),
        row.getFeedback(),
        row.getPreviousSummary(),
        row.getTargetNodeKey(),
        row.getModelMode(),
        row.getModelConnectionId(),
        row.getCreatedAt());
  }
}
