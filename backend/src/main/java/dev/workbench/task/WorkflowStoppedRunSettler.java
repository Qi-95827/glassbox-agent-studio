package dev.workbench.task;

import dev.workbench.domain.Handoff;
import dev.workbench.domain.HandoffSource;
import dev.workbench.domain.HandoffStatus;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.persistence.repository.WorkflowExecutionRepository;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 停机收口：并行调度停止时逐个结算所有在途实例，再统一结算运行与停机交接。
 *
 * <p>收口包 G-2：非成功停机同样必须留下可读的结构化系统交接，否则玻璃盒的「交接」视图对新运行会凭空缺一条最关键的终止证据。
 * 交接与运行终态在同一事务内提交，绝不出现只有终态没有交接的半成品。
 */
final class WorkflowStoppedRunSettler {
  private final RunStateStore runs;
  private final AgentAssemblyRecordService agents;
  private final RunEvidenceRecordService evidence;
  private final WorkflowExecutionRepository records;
  private final TransactionTemplate tx;
  private final WorkflowNodeSettler settler;

  WorkflowStoppedRunSettler(
      RunStateStore runs,
      AgentAssemblyRecordService agents,
      RunEvidenceRecordService evidence,
      WorkflowExecutionRepository records,
      TransactionTemplate tx,
      WorkflowNodeSettler settler) {
    this.runs = runs;
    this.agents = agents;
    this.evidence = evidence;
    this.records = records;
    this.tx = tx;
    this.settler = settler;
  }

  /** 动态工作流的停止事实来自尝试终态和事件，无需构造旧版角色交接。 */
  void finishStopped(
      String runId,
      UUID agentId,
      StopCause cause,
      String reason,
      int rounds,
      int tools,
      long elapsed) {
    finishParallelStopped(
        runId,
        agentId == null ? List.of() : List.of(agentId),
        cause,
        reason,
        rounds,
        tools,
        elapsed);
  }

  /** 并行调度停止时逐个结算所有在途实例，再统一结算运行。 */
  void finishParallelStopped(
      String runId,
      List<UUID> agentIds,
      StopCause cause,
      String reason,
      int rounds,
      int tools,
      long elapsed) {
    finishParallelStopped(runId, agentIds, cause, reason, rounds, tools, elapsed, null);
  }

  /** 逐个结算在途实例并统一结算运行与停机交接。 */
  void finishParallelStopped(
      String runId,
      List<UUID> agentIds,
      StopCause cause,
      String reason,
      int rounds,
      int tools,
      long elapsed,
      Handoff systemHandoff) {
    if (systemHandoff != null
        && (!runId.equals(systemHandoff.runId())
            || systemHandoff.source() != HandoffSource.SYSTEM
            || systemHandoff.status() != HandoffStatus.valueOf(cause.status().name())
            || !java.util.Objects.equals(reason, systemHandoff.summary())))
      throw new IllegalArgumentException("系统停机交接与停止事实不匹配。");
    tx.executeWithoutResult(
        ignored -> {
          for (UUID agentId : agentIds == null ? List.<UUID>of() : agentIds) {
            if (agentId == null) continue;
            if (!records.isRunningAttempt(runId, agentId.toString())) continue;

            // 检查失败等阻塞原因没有对应的调用终态；已认领但尚未发起模型/工具调用的实例按失败结束。
            InvocationStatus status =
                switch (cause.status()) {
                  case COMPLETED -> InvocationStatus.COMPLETED;
                  case CANCELLED -> InvocationStatus.CANCELLED;
                  case LIMITED -> InvocationStatus.LIMITED;
                  case INTERRUPTED -> InvocationStatus.INTERRUPTED;
                  default -> InvocationStatus.FAILED;
                };
            agents.finishAgent(agentId.toString(), status, null);
            settler.recordAttemptStop(agentId.toString(), cause);
          }
          // 停止发生在检查结算前时，保留检查记录并标明结果未知，不能当作通过。
          records.interruptChecks(runId, dev.workbench.domain.WorkbenchTime.now());

          runs.settleStoppedRun(runId, cause, reason, rounds, tools, elapsed);
          if (systemHandoff != null)
            evidence.handoff(dev.workbench.domain.HandoffDirection.RESULT, systemHandoff);
        });
  }
}
