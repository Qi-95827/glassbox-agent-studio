package dev.workbench.framework.agent.assembly;

import dev.workbench.framework.agent.definition.AgentRoleRegistry;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.definition.AgentDefinition;
import dev.workbench.framework.agent.definition.AgentDefinitionCatalog;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.agent.definition.AgentDefinitionRegistry;
import dev.workbench.framework.agent.definition.PrivateAgentDefinitionSource;
import dev.workbench.framework.skill.SkillDefinition;
import dev.workbench.framework.skill.SkillRegistry;
import dev.workbench.framework.tool.ToolContracts;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 创建不可变装配计划；实际注册和调用仍由执行器及运行控制检查。 */
@Component
public class AgentAssemblyPlanner {
  private final AgentRoleRegistry roles;
  private final AgentDefinitionCatalog definitions;
  private final SkillRegistry skills;

  @Autowired
  public AgentAssemblyPlanner(
      AgentRoleRegistry roles, AgentDefinitionCatalog definitions, SkillRegistry skills) {
    this.roles = roles;
    this.definitions = definitions;
    this.skills = skills;
  }

  /** 兼容只含内置定义的独立验证。 */
  public AgentAssemblyPlanner(
      AgentRoleRegistry roles, AgentDefinitionRegistry definitions, SkillRegistry skills) {
    this(roles, new AgentDefinitionCatalog(definitions), skills);
  }

  /** 包内兼容构造：用于只包含旧内置角色的独立验证。 */
  public AgentAssemblyPlanner(AgentRoleRegistry roles, SkillRegistry skills) {
    this(roles, new AgentDefinitionRegistry(roles, skills, List.of()), skills);
  }

  public Assembly plan(AgentRole role, boolean hasWorkspace, int fileCount, boolean writeEnabled) {
    return plan(role, null, hasWorkspace, fileCount, writeEnabled);
  }

  public Assembly plan(
      AgentRole role,
      AgentDefinitionRef requested,
      boolean hasWorkspace,
      int fileCount,
      boolean writeEnabled) {
    return plan(role, requested, null, hasWorkspace, fileCount, writeEnabled);
  }

  public Assembly plan(
      AgentRole role,
      AgentDefinitionRef requested,
      String expectedSha256,
      boolean hasWorkspace,
      int fileCount,
      boolean writeEnabled) {
    return plan(
        role,
        requested,
        expectedSha256,
        hasWorkspace,
        fileCount,
        writeEnabled ? fileCount : 0,
        writeEnabled);
  }

  /** 节点级装配：读取、搜索使用读取集合，写入使用更窄的写入集合。 */
  public Assembly plan(
      AgentRole role,
      AgentDefinitionRef requested,
      String expectedSha256,
      boolean hasWorkspace,
      int readFileCount,
      int writeFileCount,
      boolean writeEnabled) {
    if (readFileCount < 0
        || readFileCount > 20
        || writeFileCount < 0
        || writeFileCount > 20
        || writeFileCount > readFileCount
        || (hasWorkspace && readFileCount == 0)
        || (!hasWorkspace && readFileCount != 0)
        || writeEnabled != (writeFileCount > 0)) throw new IllegalArgumentException("工作区授权文件数量无效。");
    var ref = requested == null ? definitions.forLegacyRole(role) : requested;
    var definition = definitions.resolve(ref);
    if (definition.permissionProfile() != role) {
      throw new IllegalArgumentException("Agent 定义与运行的权限配置不一致。");
    }
    String fingerprint = definitions.fingerprint(ref);
    if (expectedSha256 != null && !expectedSha256.equals(fingerprint)) {
      throw new IllegalStateException("Agent 定义内容与计划摘要不一致，不能执行。");
    }
    var authorized = roles.forRole(role).authorizedTools(hasWorkspace, writeEnabled);
    var versions = new EnumMap<ToolKind, Integer>(ToolKind.class);
    for (var tool : definition.tools()) {
      if (authorized.contains(tool)) {
        int files = tool == ToolKind.WORKSPACE_WRITE ? writeFileCount : readFileCount;
        versions.put(tool, ToolContracts.versionFor(tool, files));
      }
    }
    var components = definitions.privateComponents(ref);
    for (var tool : components.tools()) {
      ToolKind kind =
          "SEARCH".equals(tool.executorKind())
              ? ToolKind.WORKSPACE_SEARCH
              : ToolKind.WORKSPACE_READ;
      if (!authorized.contains(kind))
        throw new IllegalArgumentException("私有工具「" + tool.name() + "」超出当前节点或权限类别允许的只读能力。");
      versions.putIfAbsent(kind, ToolContracts.versionFor(kind, readFileCount));
    }
    List<SkillDefinition> builtInSkills =
        definitions.isPrivate(ref) ? List.of() : skills.resolve(definition.skills());
    return new Assembly(
        definition,
        builtInSkills,
        versions,
        fingerprint,
        components.skills(),
        components.tools(),
        definitions.fixedModelMode(ref).orElse(null));
  }

  /** 技能指导与工具契约是装配计划，不表示已注册、已调用或执行成功。 */
  public record Assembly(
      AgentDefinition definition,
      List<SkillDefinition> skills,
      Map<ToolKind, Integer> toolVersions,
      String definitionSha256,
      List<PrivateAgentDefinitionSource.PinnedSkill> privateSkills,
      List<PrivateAgentDefinitionSource.PinnedTool> privateTools,
      dev.workbench.domain.ModelMode modelOverride) {
    public Assembly {
      skills = List.copyOf(skills);
      toolVersions = Map.copyOf(toolVersions);
      privateSkills = privateSkills == null ? List.of() : List.copyOf(privateSkills);
      privateTools = privateTools == null ? List.of() : List.copyOf(privateTools);
    }

    /** 兼容旧装配构造。 */
    public Assembly(
        AgentDefinition definition,
        List<SkillDefinition> skills,
        Map<ToolKind, Integer> toolVersions,
        String definitionSha256) {
      this(definition, skills, toolVersions, definitionSha256, List.of(), List.of(), null);
    }
  }
}
