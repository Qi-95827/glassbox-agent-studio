package dev.workbench.orchestration;

import dev.workbench.domain.CheckpointSkipReason;
import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.WorkspaceReviewCheckpoint;
import dev.workbench.framework.control.RunControl;
import dev.workbench.store.RunRecordService;
import dev.workbench.workspace.WorkspaceService;

/** 运行检查点的只读指纹、恢复前置校验和固定原因记录。 */
final class RunCheckpointSupport {
  private final RunRecordService repository;
  private final WorkspaceService workspaces;

  RunCheckpointSupport(RunRecordService repository, WorkspaceService workspaces) {
    this.repository = repository;
    this.workspaces = workspaces;
  }

  /** 仅记录固定原因，不将路径、文件内容或异常详情写入用户轨迹。 */
  void recordSkipped(String runId, CheckpointSkipReason reason) {
    repository.checkpointSkipped(runId, reason);
  }

  /** 仅只读、无 Bug、未启用项目记忆的工作区可尝试形成检查点。 */
  String fingerprintIfEligible(TaskDefinition task) {
    if (task.workspaceId() == null
        || task.writeEnabled()
        || task.bugId() != null
        || task.memoryEnabled()) return null;
    try {
      return workspaces.verificationFingerprint(task.workspaceId());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 用户请求与后台启动时都要验证，避免队列等待期间工作区发生变化。 */
  void requireUnchangedWorkspace(TaskDefinition task, WorkspaceReviewCheckpoint checkpoint) {
    if (task.workspaceId() == null
        || task.writeEnabled()
        || task.bugId() != null
        || task.memoryEnabled()
        || !task.workspaceId().equals(checkpoint.workspaceId().toString()))
      throw new IllegalStateException("任务不满足只读工作区恢复条件。");
    String current = fingerprintIfEligible(task);
    if (current == null
        || !java.security.MessageDigest.isEqual(
            current.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            checkpoint
                .workspaceFingerprint()
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
      throw new WorkspaceRecoveryConflictException("工作区状态无法确认或已变化，不能使用此检查点恢复。");
  }

  /** 分析完成后按任务能力记录检查点；不能安全形成时只记录固定跳过原因。 */
  void recordAnalysisCheckpoint(
      String runId,
      TaskDefinition task,
      RunControl control,
      String agentId,
      String beforeAnalysis) {
    if (task.workspaceId() == null && task.bugId() == null) {
      String reference = repository.analysisReference(runId, agentId);
      repository.checkpoint(
          runId,
          CheckpointStage.BEFORE_REVIEW,
          control.rounds(),
          control.tools(),
          control.elapsed(),
          reference);
    } else if (task.bugId() != null) {
      recordSkipped(runId, CheckpointSkipReason.BUG_TASK);
    } else if (task.writeEnabled()) {
      recordSkipped(runId, CheckpointSkipReason.WRITE_TASK);
    } else if (task.memoryEnabled()) {
      recordSkipped(runId, CheckpointSkipReason.MEMORY_CONTEXT);
    } else if (beforeAnalysis == null) {
      recordSkipped(runId, CheckpointSkipReason.WORKSPACE_BEFORE_UNAVAILABLE);
    } else {
      String afterAnalysis = fingerprintIfEligible(task);
      if (afterAnalysis == null) {
        recordSkipped(runId, CheckpointSkipReason.WORKSPACE_AFTER_UNAVAILABLE);
      } else if (!beforeAnalysis.equals(afterAnalysis)) {
        recordSkipped(runId, CheckpointSkipReason.WORKSPACE_CHANGED);
      } else {
        String reference = repository.analysisReference(runId, agentId);
        repository.workspaceReviewCheckpoint(
            runId,
            control.rounds(),
            control.tools(),
            control.elapsed(),
            new WorkspaceReviewCheckpoint(
                java.util.UUID.fromString(task.workspaceId()), reference, beforeAnalysis));
      }
    }
  }
}
