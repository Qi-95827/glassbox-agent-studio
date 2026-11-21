package dev.workbench.privateagent;

import dev.workbench.privateagent.PrivateComponentModels.AgentRequest;
import dev.workbench.privateagent.PrivateComponentModels.AgentVersionView;
import dev.workbench.privateagent.PrivateComponentModels.AgentView;
import dev.workbench.privateagent.PrivateComponentModels.Draft;
import dev.workbench.privateagent.PrivateComponentModels.SkillRequest;
import dev.workbench.privateagent.PrivateComponentModels.SkillView;
import dev.workbench.privateagent.PrivateComponentModels.ToolRequest;
import dev.workbench.privateagent.PrivateComponentModels.ToolView;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 我的 Skill、工具和 Agent 的兼容门面。
 *
 * <p>对外 HTTP 契约保持不变；实际职责由 {@link PrivateSkillService}、{@link
 * PrivateToolConfigService} 和 {@link PrivateAgentService} 承担，接入方应优先使用具体服务。
 */
@Service
public class PrivateComponentService {
  private final PrivateSkillService skills;
  private final PrivateToolConfigService tools;
  private final PrivateAgentService agents;

  public PrivateComponentService(
      PrivateSkillService skills, PrivateToolConfigService tools, PrivateAgentService agents) {
    this.skills = skills;
    this.tools = tools;
    this.agents = agents;
  }

  public List<SkillView> skills() {
    return skills.skills();
  }

  public SkillView createSkill(SkillRequest request) {
    return skills.createSkill(request);
  }

  public SkillView updateSkill(String id, SkillRequest request) {
    return skills.updateSkill(id, request);
  }

  public SkillView publishSkill(String id) {
    return skills.publishSkill(id);
  }

  public SkillView archiveSkill(String id) {
    return skills.archiveSkill(id);
  }

  public List<SkillView> skillVersions(String id) {
    return skills.skillVersions(id);
  }

  public List<ToolView> tools() {
    return tools.tools();
  }

  public ToolView createTool(ToolRequest request) {
    return tools.createTool(request);
  }

  public ToolView updateTool(String id, ToolRequest request) {
    return tools.updateTool(id, request);
  }

  public ToolView publishTool(String id) {
    return tools.publishTool(id);
  }

  public ToolView archiveTool(String id) {
    return tools.archiveTool(id);
  }

  public List<ToolView> toolVersions(String id) {
    return tools.toolVersions(id);
  }

  public List<AgentView> agents() {
    return agents.agents();
  }

  public AgentView createAgent(AgentRequest request) {
    return agents.createAgent(request);
  }

  public AgentView updateAgent(String id, AgentRequest request) {
    return agents.updateAgent(id, request);
  }

  public AgentView publishAgent(String id) {
    return agents.publishAgent(id);
  }

  public AgentView archiveAgent(String id) {
    return agents.archiveAgent(id);
  }

  public List<AgentVersionView> agentVersions(String id) {
    return agents.agentVersions(id);
  }

  public Draft draftFromIdea(String idea) {
    return agents.draftFromIdea(idea);
  }

  public Draft draftFromIdea(String idea, String mode) {
    return agents.draftFromIdea(idea, mode);
  }
}
