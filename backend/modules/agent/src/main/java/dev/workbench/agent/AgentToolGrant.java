package dev.workbench.agent;

import dev.workbench.domain.ToolKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 某个 Agent 创建时的工具装配记录，不代表工具已经调用或始终拥有权限。 */
public record AgentToolGrant(
    UUID agentId, Instant loadedAt, List<ToolKind> tools, List<ToolVersion> toolVersions) {
  public AgentToolGrant {
    tools = List.copyOf(tools);
    toolVersions = List.copyOf(toolVersions);
  }

  /** 历史记录的版本可能未知，不能推断为第一版。 */
  public record ToolVersion(ToolKind tool, Integer version) {}
}
