package dev.workbench.framework.agent.definition;

import dev.workbench.agent.AgentRoleRegistry;
import dev.workbench.domain.AgentRole;
import dev.workbench.framework.skill.SkillRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 版本化受信定义目录；旧角色只作为过渡期权限上限与兼容映射。 */
@Component
public final class AgentDefinitionRegistry {
  private final Map<AgentDefinitionRef, AgentDefinition> definitions;
  private final Map<AgentRole, AgentDefinitionRef> legacyReferences;
  private final SkillRegistry skills;

  public AgentDefinitionRegistry(
      AgentRoleRegistry roles, SkillRegistry skills, List<AgentDefinitionProvider> providers) {
    Objects.requireNonNull(roles, "旧角色目录不能为空");
    Objects.requireNonNull(skills, "技能目录不能为空");
    Objects.requireNonNull(providers, "Agent 定义提供者不能为空");
    this.skills = skills;
    var checked = new LinkedHashMap<AgentDefinitionRef, AgentDefinition>();
    var mappings = new EnumMap<AgentRole, AgentDefinitionRef>(AgentRole.class);
    for (var role : AgentRole.values()) {
      var old = roles.forRole(role);
      var ref = builtinReference(role);
      var definition =
          new AgentDefinition(
              ref,
              old.name(),
              old.systemPrompt(),
              role,
              old.tools().stream().sorted().toList(),
              skills.forRole(role).stream().map(skill -> skill.id()).toList());
      register(checked, definition, roles, skills);
      mappings.put(role, ref);
    }
    for (var provider : providers) {
      if (provider == null) throw new IllegalArgumentException("Agent 定义提供者不能为空。");
      register(checked, provider.definition(), roles, skills);
    }
    definitions = Map.copyOf(checked);
    legacyReferences = Map.copyOf(mappings);
  }

  /** 按引用解析固定版本；缺失时不切换到其他版本。 */
  public AgentDefinition resolve(AgentDefinitionRef ref) {
    var definition = definitions.get(Objects.requireNonNull(ref, "Agent 定义引用不能为空"));
    if (definition == null) throw new IllegalArgumentException("Agent 定义或版本不存在。 " + ref);
    return definition;
  }

  /** 原角色在未指定新定义时继续使用对应的内置定义。 */
  public AgentDefinitionRef forLegacyRole(AgentRole role) {
    var ref = legacyReferences.get(Objects.requireNonNull(role, "角色不能为空"));
    if (ref == null) throw new IllegalArgumentException("角色没有对应的 Agent 定义。");
    return ref;
  }

  /** 旧固定角色的内置定义引用；新建计划与运行装配必须使用相同规则。 */
  public static AgentDefinitionRef builtinReference(AgentRole role) {
    Objects.requireNonNull(role, "角色不能为空");
    return new AgentDefinitionRef(role.name().toLowerCase(Locale.ROOT), role.version());
  }

  /** 计算定义声明及其技能内容的规范摘要，不包含模型密钥或上下文正文。 */
  public String fingerprint(AgentDefinitionRef ref) {
    var definition = resolve(ref);
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      append(digest, "agent-definition-v1");
      append(digest, ref.id());
      append(digest, Integer.toString(ref.version()));
      append(digest, definition.name());
      append(digest, definition.systemPrompt());
      append(digest, definition.permissionProfile().name());
      append(digest, Integer.toString(definition.tools().size()));
      for (var tool : definition.tools()) append(digest, tool.name());
      var selectedSkills = skills.resolve(definition.skills());
      append(digest, Integer.toString(selectedSkills.size()));
      for (var skill : selectedSkills) {
        append(digest, skill.id().name());
        append(digest, Integer.toString(skill.version()));
        append(digest, skill.contentSha256());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("无法计算 Agent 定义摘要。", e);
    }
  }

  /** 字段使用长度前缀，避免相邻字段拼接后出现歧义。 */
  private static void append(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static void register(
      Map<AgentDefinitionRef, AgentDefinition> checked,
      AgentDefinition definition,
      AgentRoleRegistry roles,
      SkillRegistry skills) {
    if (definition == null) throw new IllegalArgumentException("Agent 定义不能为空。");
    if (!roles.forRole(definition.permissionProfile()).tools().containsAll(definition.tools())) {
      throw new IllegalArgumentException("Agent 定义声明的工具超出旧权限配置上限。");
    }
    skills.resolve(definition.skills());
    if (checked.putIfAbsent(definition.ref(), definition) != null) {
      throw new IllegalArgumentException("Agent 定义标识和版本重复：" + definition.ref());
    }
  }
}
