package dev.workbench.privateagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.auth.AuthService;
import dev.workbench.domain.ToolKind;
import dev.workbench.planning.ExecutionScopeResolver;
import dev.workbench.persistence.entity.PrivateAgentEntity;
import dev.workbench.persistence.entity.PrivateAgentVersionEntity;
import dev.workbench.persistence.entity.PrivateSkillEntity;
import dev.workbench.persistence.entity.PrivateSkillVersionEntity;
import dev.workbench.persistence.entity.PrivateToolEntity;
import dev.workbench.persistence.entity.PrivateToolVersionEntity;
import dev.workbench.persistence.repository.PrivateAgentRepository;
import dev.workbench.persistence.repository.PrivateAgentVersionRepository;
import dev.workbench.persistence.repository.PrivateSkillRepository;
import dev.workbench.persistence.repository.PrivateSkillVersionRepository;
import dev.workbench.persistence.repository.PrivateToolRepository;
import dev.workbench.persistence.repository.PrivateToolVersionRepository;
import dev.workbench.infrastructure.model.TaskIdeaModel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 我的 Skill、工具和 Agent。
 *
 * <p>草稿可以反复编辑；发布生成不可变版本并固定组件版本。所有读写都带当前会话归属，按 ID 读取 其他用户的资源与不存在一样返回 404。发布只保存定义，不授予运行权限。
 */
@Service
public class PrivateComponentService {
  private static final Set<String> TOOL_EXECUTORS = Set.of("READ", "SEARCH");
  private static final Set<String> AGENT_ROLES = Set.of("ANALYST", "REVIEWER");

  private final PrivateSkillRepository skills;
  private final PrivateSkillVersionRepository skillVersions;
  private final PrivateToolRepository tools;
  private final PrivateToolVersionRepository toolVersions;
  private final PrivateAgentRepository agents;
  private final PrivateAgentVersionRepository agentVersions;
  private final ExecutionScopeResolver scopes;
  private final ObjectMapper json;
  private final TaskIdeaModel taskIdeas;

  public PrivateComponentService(
      PrivateSkillRepository skills,
      PrivateSkillVersionRepository skillVersions,
      PrivateToolRepository tools,
      PrivateToolVersionRepository toolVersions,
      PrivateAgentRepository agents,
      PrivateAgentVersionRepository agentVersions,
      ExecutionScopeResolver scopes,
      ObjectMapper json,
      TaskIdeaModel taskIdeas) {
    this.skills = skills;
    this.skillVersions = skillVersions;
    this.tools = tools;
    this.toolVersions = toolVersions;
    this.agents = agents;
    this.agentVersions = agentVersions;
    this.scopes = scopes;
    this.json = json;
    this.taskIdeas = taskIdeas;
  }

  public record SkillRequest(
      String name,
      String summary,
      String usageNotes,
      String inputContract,
      String outputContract,
      String contentMd) {}

  public record ToolRequest(
      String name, String description, String executorKind, List<String> fileExtensions) {}

  public record ComponentRef(String id, Integer version) {}

  public record AgentRequest(
      String name,
      String description,
      String responsibility,
      String outputRequirements,
      String permissionRole,
      Boolean recommendable,
      String modelMode,
      List<ComponentRef> skills,
      List<ComponentRef> tools,
      String modelStrategy) {
    /** 兼容旧客户端：未填写时继承任务模型策略。 */
    public AgentRequest(
        String name,
        String description,
        String responsibility,
        String outputRequirements,
        String permissionRole,
        Boolean recommendable,
        String modelMode,
        List<ComponentRef> skills,
        List<ComponentRef> tools) {
      this(
          name,
          description,
          responsibility,
          outputRequirements,
          permissionRole,
          recommendable,
          modelMode,
          skills,
          tools,
          "INHERIT_TASK");
    }
  }

  public record SkillView(
      String id,
      String name,
      String summary,
      String usageNotes,
      String inputContract,
      String outputContract,
      String contentMd,
      String status,
      int currentVersion,
      boolean draftChanged,
      String archivedAt,
      String updatedAt) {}

  public record ToolView(
      String id,
      String name,
      String description,
      String executorKind,
      String executorLabel,
      List<String> fileExtensions,
      String status,
      int currentVersion,
      boolean draftChanged,
      String archivedAt,
      String updatedAt) {}

  public record PinnedSkill(
      String id,
      int version,
      String name,
      String sha256,
      String guidance,
      String usageNotes,
      String inputContract,
      String outputContract) {}

  public record PinnedTool(
      String id,
      int version,
      String name,
      String executorKind,
      String toolName,
      List<String> fileExtensions,
      String sha256) {}

  public record AgentView(
      String id,
      String name,
      String description,
      String responsibility,
      String outputRequirements,
      String permissionRole,
      boolean recommendable,
      String modelMode,
      String modelStrategy,
      List<ComponentRef> skills,
      List<ComponentRef> tools,
      String status,
      int currentVersion,
      boolean draftChanged,
      String archivedAt,
      String updatedAt,
      String definitionRef,
      String specSha256) {}

  public record AgentVersionView(
      int version,
      String name,
      String description,
      String permissionRole,
      boolean recommendable,
      String modelMode,
      String modelStrategy,
      List<PinnedSkill> skills,
      List<PinnedTool> tools,
      String specSha256,
      String createdAt) {}

  public record Draft(
      String name,
      String description,
      String responsibility,
      String outputRequirements,
      String permissionRole,
      boolean recommendable,
      String modelMode,
      String modelStrategy,
      List<String> openQuestions,
      List<ComponentRef> suggestedSkills,
      List<ComponentRef> suggestedTools) {
    public Draft {
      openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
      suggestedSkills = suggestedSkills == null ? List.of() : List.copyOf(suggestedSkills);
      suggestedTools = suggestedTools == null ? List.of() : List.copyOf(suggestedTools);
    }

    /** 兼容旧调用，未指定时继承任务模型策略且不预选组件。 */
    public Draft(
        String name,
        String description,
        String responsibility,
        String outputRequirements,
        String permissionRole,
        boolean recommendable,
        String modelMode,
        List<String> openQuestions) {
      this(
          name,
          description,
          responsibility,
          outputRequirements,
          permissionRole,
          recommendable,
          modelMode,
          "INHERIT_TASK",
          openQuestions,
          List.of(),
          List.of());
    }

    public Draft(
        String name,
        String description,
        String responsibility,
        String outputRequirements,
        String permissionRole,
        boolean recommendable,
        String modelMode,
        String modelStrategy,
        List<String> openQuestions) {
      this(
          name,
          description,
          responsibility,
          outputRequirements,
          permissionRole,
          recommendable,
          modelMode,
          modelStrategy,
          openQuestions,
          List.of(),
          List.of());
    }
  }

  public List<SkillView> skills() {
    return skills.findAllByOwnerScopeKeyOrderByUpdatedAtDesc(scope()).stream()
        .map(this::skillView)
        .toList();
  }

  @Transactional
  public SkillView createSkill(SkillRequest request) {
    validateSkill(request);
    var row =
        new PrivateSkillEntity(
            dev.workbench.task.RunRecordService.id(),
            scope(),
            request.name().strip(),
            dev.workbench.task.RunRecordService.now());
    apply(row, request);
    return skillView(skills.saveAndFlush(row));
  }

  @Transactional
  public SkillView updateSkill(String id, SkillRequest request) {
    var row = ownSkill(id);
    requireEditable(row.getStatus());
    validateSkill(request);
    apply(row, request);
    row.setUpdatedAt(dev.workbench.task.RunRecordService.now());
    return skillView(skills.saveAndFlush(row));
  }

  @Transactional
  public SkillView publishSkill(String id) {
    var row = ownSkill(id);
    requireEditable(row.getStatus());
    int version = row.getCurrentVersion() + 1;
    String now = dev.workbench.task.RunRecordService.now();
    skillVersions.saveAndFlush(
        new PrivateSkillVersionEntity(
            row.getId(),
            version,
            row.getName(),
            row.getSummary(),
            row.getUsageNotes(),
            row.getInputContract(),
            row.getOutputContract(),
            row.getContentMd(),
            sha256(row.getContentMd()),
            now));
    row.setCurrentVersion(version);
    row.setStatus("PUBLISHED");
    row.setUpdatedAt(now);
    return skillView(skills.saveAndFlush(row));
  }

  @Transactional
  public SkillView archiveSkill(String id) {
    var row = ownSkill(id);
    row.setStatus("ARCHIVED");
    row.setArchivedAt(dev.workbench.task.RunRecordService.now());
    row.setUpdatedAt(row.getArchivedAt());
    return skillView(skills.saveAndFlush(row));
  }

  public List<SkillView> skillVersions(String id) {
    ownSkill(id);
    return skillVersions.findBySkillIdOrderByVersionDesc(id).stream()
        .map(
            row ->
                new SkillView(
                    row.getSkillId(),
                    row.getName(),
                    row.getSummary(),
                    row.getUsageNotes(),
                    row.getInputContract(),
                    row.getOutputContract(),
                    row.getContentMd(),
                    "PUBLISHED",
                    row.getVersion(),
                    false,
                    null,
                    row.getCreatedAt()))
        .toList();
  }

  public List<ToolView> tools() {
    return tools.findAllByOwnerScopeKeyOrderByUpdatedAtDesc(scope()).stream()
        .map(this::toolView)
        .toList();
  }

  @Transactional
  public ToolView createTool(ToolRequest request) {
    validateTool(request);
    var row =
        new PrivateToolEntity(
            dev.workbench.task.RunRecordService.id(),
            scope(),
            request.name().strip(),
            dev.workbench.task.RunRecordService.now());
    apply(row, request);
    return toolView(tools.saveAndFlush(row));
  }

  @Transactional
  public ToolView updateTool(String id, ToolRequest request) {
    var row = ownTool(id);
    requireEditable(row.getStatus());
    validateTool(request);
    apply(row, request);
    return toolView(tools.saveAndFlush(row));
  }

  @Transactional
  public ToolView publishTool(String id) {
    var row = ownTool(id);
    requireEditable(row.getStatus());
    int version = row.getCurrentVersion() + 1;
    String now = dev.workbench.task.RunRecordService.now();
    toolVersions.saveAndFlush(
        new PrivateToolVersionEntity(
            row.getId(),
            version,
            row.getName(),
            row.getDescription(),
            row.getExecutorKind(),
            row.getFileExtensionsJson(),
            toolSpecSha(row),
            now));
    row.setCurrentVersion(version);
    row.setStatus("PUBLISHED");
    row.setUpdatedAt(now);
    return toolView(tools.saveAndFlush(row));
  }

  @Transactional
  public ToolView archiveTool(String id) {
    var row = ownTool(id);
    row.setStatus("ARCHIVED");
    row.setArchivedAt(dev.workbench.task.RunRecordService.now());
    row.setUpdatedAt(row.getArchivedAt());
    return toolView(tools.saveAndFlush(row));
  }

  public List<ToolView> toolVersions(String id) {
    ownTool(id);
    return toolVersions.findByToolIdOrderByVersionDesc(id).stream()
        .map(
            row ->
                toolView(
                    row.getToolId(),
                    row.getName(),
                    row.getDescription(),
                    row.getExecutorKind(),
                    readList(row.getFileExtensionsJson()),
                    "PUBLISHED",
                    row.getVersion(),
                    false,
                    null,
                    row.getCreatedAt()))
        .toList();
  }

  public List<AgentView> agents() {
    return agents.findAllByOwnerScopeKeyOrderByUpdatedAtDesc(scope()).stream()
        .map(this::agentView)
        .toList();
  }

  @Transactional
  public AgentView createAgent(AgentRequest request) {
    validateAgent(request);
    var row =
        new PrivateAgentEntity(
            dev.workbench.task.RunRecordService.id(),
            scope(),
            request.name().strip(),
            dev.workbench.task.RunRecordService.now());
    apply(row, request);
    return agentView(agents.saveAndFlush(row));
  }

  @Transactional
  public AgentView updateAgent(String id, AgentRequest request) {
    var row = ownAgent(id);
    requireEditable(row.getStatus());
    validateAgent(request);
    apply(row, request);
    return agentView(agents.saveAndFlush(row));
  }

  /** 发布时固定技能、工具和摘要；后续版本更新不会改变旧运行的解释。 */
  @Transactional
  public AgentView publishAgent(String id) {
    var row = ownAgent(id);
    requireEditable(row.getStatus());
    var pinnedSkills = new ArrayList<PinnedSkill>();
    for (ComponentRef ref : readRefs(row.getSkillRefsJson())) {
      PrivateSkillVersionEntity version = resolveSkillVersion(ref);
      pinnedSkills.add(
          new PinnedSkill(
              version.getSkillId(),
              version.getVersion(),
              version.getName(),
              version.getContentSha256(),
              version.getContentMd(),
              version.getUsageNotes(),
              version.getInputContract(),
              version.getOutputContract()));
    }
    var pinnedTools = new ArrayList<PinnedTool>();
    for (ComponentRef ref : readRefs(row.getToolRefsJson())) {
      PrivateToolVersionEntity version = resolveToolVersion(ref);
      pinnedTools.add(
          new PinnedTool(
              version.getToolId(),
              version.getVersion(),
              version.getName(),
              version.getExecutorKind(),
              privateToolName(version.getToolId(), version.getExecutorKind()),
              readList(version.getFileExtensionsJson()),
              version.getSpecSha256()));
    }
    String components = write(Map.of("skills", pinnedSkills, "tools", pinnedTools));
    int version = row.getCurrentVersion() + 1;
    String now = dev.workbench.task.RunRecordService.now();
    String specSha =
        sha256(
            String.join(
                "\n",
                "private-agent-v1",
                row.getName(),
                row.getDescription(),
                row.getResponsibility(),
                Objects.toString(row.getOutputRequirements(), ""),
                row.getPermissionRole(),
                Boolean.toString(row.isRecommendable()),
                row.getModelMode(),
                row.getModelStrategy(),
                components));
    agentVersions.saveAndFlush(
        new PrivateAgentVersionEntity(
            row.getId(),
            version,
            row.getName(),
            row.getDescription(),
            row.getResponsibility(),
            row.getOutputRequirements(),
            row.getPermissionRole(),
            row.isRecommendable(),
            row.getModelMode(),
            row.getModelStrategy(),
            components,
            specSha,
            now));
    row.setCurrentVersion(version);
    row.setStatus("PUBLISHED");
    row.setUpdatedAt(now);
    return agentView(agents.saveAndFlush(row));
  }

  @Transactional
  public AgentView archiveAgent(String id) {
    var row = ownAgent(id);
    row.setStatus("ARCHIVED");
    row.setArchivedAt(dev.workbench.task.RunRecordService.now());
    row.setUpdatedAt(row.getArchivedAt());
    return agentView(agents.saveAndFlush(row));
  }

  public List<AgentVersionView> agentVersions(String id) {
    ownAgent(id);
    return agentVersions.findByAgentIdOrderByVersionDesc(id).stream()
        .map(this::agentVersionView)
        .toList();
  }

  /** 从设计思路生成可编辑草稿；默认本地规则，也可显式调用已配置模型。 */
  public Draft draftFromIdea(String idea) {
    return draftFromIdea(idea, "LOCAL");
  }

  public Draft draftFromIdea(String idea, String mode) {
    if (idea == null || idea.isBlank() || idea.strip().length() > 2000)
      throw new IllegalArgumentException("请用 1 至 2000 字描述 Agent 的设计思路。");
    String strategy =
        mode == null || mode.isBlank() ? "LOCAL" : mode.strip().toUpperCase(java.util.Locale.ROOT);
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
      var skillAliases = new java.util.LinkedHashMap<String, PrivateSkillEntity>();
      var toolAliases = new java.util.LinkedHashMap<String, PrivateToolEntity>();
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
            .append(readList(version.getFileExtensionsJson()))
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
          raw.openQuestions() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(raw.openQuestions());
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

  /** 模型只返回可见组件代号，由服务端解析成当前账号的稳定 ID 和发布版本。 */
  public record ModelDraft(
      String name,
      String description,
      String responsibility,
      String outputRequirements,
      String permissionRole,
      Boolean recommendable,
      String modelStrategy,
      String modelMode,
      List<String> openQuestions,
      List<String> skillRefs,
      List<String> toolRefs) {}

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
            : draft.modelStrategy().strip().toUpperCase(java.util.Locale.ROOT);
    if (!List.of("INHERIT_TASK", "FIXED").contains(strategy))
      throw new IllegalStateException("模型草稿的模型策略无效。");
    String modelMode =
        draft.modelMode() == null || draft.modelMode().isBlank() ? "mock" : draft.modelMode();
    if ("FIXED".equals(strategy) && !List.of("mock", "deepseek").contains(modelMode))
      throw new IllegalStateException("模型草稿指定了未配置的模型。");
    List<String> questions =
        draft.openQuestions() == null
            ? List.of()
            : draft.openQuestions().stream().filter(item -> item != null && !item.isBlank()).limit(5).toList();
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

  String scope() {
    return scopes.current().ownerScopeKey();
  }

  private SkillView skillView(PrivateSkillEntity row) {
    boolean changed =
        row.getCurrentVersion() < 1
            || !sha256(row.getContentMd())
                .equals(
                    skillVersions
                        .findBySkillIdAndVersion(row.getId(), row.getCurrentVersion())
                        .map(PrivateSkillVersionEntity::getContentSha256)
                        .orElse(""));
    return new SkillView(
        row.getId(),
        row.getName(),
        row.getSummary(),
        row.getUsageNotes(),
        row.getInputContract(),
        row.getOutputContract(),
        row.getContentMd(),
        row.getStatus(),
        row.getCurrentVersion(),
        changed,
        row.getArchivedAt(),
        row.getUpdatedAt());
  }

  private ToolView toolView(PrivateToolEntity row) {
    List<String> extensions = readList(row.getFileExtensionsJson());
    boolean changed =
        row.getCurrentVersion() < 1
            || !toolSpecSha(row.getId(), row.getName(), row.getDescription(), row.getExecutorKind(), row.getFileExtensionsJson())
                .equals(
                    toolVersions
                        .findByToolIdAndVersion(row.getId(), row.getCurrentVersion())
                        .map(PrivateToolVersionEntity::getSpecSha256)
                        .orElse(""));
    return toolView(
        row.getId(),
        row.getName(),
        row.getDescription(),
        row.getExecutorKind(),
        extensions,
        row.getStatus(),
        row.getCurrentVersion(),
        changed,
        row.getArchivedAt(),
        row.getUpdatedAt());
  }

  private ToolView toolView(
      String id,
      String name,
      String description,
      String executorKind,
      List<String> extensions,
      String status,
      int version,
      boolean changed,
      String archivedAt,
      String updatedAt) {
    return new ToolView(
        id,
        name,
        description,
        executorKind,
        "SEARCH".equals(executorKind) ? "授权搜索" : "授权读取",
        extensions,
        status,
        version,
        changed,
        archivedAt,
        updatedAt);
  }

  private AgentView agentView(PrivateAgentEntity row) {
    String definitionRef = "pa-" + row.getId();
    String specSha =
        agentVersions
            .findByAgentIdAndVersion(row.getId(), row.getCurrentVersion())
            .map(PrivateAgentVersionEntity::getSpecSha256)
            .orElse(null);
    boolean changed = row.getCurrentVersion() < 1 || !"PUBLISHED".equals(row.getStatus());
    return new AgentView(
        row.getId(),
        row.getName(),
        row.getDescription(),
        row.getResponsibility(),
        row.getOutputRequirements(),
        row.getPermissionRole(),
        row.isRecommendable(),
        row.getModelMode(),
        row.getModelStrategy(),
        readRefs(row.getSkillRefsJson()),
        readRefs(row.getToolRefsJson()),
        row.getStatus(),
        row.getCurrentVersion(),
        changed,
        row.getArchivedAt(),
        row.getUpdatedAt(),
        definitionRef,
        specSha);
  }

  private AgentVersionView agentVersionView(PrivateAgentVersionEntity row) {
    try {
      var node = json.readTree(row.getComponentsJson());
      List<PinnedSkill> skills =
          json.convertValue(
              node.path("skills"),
              json.getTypeFactory().constructCollectionType(List.class, PinnedSkill.class));
      List<PinnedTool> tools =
          json.convertValue(
              node.path("tools"),
              json.getTypeFactory().constructCollectionType(List.class, PinnedTool.class));
      return new AgentVersionView(
          row.getVersion(),
          row.getName(),
          row.getDescription(),
          row.getPermissionRole(),
          row.isRecommendable(),
          row.getModelMode(),
          row.getModelStrategy(),
          skills,
          tools,
          row.getSpecSha256(),
          row.getCreatedAt());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("已发布 Agent 组件快照无法读取。", e);
    }
  }

  private void apply(PrivateSkillEntity row, SkillRequest request) {
    row.setName(request.name().strip());
    row.setSummary(request.summary().strip());
    row.setUsageNotes(blankToNull(request.usageNotes()));
    row.setInputContract(blankToNull(request.inputContract()));
    row.setOutputContract(blankToNull(request.outputContract()));
    row.setContentMd(request.contentMd().strip());
    row.setDraftSha256(sha256(request.contentMd().strip()));
    row.setUpdatedAt(dev.workbench.task.RunRecordService.now());
  }

  private void apply(PrivateToolEntity row, ToolRequest request) {
    row.setName(request.name().strip());
    row.setDescription(request.description().strip());
    row.setExecutorKind(request.executorKind());
    List<String> extensions =
        request.fileExtensions() == null
            ? List.of()
            : request.fileExtensions().stream()
                .map(PrivateComponentService::normalizeExtension)
                .distinct()
                .toList();
    row.setFileExtensionsJson(write(extensions));
    row.setDraftSha256(toolSpecSha(row));
    row.setUpdatedAt(dev.workbench.task.RunRecordService.now());
  }

  private void apply(PrivateAgentEntity row, AgentRequest request) {
    row.setName(request.name().strip());
    row.setDescription(request.description().strip());
    row.setResponsibility(request.responsibility().strip());
    row.setOutputRequirements(blankToNull(request.outputRequirements()));
    row.setPermissionRole(request.permissionRole());
    row.setRecommendable(Boolean.TRUE.equals(request.recommendable()));
    row.setModelMode(
        request.modelMode() == null || request.modelMode().isBlank()
            ? "mock"
            : request.modelMode());
    row.setModelStrategy(
        request.modelStrategy() == null || request.modelStrategy().isBlank()
            ? "INHERIT_TASK"
            : request.modelStrategy().strip().toUpperCase(java.util.Locale.ROOT));
    row.setSkillRefsJson(write(request.skills() == null ? List.of() : request.skills()));
    row.setToolRefsJson(write(request.tools() == null ? List.of() : request.tools()));
    row.setDraftSha256(
        sha256(
            String.join(
                "\n",
                row.getName(),
                row.getDescription(),
                row.getResponsibility(),
                Objects.toString(row.getOutputRequirements(), ""),
                row.getPermissionRole(),
                row.getSkillRefsJson(),
                row.getToolRefsJson())));
    row.setUpdatedAt(dev.workbench.task.RunRecordService.now());
  }

  private void validateSkill(SkillRequest request) {
    if (request == null) throw new IllegalArgumentException("Skill 内容不能为空。");
    text(request.name(), 120, "Skill 名称");
    text(request.summary(), 300, "用途");
    if (request.contentMd() == null
        || request.contentMd().isBlank()
        || request.contentMd().strip().length() > 20000)
      throw new IllegalArgumentException("Skill 正文须为 1 至 20000 字 Markdown。");
    optional(request.usageNotes(), 2000, "使用说明");
    optional(request.inputContract(), 1000, "输入约定");
    optional(request.outputContract(), 1000, "预期输出");
  }

  private void validateTool(ToolRequest request) {
    if (request == null) throw new IllegalArgumentException("工具配置不能为空。");
    text(request.name(), 120, "工具名称");
    text(request.description(), 300, "工具说明");
    if (!TOOL_EXECUTORS.contains(request.executorKind()))
      throw new IllegalArgumentException("首批私有工具只允许受信读取或授权搜索执行器。");
    if (request.fileExtensions() == null || request.fileExtensions().isEmpty())
      throw new IllegalArgumentException("请至少填写一个允许的文件扩展名，例如 .jsx。");
    if (request.fileExtensions().size() > 10)
      throw new IllegalArgumentException("文件扩展名最多 10 个。");
  }

  private void validateAgent(AgentRequest request) {
    if (request == null) throw new IllegalArgumentException("Agent 配置不能为空。");
    text(request.name(), 120, "Agent 名称");
    text(request.description(), 300, "Agent 简介");
    text(request.responsibility(), 20000, "职责");
    optional(request.outputRequirements(), 2000, "输出要求");
    if (!AGENT_ROLES.contains(request.permissionRole()))
      throw new IllegalArgumentException("首批私有 Agent 只允许分析或审查权限类别。");
    if (request.tools() != null && !request.tools().isEmpty() && "REVIEWER".equals(request.permissionRole()))
      throw new IllegalArgumentException("审查权限类别不能声明工作区只读工具。");
    String strategy =
        request.modelStrategy() == null || request.modelStrategy().isBlank()
            ? "INHERIT_TASK"
            : request.modelStrategy().strip().toUpperCase(java.util.Locale.ROOT);
    if (!List.of("INHERIT_TASK", "FIXED").contains(strategy))
      throw new IllegalArgumentException("Agent 模型策略必须为继承任务或指定模型。");
    if ("FIXED".equals(strategy)) {
      String mode = request.modelMode() == null ? "" : request.modelMode().strip();
      if (!List.of("mock", "deepseek").contains(mode))
        throw new IllegalArgumentException("指定模型时必须选择服务器已配置的 mock 或 deepseek。");
    }
  }

  private PrivateSkillEntity ownSkill(String id) {
    return skills
        .findByIdAndOwnerScopeKey(id, scope())
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  private PrivateToolEntity ownTool(String id) {
    return tools
        .findByIdAndOwnerScopeKey(id, scope())
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  private PrivateAgentEntity ownAgent(String id) {
    return agents
        .findByIdAndOwnerScopeKey(id, scope())
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  private PrivateSkillVersionEntity resolveSkillVersion(ComponentRef ref) {
    if (ref == null || ref.id() == null) throw new IllegalArgumentException("Skill 引用为空。");
    var skill =
        skills
            .findByIdAndOwnerScopeKey(ref.id(), scope())
            .orElseThrow(() -> new IllegalArgumentException("Skill 不存在或无权访问：" + ref.id()));
    if (!"PUBLISHED".equals(skill.getStatus()))
      throw new IllegalArgumentException("Skill 未发布：" + skill.getName());
    int version = ref.version() == null ? skill.getCurrentVersion() : ref.version();
    return skillVersions
        .findBySkillIdAndVersion(ref.id(), version)
        .orElseThrow(() -> new IllegalArgumentException("Skill 版本不存在：" + ref.id() + " v" + version));
  }

  private PrivateToolVersionEntity resolveToolVersion(ComponentRef ref) {
    if (ref == null || ref.id() == null) throw new IllegalArgumentException("工具引用为空。");
    var tool =
        tools
            .findByIdAndOwnerScopeKey(ref.id(), scope())
            .orElseThrow(() -> new IllegalArgumentException("工具不存在或无权访问：" + ref.id()));
    if (!"PUBLISHED".equals(tool.getStatus()))
      throw new IllegalArgumentException("工具未发布：" + tool.getName());
    int version = ref.version() == null ? tool.getCurrentVersion() : ref.version();
    return toolVersions
        .findByToolIdAndVersion(ref.id(), version)
        .orElseThrow(() -> new IllegalArgumentException("工具版本不存在：" + ref.id() + " v" + version));
  }

  private static void requireEditable(String status) {
    if ("ARCHIVED".equals(status)) throw new IllegalStateException("已归档定义不能编辑或再发布。");
  }

  private List<ComponentRef> readRefs(String value) {
    try {
      return json.readValue(
          value,
          json.getTypeFactory().constructCollectionType(List.class, ComponentRef.class));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("组件引用记录无法读取。", e);
    }
  }

  private List<String> readList(String value) {
    try {
      return json.readValue(
          value, json.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("文件扩展名记录无法读取。", e);
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("私有组件记录无法编码。", e);
    }
  }

  private static void text(String value, int max, String name) {
    if (value == null || value.isBlank() || value.strip().length() > max)
      throw new IllegalArgumentException(name + "为空或超过 " + max + " 字。");
  }

  private static void optional(String value, int max, String name) {
    if (value != null && value.strip().length() > max)
      throw new IllegalArgumentException(name + "超过 " + max + " 字。");
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  static String normalizeExtension(String value) {
    if (value == null) throw new IllegalArgumentException("文件扩展名为空。");
    String extension = value.strip().toLowerCase(Locale.ROOT);
    if (!extension.startsWith(".")) extension = "." + extension;
    if (!extension.matches("\\.[a-z0-9]{1,10}"))
      throw new IllegalArgumentException("文件扩展名无效：" + value);
    return extension;
  }

  static String privateToolName(String id, String executorKind) {
    String prefix = "SEARCH".equals(executorKind) ? "search_private_" : "read_private_";
    return prefix + id.replace("-", "").substring(0, 8);
  }

  private static String toolSpecSha(PrivateToolEntity row) {
    return toolSpecSha(
        row.getId(),
        row.getName(),
        row.getDescription(),
        row.getExecutorKind(),
        row.getFileExtensionsJson());
  }

  private static String toolSpecSha(
      String id, String name, String description, String kind, String extensions) {
    return sha256(
        String.join(
            "\n",
            "private-tool-v1",
            id,
            name,
            description,
            kind,
            extensions));
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("无法计算私有组件摘要。", e);
    }
  }
}
