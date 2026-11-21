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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 「我的 Skill / 我的工具 / 我的 Agent」入口；所有操作使用服务端会话归属。 */
@RestController
@RequestMapping("/api/my")
public class PrivateComponentController {
  private final PrivateComponentService components;

  public PrivateComponentController(PrivateComponentService components) {
    this.components = components;
  }

  @GetMapping("/skills")
  List<SkillView> skills() {
    return components.skills();
  }

  @PostMapping("/skills")
  SkillView createSkill(@RequestBody SkillRequest request) {
    return components.createSkill(request);
  }

  @PutMapping("/skills/{id}")
  SkillView updateSkill(@PathVariable String id, @RequestBody SkillRequest request) {
    return components.updateSkill(id, request);
  }

  @PostMapping("/skills/{id}/publish")
  SkillView publishSkill(@PathVariable String id) {
    return components.publishSkill(id);
  }

  @PostMapping("/skills/{id}/archive")
  SkillView archiveSkill(@PathVariable String id) {
    return components.archiveSkill(id);
  }

  @GetMapping("/skills/{id}/versions")
  List<SkillView> skillVersions(@PathVariable String id) {
    return components.skillVersions(id);
  }

  @GetMapping("/tools")
  List<ToolView> tools() {
    return components.tools();
  }

  @PostMapping("/tools")
  ToolView createTool(@RequestBody ToolRequest request) {
    return components.createTool(request);
  }

  @PutMapping("/tools/{id}")
  ToolView updateTool(@PathVariable String id, @RequestBody ToolRequest request) {
    return components.updateTool(id, request);
  }

  @PostMapping("/tools/{id}/publish")
  ToolView publishTool(@PathVariable String id) {
    return components.publishTool(id);
  }

  @PostMapping("/tools/{id}/archive")
  ToolView archiveTool(@PathVariable String id) {
    return components.archiveTool(id);
  }

  @GetMapping("/tools/{id}/versions")
  List<ToolView> toolVersions(@PathVariable String id) {
    return components.toolVersions(id);
  }

  @GetMapping("/agents")
  List<AgentView> agents() {
    return components.agents();
  }

  @PostMapping("/agents")
  AgentView createAgent(@RequestBody AgentRequest request) {
    return components.createAgent(request);
  }

  @PutMapping("/agents/{id}")
  AgentView updateAgent(@PathVariable String id, @RequestBody AgentRequest request) {
    return components.updateAgent(id, request);
  }

  @PostMapping("/agents/{id}/publish")
  AgentView publishAgent(@PathVariable String id) {
    return components.publishAgent(id);
  }

  @PostMapping("/agents/{id}/archive")
  AgentView archiveAgent(@PathVariable String id) {
    return components.archiveAgent(id);
  }

  @GetMapping("/agents/{id}/versions")
  List<AgentVersionView> agentVersions(@PathVariable String id) {
    return components.agentVersions(id);
  }

  public record DraftRequest(String idea, String mode) {}

  @PostMapping("/agents/draft")
  Draft draft(@RequestBody DraftRequest request) {
    return components.draftFromIdea(request.idea(), request.mode());
  }
}
