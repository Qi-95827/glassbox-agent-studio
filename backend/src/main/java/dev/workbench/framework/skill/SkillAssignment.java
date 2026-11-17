package dev.workbench.framework.skill;

import java.time.Instant;
import java.util.UUID;

/** 某个 Agent 实例已装配的技能引用。 */
public record SkillAssignment(
    UUID agentId, SkillId skillId, int version, String contentSha256, Instant createdAt) {}
