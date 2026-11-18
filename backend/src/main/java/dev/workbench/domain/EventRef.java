package dev.workbench.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 事件可选的权威关联。
 *
 * <p>只保存写入时已经存在的运行内标识；旧事件和运行级事件保持空值。节点、尝试等归属不从消息文本或
 * 计划顺序推断，避免把无关事件挂到错误节点。
 */
public record EventRef(
    UUID planVersionId,
    UUID nodeId,
    UUID attemptId,
    UUID agentId,
    UUID checkId,
    UUID modelCallId,
    UUID toolCallId) {

  public static EventRef empty() {
    return new EventRef(null, null, null, null, null, null, null);
  }

  public static EventRef plan(UUID planVersionId) {
    return new EventRef(planVersionId, null, null, null, null, null, null);
  }

  public static EventRef agent(UUID agentId) {
    return new EventRef(null, null, null, agentId, null, null, null);
  }

  public EventRef withPlan(UUID value) {
    return new EventRef(value, nodeId, attemptId, agentId, checkId, modelCallId, toolCallId);
  }

  public EventRef withNode(UUID value) {
    return new EventRef(planVersionId, value, attemptId, agentId, checkId, modelCallId, toolCallId);
  }

  public EventRef withAttempt(UUID value) {
    return new EventRef(planVersionId, nodeId, value, agentId, checkId, modelCallId, toolCallId);
  }

  public EventRef withAgent(UUID value) {
    return new EventRef(planVersionId, nodeId, attemptId, value, checkId, modelCallId, toolCallId);
  }

  public EventRef withCheck(UUID value) {
    return new EventRef(planVersionId, nodeId, attemptId, agentId, value, modelCallId, toolCallId);
  }

  public EventRef withModelCall(UUID value) {
    return value == null
        ? this
        : new EventRef(planVersionId, nodeId, attemptId, agentId, checkId, value, toolCallId);
  }

  public EventRef withToolCall(UUID value) {
    return value == null
        ? this
        : new EventRef(planVersionId, nodeId, attemptId, agentId, checkId, modelCallId, value);
  }

  /** 覆盖同一关联中的空值，已有值保持不变。 */
  public EventRef merge(EventRef other) {
    Objects.requireNonNull(other, "事件关联不能为空");
    return new EventRef(
        planVersionId != null ? planVersionId : other.planVersionId,
        nodeId != null ? nodeId : other.nodeId,
        attemptId != null ? attemptId : other.attemptId,
        agentId != null ? agentId : other.agentId,
        checkId != null ? checkId : other.checkId,
        modelCallId != null ? modelCallId : other.modelCallId,
        toolCallId != null ? toolCallId : other.toolCallId);
  }
}
