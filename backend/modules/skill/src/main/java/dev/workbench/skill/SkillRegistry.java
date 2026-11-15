package dev.workbench.skill;

import dev.workbench.domain.AgentRole;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 内置技能目录与角色装配清单；不接受模型或外部文本修改。 */
@Component
public class SkillRegistry {
  private final Map<SkillId, SkillDefinition> definitions;
  private final Map<AgentRole, List<SkillId>> assignments;

  public SkillRegistry() {
    this(builtInDefinitions(), builtInAssignments());
  }

  /** 组装时检查定义与绑定；包内构造用于验证不同的组合。 */
  SkillRegistry(
      Map<SkillId, SkillDefinition> definitions, Map<AgentRole, List<SkillId>> assignments) {
    if (definitions == null || assignments == null)
      throw new IllegalArgumentException("技能目录和装配清单不能为空。");
    var checkedDefinitions = new EnumMap<SkillId, SkillDefinition>(SkillId.class);
    definitions.forEach(
        (id, definition) -> {
          if (id == null || definition == null || definition.id() != id)
            throw new IllegalArgumentException("技能定义的编码不一致。");
          checkedDefinitions.put(id, definition);
        });
    var checkedAssignments = new EnumMap<AgentRole, List<SkillId>>(AgentRole.class);
    for (AgentRole role : AgentRole.values()) {
      var ids = assignments.get(role);
      if (ids == null
          || ids.stream().anyMatch(Objects::isNull)
          || ids.size() != new java.util.HashSet<>(ids).size()
          || ids.stream().anyMatch(id -> !checkedDefinitions.containsKey(id)))
        throw new IllegalArgumentException("角色技能清单缺失、重复或引用了未知技能：" + role);
      checkedAssignments.put(role, List.copyOf(ids));
    }
    if (assignments.size() != checkedAssignments.size())
      throw new IllegalArgumentException("角色技能清单包含未知角色。");
    this.definitions = Map.copyOf(checkedDefinitions);
    this.assignments = Map.copyOf(checkedAssignments);
  }

  /** 按清单顺序返回已校验的技能；技能本身不授予工具权限。 */
  public List<SkillDefinition> forRole(AgentRole role) {
    var ids = assignments.get(Objects.requireNonNull(role, "角色不能为空"));
    if (ids == null) throw new IllegalArgumentException("角色没有技能装配清单。");
    return ids.stream().map(definitions::get).toList();
  }

  private static Map<SkillId, SkillDefinition> builtInDefinitions() {
    return Map.of(
        SkillId.ANALYZE_EVIDENCE,
            new SkillDefinition(
                SkillId.ANALYZE_EVIDENCE,
                1,
                "问题分析",
                "先区分用户目标、已有证据和未知事项；只引用真实工具观察，不把假设或模拟结果写成已验证事实。"),
        SkillId.AUTHORIZED_EDIT,
            new SkillDefinition(
                SkillId.AUTHORIZED_EDIT,
                1,
                "受限代码修改",
                "先核对分析交接和授权范围，读取目标文件并使用其当前摘要；尽量缩小修改，清楚说明未验证事项。"),
        SkillId.REVIEW_EVIDENCE,
            new SkillDefinition(
                SkillId.REVIEW_EVIDENCE,
                1,
                "证据审查",
                "核对目标、约束、上游推断与真实证据；测试未执行、失败和未知分别说明，不以模型自述替代验证。"));
  }

  private static Map<AgentRole, List<SkillId>> builtInAssignments() {
    return Map.of(
        AgentRole.ANALYST, List.of(SkillId.ANALYZE_EVIDENCE),
        AgentRole.CODER, List.of(SkillId.AUTHORIZED_EDIT),
        AgentRole.REVIEWER, List.of(SkillId.REVIEW_EVIDENCE));
  }
}
