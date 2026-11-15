package dev.workbench.skill;

/** 记录实际装配的技能版本与内容摘要，不包含指令正文。 */
public record SkillRef(SkillId id, int version, String contentSha256) {}
