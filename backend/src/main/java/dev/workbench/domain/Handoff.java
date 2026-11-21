package dev.workbench.domain;

import dev.workbench.domain.HandoffParty;
import dev.workbench.domain.HandoffSource;
import dev.workbench.domain.HandoffStatus;
import java.util.List;
import java.util.UUID;

/** 协调者与 Agent 之间不可变的交接内容；证据指向真实运行事件。 */
public record Handoff(
    String taskId,
    String runId,
    String agentId,
    HandoffParty sender,
    HandoffParty receiver,
    String goal,
    List<String> constraints,
    HandoffStatus status,
    String summary,
    String evidenceRunId,
    List<Long> evidenceEventIds,
    HandoffSource source,
    String recipientAgentId,
    UUID handoffId,
    Integer schemaVersion,
    List<UUID> testCheckIds) {
  /** 新交接由系统分配稳定 ID 和契约版本；旧 JSON 缺失字段时仍保持未知。 */
  public Handoff(
      String taskId,
      String runId,
      String agentId,
      HandoffParty sender,
      HandoffParty receiver,
      String goal,
      List<String> constraints,
      HandoffStatus status,
      String summary,
      String evidenceRunId,
      List<Long> evidenceEventIds,
      HandoffSource source,
      String recipientAgentId) {
    this(
        taskId,
        runId,
        agentId,
        sender,
        receiver,
        goal,
        constraints,
        status,
        summary,
        evidenceRunId,
        evidenceEventIds,
        source,
        recipientAgentId,
        UUID.randomUUID(),
        1,
        null);
  }

  /** 兼容已有 Java 调用和版本 1 记录；旧记录未明确保存测试证据。 */
  public Handoff(
      String taskId,
      String runId,
      String agentId,
      HandoffParty sender,
      HandoffParty receiver,
      String goal,
      List<String> constraints,
      HandoffStatus status,
      String summary,
      String evidenceRunId,
      List<Long> evidenceEventIds,
      HandoffSource source,
      String recipientAgentId,
      UUID handoffId,
      Integer schemaVersion) {
    this(
        taskId,
        runId,
        agentId,
        sender,
        receiver,
        goal,
        constraints,
        status,
        summary,
        evidenceRunId,
        evidenceEventIds,
        source,
        recipientAgentId,
        handoffId,
        schemaVersion,
        null);
  }

  /** 创建带明确测试证据的版本 2 交接，沿用交接 ID。 */
  public Handoff withTestChecks(List<UUID> checkIds) {
    return new Handoff(
        taskId,
        runId,
        agentId,
        sender,
        receiver,
        goal,
        constraints,
        status,
        summary,
        evidenceRunId,
        evidenceEventIds,
        source,
        recipientAgentId,
        handoffId,
        2,
        List.copyOf(checkIds));
  }

  public Handoff {
    constraints = List.copyOf(constraints);
    evidenceEventIds = List.copyOf(evidenceEventIds);
    if (testCheckIds != null) testCheckIds = List.copyOf(testCheckIds);
  }
}
