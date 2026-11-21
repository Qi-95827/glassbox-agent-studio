package dev.workbench.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.EventType;
import dev.workbench.domain.WorkspaceReviewCheckpoint;
import dev.workbench.persistence.repository.EventRepository;
import dev.workbench.persistence.repository.RunCheckpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 运行检查点写入；读取和校验由证据服务负责。 */
@Service
public class RunCheckpointStore {
  private final RunCheckpointRepository checkpointRows;
  private final EventRepository eventRows;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final RunEvidenceRecordService evidence;
  private final TaskRecordService tasks;

  public RunCheckpointStore(
      RunCheckpointRepository checkpointRows,
      EventRepository eventRows,
      TransactionTemplate tx,
      ObjectMapper json,
      RunEvidenceRecordService evidence,
      TaskRecordService tasks) {
    this.checkpointRows = checkpointRows;
    this.eventRows = eventRows;
    this.tx = tx;
    this.json = json;
    this.evidence = evidence;
    this.tasks = tasks;
  }

  public void checkpoint(
      String runId, CheckpointStage stage, int rounds, int tools, long elapsed, String result) {
    if (stage == CheckpointStage.BEFORE_REVIEW && result == null)
      throw new IllegalArgumentException("审查前检查点需要分析来源。");
    int version = stage == CheckpointStage.BEFORE_REVIEW ? (result.contains("|") ? 5 : 4) : 3;
    writeCheckpoint(runId, stage, rounds, tools, elapsed, result, version);
  }

  /** 创建只读工作区的审查前检查点；摘要由工作区服务在分析边界取得。 */
  public void workspaceReviewCheckpoint(
      String runId, int rounds, int tools, long elapsed, WorkspaceReviewCheckpoint payload) {
    var task = tasks.taskDefinition(evidence.runTaskId(runId));
    if (task.workspaceId() == null
        || task.writeEnabled()
        || task.bugId() != null
        || task.memoryEnabled()
        || !task.workspaceId().equals(payload.workspaceId().toString()))
      throw new IllegalArgumentException("任务不满足只读工作区恢复条件。");
    evidence.checkpointAnalysisSource(runId, payload.analysisReference());
    try {
      writeCheckpoint(
          runId,
          CheckpointStage.BEFORE_REVIEW,
          rounds,
          tools,
          elapsed,
          json.writeValueAsString(payload),
          6);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("无法序列化工作区检查点。", e);
    }
  }

  private void writeCheckpoint(
      String runId,
      CheckpointStage stage,
      int rounds,
      int tools,
      long elapsed,
      String result,
      int version) {
    tx.executeWithoutResult(
        s -> {
          evidence.event(runId, EventType.CHECKPOINT_SAVED, "已保存内部状态：" + stage);
          Long last = eventRows.maxId(runId);
          String checkpointId = RecordSupport.id();
          String created = RecordSupport.now();
          String sum =
              RecordSupport.checksum(
                  checkpointId,
                  runId,
                  String.valueOf(version),
                  "AGENTSCOPE",
                  stage.name(),
                  String.valueOf(rounds),
                  String.valueOf(tools),
                  String.valueOf(elapsed),
                  result,
                  String.valueOf(last),
                  created);
          checkpointRows.insert(
              new RunCheckpointRepository.CheckpointData(
                  checkpointId,
                  runId,
                  version,
                  stage.name(),
                  rounds,
                  tools,
                  elapsed,
                  result,
                  last,
                  created,
                  sum,
                  RunCheckpointRepository.AGENT_SCOPE_ENGINE));
        });
  }
}
