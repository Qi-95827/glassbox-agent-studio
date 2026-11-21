package dev.workbench.orchestration;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.Handoff;
import dev.workbench.domain.HandoffParty;
import dev.workbench.domain.HandoffSource;
import dev.workbench.domain.HandoffStatus;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.control.RunControl;
import dev.workbench.store.RunRecordService;
import dev.workbench.store.StopCompletion;
import java.util.List;

/** 结算未成功运行：关闭已开始的调用并按角色记录系统交接，不把未知结果写成成功。 */
final class RunFailureHandler {
  private final RunRecordService repository;
  private final RunHandoffFactory handoffs;

  RunFailureHandler(RunRecordService repository, RunHandoffFactory handoffs) {
    this.repository = repository;
    this.handoffs = handoffs;
  }

  void finishUnsuccessful(
      String runId,
      String agentId,
      boolean agentStarted,
      AgentRole activeRole,
      TaskDefinition task,
      RunControl control,
      StopCause stopCause,
      String reason) {
    RunStatus status = stopCause.status();
    InvocationStatus invocationStatus = InvocationStatus.fromRunStatus(status);
    control.finishModel(invocationStatus, null, null, reason);
    control.finishTool(invocationStatus, null, reason);
    Handoff systemHandoff = null;
    if (agentStarted) {
      HandoffParty sender =
          switch (activeRole) {
            case ANALYST -> HandoffParty.ANALYSIS_AGENT;
            case CODER -> HandoffParty.CODING_AGENT;
            case REVIEWER -> HandoffParty.REVIEW_AGENT;
          };
      systemHandoff =
          new Handoff(
              task.id().toString(),
              runId,
              agentId,
              sender,
              HandoffParty.COORDINATOR,
              task.goal(),
              handoffs.constraints(task, List.of("该交接由系统记录，未完成或未知内容不能视为成功")),
              HandoffStatus.valueOf(status.name()),
              reason,
              runId,
              repository.toolEvidence(runId),
              HandoffSource.SYSTEM,
              null);
    }
    repository.finishStoppedRun(
        new StopCompletion(
            java.util.UUID.fromString(runId),
            agentStarted ? java.util.UUID.fromString(agentId) : null,
            stopCause,
            reason,
            control.rounds(),
            control.tools(),
            control.elapsed(),
            systemHandoff));
  }
}
