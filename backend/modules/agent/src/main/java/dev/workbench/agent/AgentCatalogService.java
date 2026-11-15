package dev.workbench.agent;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import dev.workbench.skill.SkillId;
import dev.workbench.skill.SkillRegistry;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** 提供当前内置角色的只读能力目录，具体任务授权仍由执行器校验。 */
@Service
public class AgentCatalogService {
  private final SkillRegistry skills;
  private final AgentRoleRegistry roleRegistry;

  public AgentCatalogService(SkillRegistry skills, AgentRoleRegistry roleRegistry) {
    this.skills = skills;
    this.roleRegistry = roleRegistry;
  }

  public List<RoleCard> roles() {
    return Arrays.stream(AgentRole.values()).map(this::describe).toList();
  }

  private RoleCard describe(AgentRole role) {
    var definition = roleRegistry.forRole(role);
    var tools = definition.tools().stream().sorted(Comparator.comparing(Enum::name)).toList();
    var methods =
        skills.forRole(role).stream()
            .map(s -> new SkillCard(s.id(), s.name(), s.version(), s.guidance(), s.contentSha256()))
            .toList();
    return new RoleCard(
        role,
        role.displayName(),
        definition.name(),
        definition.version(),
        definition.systemPrompt(),
        tools,
        methods);
  }

  /** 能力声明不是本次任务授权，也不证明模型实际执行。 */
  public record RoleCard(
      AgentRole role,
      String displayName,
      String agentName,
      int version,
      String systemPrompt,
      List<ToolKind> declaredTools,
      List<SkillCard> skills) {
    public RoleCard {
      declaredTools = List.copyOf(declaredTools);
      skills = List.copyOf(skills);
    }
  }

  public record SkillCard(
      SkillId id, String name, int version, String guidance, String contentSha256) {}
}
