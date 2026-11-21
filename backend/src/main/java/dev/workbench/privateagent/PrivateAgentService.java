package dev.workbench.privateagent;

import static dev.workbench.privateagent.PrivateComponentSupport.blankToNull;
import static dev.workbench.privateagent.PrivateComponentSupport.optional;
import static dev.workbench.privateagent.PrivateComponentSupport.privateToolName;
import static dev.workbench.privateagent.PrivateComponentSupport.readRefs;
import static dev.workbench.privateagent.PrivateComponentSupport.requireEditable;
import static dev.workbench.privateagent.PrivateComponentSupport.sha256;
import static dev.workbench.privateagent.PrivateComponentSupport.text;
import static dev.workbench.privateagent.PrivateComponentSupport.write;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.domain.WorkbenchIds;
import dev.workbench.domain.WorkbenchTime;
import dev.workbench.persistence.entity.PrivateAgentEntity;
import dev.workbench.persistence.entity.PrivateAgentVersionEntity;
import dev.workbench.persistence.repository.PrivateAgentRepository;
import dev.workbench.persistence.repository.PrivateAgentVersionRepository;
import dev.workbench.privateagent.PrivateComponentModels.AgentRequest;
import dev.workbench.privateagent.PrivateComponentModels.AgentVersionView;
import dev.workbench.privateagent.PrivateComponentModels.AgentView;
import dev.workbench.privateagent.PrivateComponentModels.ComponentRef;
import dev.workbench.privateagent.PrivateComponentModels.Draft;
import dev.workbench.privateagent.PrivateComponentModels.PinnedSkill;
import dev.workbench.privateagent.PrivateComponentModels.PinnedTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 私有 Agent 的草稿、发布版本、归档与装配快照。 */
@Service
public class PrivateAgentService {
  private static final Set<String> AGENT_ROLES = Set.of("ANALYST", "REVIEWER");

  private final PrivateAgentRepository agents;
  private final PrivateAgentVersionRepository agentVersions;
  private final PrivateSkillService skills;
  private final PrivateToolConfigService tools;
  private final PrivateAgentDraftService drafts;
  private final ExecutionScopeResolver scopes;
  private final ObjectMapper json;

  public PrivateAgentService(
      PrivateAgentRepository agents,
      PrivateAgentVersionRepository agentVersions,
      PrivateSkillService skills,
      PrivateToolConfigService tools,
      PrivateAgentDraftService drafts,
      ExecutionScopeResolver scopes,
      ObjectMapper json) {
    this.agents = agents;
    this.agentVersions = agentVersions;
    this.skills = skills;
    this.tools = tools;
    this.drafts = drafts;
    this.scopes = scopes;
    this.json = json;
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
            WorkbenchIds.newId(), scope(), request.name().strip(), WorkbenchTime.now());
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
    for (ComponentRef ref : readRefs(json, row.getSkillRefsJson())) {
      var version = skills.resolveVersion(ref);
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
    for (ComponentRef ref : readRefs(json, row.getToolRefsJson())) {
      var version = tools.resolveVersion(ref);
      pinnedTools.add(
          new PinnedTool(
              version.getToolId(),
              version.getVersion(),
              version.getName(),
              version.getExecutorKind(),
              privateToolName(version.getToolId(), version.getExecutorKind()),
              PrivateComponentSupport.readList(json, version.getFileExtensionsJson()),
              version.getSpecSha256()));
    }
    String components = write(json, java.util.Map.of("skills", pinnedSkills, "tools", pinnedTools));
    int version = row.getCurrentVersion() + 1;
    String now = WorkbenchTime.now();
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
    row.setArchivedAt(WorkbenchTime.now());
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
    return drafts.draftFromIdea(idea);
  }

  public Draft draftFromIdea(String idea, String mode) {
    return drafts.draftFromIdea(idea, mode);
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
        readRefs(json, row.getSkillRefsJson()),
        readRefs(json, row.getToolRefsJson()),
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
            : request.modelStrategy().strip().toUpperCase(Locale.ROOT));
    row.setSkillRefsJson(write(json, request.skills() == null ? List.of() : request.skills()));
    row.setToolRefsJson(write(json, request.tools() == null ? List.of() : request.tools()));
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
    row.setUpdatedAt(WorkbenchTime.now());
  }

  private void validateAgent(AgentRequest request) {
    if (request == null) throw new IllegalArgumentException("Agent 配置不能为空。");
    text(request.name(), 120, "Agent 名称");
    text(request.description(), 300, "Agent 简介");
    text(request.responsibility(), 20000, "职责");
    optional(request.outputRequirements(), 2000, "输出要求");
    if (!AGENT_ROLES.contains(request.permissionRole()))
      throw new IllegalArgumentException("首批私有 Agent 只允许分析或审查权限类别。");
    if (request.tools() != null
        && !request.tools().isEmpty()
        && "REVIEWER".equals(request.permissionRole()))
      throw new IllegalArgumentException("审查权限类别不能声明工作区只读工具。");
    String strategy =
        request.modelStrategy() == null || request.modelStrategy().isBlank()
            ? "INHERIT_TASK"
            : request.modelStrategy().strip().toUpperCase(Locale.ROOT);
    if (!List.of("INHERIT_TASK", "FIXED").contains(strategy))
      throw new IllegalArgumentException("Agent 模型策略必须为继承任务或指定模型。");
    if ("FIXED".equals(strategy)) {
      String mode = request.modelMode() == null ? "" : request.modelMode().strip();
      if (!List.of("mock", "deepseek").contains(mode))
        throw new IllegalArgumentException("指定模型时必须选择服务器已配置的 mock 或 deepseek。");
    }
  }

  private PrivateAgentEntity ownAgent(String id) {
    return agents
        .findByIdAndOwnerScopeKey(id, scope())
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  private String scope() {
    return scopes.current().ownerScopeKey();
  }
}
