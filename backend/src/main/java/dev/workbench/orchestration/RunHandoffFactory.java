package dev.workbench.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.bug.BugService;
import dev.workbench.domain.Handoff;
import dev.workbench.domain.HandoffParty;
import dev.workbench.domain.HandoffSource;
import dev.workbench.domain.HandoffStatus;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.context.AgentInput;
import dev.workbench.store.RunEvidenceRecordService;
import java.util.List;

/** 组装协调者、分析与审查之间的版本化交接，保持持久化内容与发送给 Agent 的内容一致。 */
final class RunHandoffFactory {
  private final RunEvidenceRecordService evidence;
  private final ObjectMapper json;

  RunHandoffFactory(RunEvidenceRecordService evidence, ObjectMapper json) {
    this.evidence = evidence;
    this.json = json;
  }

  List<String> constraints(TaskDefinition task, List<String> fixed) {
    var all = new java.util.ArrayList<>(fixed);
    all.add("用户选择的任务入口：" + task.entryKind().label() + "；入口类型不构成额外授权或已验证事实。");
    if (!task.fileScopes().isEmpty()) {
      try {
        all.add("用户授权文件路径 JSON：" + json.writeValueAsString(task.fileScopes()));
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("无法装配授权文件路径。", e);
      }
    }
    task.constraints().forEach(value -> all.add("用户约束：" + value));
    task.acceptanceCriteria().forEach(value -> all.add("待验证验收条件：" + value));
    return List.copyOf(all);
  }

  /** 发送给审查 Agent 的内容与持久化交接一致，便于追踪信息来源。 */
  AgentInput input(Handoff handoff) {
    try {
      var sources = new java.util.ArrayList<AgentInput.Source>();
      sources.add(
          new AgentInput.Source(
              AgentInput.Kind.HANDOFF, handoff.handoffId().toString(), handoff.schemaVersion()));
      if (handoff.testCheckIds() != null) {
        for (var check : handoff.testCheckIds())
          sources.add(new AgentInput.Source(AgentInput.Kind.BUG_CHECK, check.toString(), null));
      }
      return new AgentInput(json.writeValueAsString(handoff), sources);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("无法序列化 Agent 交接信息。", e);
    }
  }

  Handoff delegation(RunSnapshot run, TaskDefinition task, String agentId) {
    return new Handoff(
        task.id().toString(),
        run.id().toString(),
        agentId,
        HandoffParty.COORDINATOR,
        HandoffParty.ANALYSIS_AGENT,
        task.goal(),
        constraints(
            task,
            task.workspaceId() == null
                ? List.of("仅使用模拟工具", "遵守运行限制")
                : List.of("仅允许访问用户指定的工作区文件", "分析 Agent 仅允许只读", "遵守运行限制")),
        HandoffStatus.ASSIGNED,
        task.workspaceId() == null ? "分析模拟工具结果并交付建议" : "在授权范围内分析工作区文件并交付建议",
        null,
        List.of(),
        HandoffSource.COORDINATOR,
        agentId);
  }

  Handoff analysisResult(
      RunSnapshot run,
      TaskDefinition task,
      String agentId,
      String answer,
      String evidenceRun,
      BugService.Check initial) {
    HandoffSource source = source(task);
    return attachTestChecks(
        new Handoff(
            task.id().toString(),
            run.id().toString(),
            agentId,
            HandoffParty.ANALYSIS_AGENT,
            HandoffParty.COORDINATOR,
            task.goal(),
            List.of("模拟工具结果不代表真实测试", "模型回答需要独立验证"),
            HandoffStatus.COMPLETED,
            answer,
            evidenceRun,
            evidence.toolEvidence(evidenceRun),
            source,
            null),
        initial);
  }

  Handoff codingInput(
      RunSnapshot run,
      TaskDefinition task,
      String analystAgentId,
      String analysisAnswer,
      String evidenceRun,
      String coderId,
      BugService.Check initial) {
    Handoff codingInput =
        new Handoff(
            task.id().toString(),
            run.id().toString(),
            analystAgentId,
            HandoffParty.ANALYSIS_AGENT,
            HandoffParty.CODING_AGENT,
            task.goal(),
            constraints(
                task,
                List.of("仅可修改用户授权的文件", "分析结论需要结合真实代码与测试验证")),
            HandoffStatus.COMPLETED,
            analysisAnswer,
            evidenceRun,
            evidence.toolEvidence(evidenceRun),
            source(task),
            coderId);
    return attachTestChecks(codingInput, initial);
  }

  Handoff reviewTransfer(
      RunSnapshot run,
      TaskDefinition task,
      String agentId,
      HandoffParty upstreamParty,
      String answer,
      String analysisAnswer,
      String evidenceRun,
      String reviewerId,
      String verification,
      BugService.Check initial,
      BugService.Check regression) {
    Handoff transfer =
        new Handoff(
            task.id().toString(),
            run.id().toString(),
            agentId,
            upstreamParty,
            HandoffParty.REVIEW_AGENT,
            task.goal(),
            constraints(
                task,
                List.of(
                    "模拟检查结果不能当作真实代码证据", "模型结论需要独立验证", verification)),
            HandoffStatus.COMPLETED,
            task.writeEnabled() ? "分析结论：\n" + analysisAnswer + "\n开发结论：\n" + answer : answer,
            evidenceRun,
            evidence.toolEvidence(evidenceRun),
            source(task),
            reviewerId);
    return attachTestChecks(transfer, initial, regression);
  }

  /** 仅将本次运行实际生成的检测记录加入版本化交接，写入时还需仓储校验归属。 */
  Handoff attachTestChecks(Handoff handoff, BugService.Check... checks) {
    var ids =
        java.util.Arrays.stream(checks)
            .filter(java.util.Objects::nonNull)
            .map(check -> java.util.UUID.fromString(check.id()))
            .toList();
    return ids.isEmpty() ? handoff : handoff.withTestChecks(ids);
  }

  private static HandoffSource source(TaskDefinition task) {
    return switch (task.modelMode()) {
      case DEEPSEEK -> HandoffSource.DEEPSEEK_FLASH;
      case USER -> HandoffSource.MODEL_CONNECTION;
      case MOCK -> HandoffSource.MOCK_MODEL;
    };
  }
}
