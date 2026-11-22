package dev.workbench.framework.skill;

/** 将已采纳记忆晋升为私有 Skill 草稿的纯净端口；具体持久化由 privateagent 实现。 */
public interface SkillDraftPort {
  SkillDraft createDraft(String name, String content);

  record SkillDraft(String id, String name, String status) {}
}
