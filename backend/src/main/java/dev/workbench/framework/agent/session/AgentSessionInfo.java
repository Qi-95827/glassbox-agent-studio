package dev.workbench.framework.agent.session;

import java.util.UUID;

/** 对用户只展示状态元数据，不返回会话内容或工具参数。 */
public record AgentSessionInfo(UUID agentId, AgentSessionStatus status, Long version) {}
