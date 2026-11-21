package dev.workbench.privateagent;

import static dev.workbench.privateagent.PrivateComponentSupport.optional;
import static dev.workbench.privateagent.PrivateComponentSupport.readList;
import static dev.workbench.privateagent.PrivateComponentSupport.text;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.infrastructure.model.TaskIdeaModel;
import dev.workbench.persistence.entity.PrivateSkillEntity;
import dev.workbench.persistence.entity.PrivateToolEntity;
import dev.workbench.persistence.repository.PrivateSkillRepository;
import dev.workbench.persistence.repository.PrivateSkillVersionRepository;
import dev.workbench.persistence.repository.PrivateToolRepository;
import dev.workbench.persistence.repository.PrivateToolVersionRepository;
import dev.workbench.privateagent.PrivateComponentModels.ComponentRef;
import dev.workbench.privateagent.PrivateComponentModels.Draft;
import dev.workbench.privateagent.PrivateComponentModels.ModelDraft;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 从设计思路生成本地规则或模型草稿；只建议当前账号可见的已发布组件。 */
@Service
public class PrivateAgentDraftService {
  private static final Set<String> AGENT_ROLES = Set.of("ANALYST", "REVIEWER");

  private final PrivateSkillRepository skills;
  private final PrivateSkillVersionRepository skillVersions;
  private final PrivateToolRepository tools;
  private final PrivateToolVersionRepository toolVersions;
  private final ExecutionScopeResolver scopes;
  private final ObjectMapper json;
  private final TaskIdeaModel taskIdeas;

  public PrivateAgentDraftService(
      PrivateSkillRepository skills,
      PrivateSkillVersionRepository skillVersions,
      PrivateToolRepository tools,
      PrivateToolVersionRepository toolVersions,
      ExecutionScopeResolver scopes,
      ObjectMapper json,
      TaskIdeaModel taskIdeas) {
    this.skills = skills;
    this.skillVersions = skillVersions;
    this.tools = tools;
    this.toolVersions = toolVersions;
    this.scopes = scopes;
    this.json = json;
    this.taskIdeas = taskIdeas;
  }

  /** 从设计思路生成可编辑草稿；默认本地规则，也可显式调用已配置模型。 */
  public Draft draftFromIdea(String idea) {
    return draftFromIdea(idea, "LOCAL");
  }

  public Draft draftFromIdea(String idea, String mode) {
    if (idea == null || idea.isBlank() || idea.strip().length() > 2000)
      throw new IllegalArgumentException("请用 1 至 2000 字描述 Agent 的设计思路。");
    String strategy =
        mode == null || mode.isBlank() ? "LOCAL" : mode.strip().toUpperCase(Locale.ROOT);
    if ("LOCAL".equals(strategy)) return localDraft(idea.strip());
    if (!"MODEL".equals(strategy))
      throw new IllegalArgumentException("草稿生成方式必须为本地规则或模型生成。");
    String text;
    try {
      var availableSkills =
          skills.findAllByOwnerScopeKeyOrderByUpdatedAtDesc(scope()).stream()
              .filter(row -> "PUBLISHED".equals(row.getStatus()) && row.getCurrentVersion() > 0)
              .toList();
      var availableTools =
          tools.findAllByOwnerScopeKeyOrderByUpdatedAtDesc(scope()).stream()
              .filter(row -> "PUBLISHED".equals(row.getStatus()) && row.getCurrentVersion() > 0)
              .toList();
      var skillAliases = new LinkedHashMap<String, PrivateSkillEntity>();
      var toolAliases = new LinkedHashMap<String, PrivateToolEntity>();
      var skillSummary = new StringBuilder();
      for (int index = 0; index < availableSkills.size(); index++) {
        var row = availableSkills.get(index);
        String alias = "S" + (index + 1);
        var version =
            skillVersions.findBySkillIdAndVersion(row.getId(), row.getCurrentVersion()).orElse(null);
        if (version == null) continue;
        skillAliases.put(alias, row);
        skillSummary
            .append(alias)
            .append(": ")
            .append(version.getName())
            .append(" v")
            .append(version.getVersion())
            .append(" · ")
            .append(version.getSummary())
            .append('\n');
      }
      var toolSummary = new StringBuilder();
      for (int index = 0; index < availableTools.size(); index++) {
        var row = availableTools.get(index);
        String alias = "T" + (index + 1);
        var version =
            toolVersions.findByToolIdAndVersion(row.getId(), row.getCurrentVersion()).orElse(null);
        if (version == null) continue;
        toolAliases.put(alias, row);
        toolSummary
            .append(alias)
            .append(": ")
            .append(version.getName())
            .append(" v")
            .append(version.getVersion())
            .append(" · ")
            .append(version.getDescription())
            .append(" · ")
            .append(version.getExecutorKind())
            .append(' ')
            .append(readList(json, version.getFileExtensionsJson()))
            .append('\n');
      }
      String instructions =
          """
          你是私有 Agent 装配助手。根据用户设计思路输出一个 JSON 对象，不要输出解释或 Markdown：
          name（最多 120 字）、description（最多 300 字）、responsibility（最多 2000 字）、
          outputRequirements（最多 2000 字）、permissionRole（ANALYST 或 REVIEWER）、
          recommendable（布尔）、modelStrategy（INHERIT_TASK 或 FIXED）、
          modelMode（FIXED 时 mock 或 deepseek，否则 mock）、openQuestions（最多 5 条字符串）、
          skillRefs（从下方 Skill 代号中选择，最多 5 个）、toolRefs（从下方工具代号中选择，最多 5 个）。
          只能引用下方当前用户可见的已发布 Skill 和工具代号，不能虚构 ID、文件、命令、凭据或审批。
          建议只是可编辑草稿，用户确认后才保存；ANALYST 允许只读，REVIEWER 不声明工作区工具。
          可用 Skill：
          """
              + skillSummary
              + "可用工具：\n"
              + toolSummary;
      text = taskIdeas.draft(instructions, idea.strip());
      ModelDraft raw = json.readValue(text, ModelDraft.class);
      List<String> questions =
          raw.openQuestions() == null
              ? new ArrayList<>()
              : new ArrayList<>(raw.openQuestions());
      var suggestedSkills = new ArrayList<ComponentRef>();
      if (raw.skillRefs() != null)
        for (String alias : raw.skillRefs()) {
          var row = skillAliases.get(alias);
          if (row == null) questions.add("模型建议的 Skill 代号不可用：" + alias);
          else suggestedSkills.add(new ComponentRef(row.getId(), row.getCurrentVersion()));
        }
      var suggestedTools = new ArrayList<ComponentRef>();
      if (raw.toolRefs() != null)
        for (String alias : raw.toolRefs()) {
          var row = toolAliases.get(alias);
          if (row == null) questions.add("模型建议的工具代号不可用：" + alias);
          else suggestedTools.add(new ComponentRef(row.getId(), row.getCurrentVersion()));
        }
      return validateDraft(
          new Draft(
              raw.name(),
              raw.description(),
              raw.responsibility(),
              raw.outputRequirements(),
              raw.permissionRole(),
              Boolean.TRUE.equals(raw.recommendable()),
              raw.modelMode(),
              raw.modelStrategy(),
              questions.stream().filter(item -> item != null && !item.isBlank()).limit(5).toList(),
              suggestedSkills,
              suggestedTools));
    } catch (RuntimeException e) {
      if (e instanceof IllegalStateException || e instanceof IllegalArgumentException) throw e;
      throw new IllegalStateException("模型草稿生成失败；可切换为本地规则后重试。");
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("模型返回的 Agent 草稿不是可解析的 JSON；可切换为本地规则。");
    }
  }

  private Draft localDraft(String text) {
    String name = text.split("[，。！？,.!?\\n]", 2)[0].strip();
    if (name.length() > 40) name = name.substring(0, 40);
    if (name.isBlank()) name = "我的只读检查 Agent";
    return new Draft(
        name,
        "由本地规则从设计思路整理；请检查后再发布。",
        text,
        "列出实际读取的文件、覆盖范围和未验证事项；不得声称未执行的检查已通过。",
        "ANALYST",
        true,
        "mock",
        "INHERIT_TASK",
        List.of("请确认权限类别为分析或审查。", "请选择已发布的 Skill 和只读工具。"));
  }

  private Draft validateDraft(Draft draft) {
    if (draft == null) throw new IllegalStateException("模型没有返回 Agent 草稿。");
    text(draft.name(), 120, "Agent 名称");
    text(draft.description(), 300, "Agent 简介");
    text(draft.responsibility(), 2000, "职责");
    optional(draft.outputRequirements(), 2000, "输出要求");
    if (!AGENT_ROLES.contains(draft.permissionRole()))
      throw new IllegalStateException("模型草稿的权限类别无效，请修改后再发布。");
    String strategy =
        draft.modelStrategy() == null || draft.modelStrategy().isBlank()
            ? "INHERIT_TASK"
            : draft.modelStrategy().strip().toUpperCase(Locale.ROOT);
    if (!List.of("INHERIT_TASK", "FIXED").contains(strategy))
      throw new IllegalStateException("模型草稿的模型策略无效。");
    String modelMode =
        draft.modelMode() == null || draft.modelMode().isBlank() ? "mock" : draft.modelMode();
    if ("FIXED".equals(strategy) && !List.of("mock", "deepseek").contains(modelMode))
      throw new IllegalStateException("模型草稿指定了未配置的模型。");
    List<String> questions =
        draft.openQuestions() == null
            ? List.of()
            : draft.openQuestions().stream()
                .filter(item -> item != null && !item.isBlank())
                .limit(5)
                .toList();
    return new Draft(
        draft.name().strip(),
        draft.description().strip(),
        draft.responsibility().strip(),
        draft.outputRequirements() == null ? null : draft.outputRequirements().strip(),
        draft.permissionRole(),
        draft.recommendable(),
        modelMode,
        strategy,
        questions,
        draft.suggestedSkills(),
        draft.suggestedTools());
  }

  private String scope() {
    return scopes.current().ownerScopeKey();
  }
}
