package dev.workbench.privateagent;

import dev.workbench.framework.skill.SkillDraftPort;
import org.springframework.stereotype.Component;

/** 私有 Skill 草稿端口实现；保持 memory 与 privateagent 之间无直接依赖。 */
@Component
public class PrivateSkillDraftAdapter implements SkillDraftPort {
  private final PrivateSkillService skills;

  public PrivateSkillDraftAdapter(PrivateSkillService skills) {
    this.skills = skills;
  }

  @Override
  public SkillDraft createDraft(String name, String content) {
    var created =
        skills.createSkill(
            new PrivateComponentModels.SkillRequest(
                name,
                "从情景记忆晋升的方法论草稿，需人工核对后发布。",
                content,
                "任务目标、约束与授权文件范围。",
                "可复用的避坑步骤与验收证据。",
                "# " + name + "\n\n" + content));
    return new SkillDraft(created.id(), created.name(), created.status());
  }
}
