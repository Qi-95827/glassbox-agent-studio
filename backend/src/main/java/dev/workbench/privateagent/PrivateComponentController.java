package dev.workbench.privateagent;

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
  List<PrivateComponentService.SkillView> skills() {
    return components.skills();
  }

  @PostMapping("/skills")
  PrivateComponentService.SkillView createSkill(
      @RequestBody PrivateComponentService.SkillRequest request) {
    return components.createSkill(request);
  }

  @PutMapping("/skills/{id}")
  PrivateComponentService.SkillView updateSkill(
      @PathVariable String id, @RequestBody PrivateComponentService.SkillRequest request) {
    return components.updateSkill(id, request);
  }

  @PostMapping("/skills/{id}/publish")
  PrivateComponentService.SkillView publishSkill(@PathVariable String id) {
    return components.publishSkill(id);
  }

  @PostMapping("/skills/{id}/archive")
  PrivateComponentService.SkillView archiveSkill(@PathVariable String id) {
    return components.archiveSkill(id);
  }

  @GetMapping("/skills/{id}/versions")
  List<PrivateComponentService.SkillView> skillVersions(@PathVariable String id) {
    return components.skillVersions(id);
  }

  @GetMapping("/tools")
  List<PrivateComponentService.ToolView> tools() {
    return components.tools();
  }

  @PostMapping("/tools")
  PrivateComponentService.ToolView createTool(
      @RequestBody PrivateComponentService.ToolRequest request) {
    return components.createTool(request);
  }

  @PutMapping("/tools/{id}")
  PrivateComponentService.ToolView updateTool(
      @PathVariable String id, @RequestBody PrivateComponentService.ToolRequest request) {
    return components.updateTool(id, request);
  }

  @PostMapping("/tools/{id}/publish")
  PrivateComponentService.ToolView publishTool(@PathVariable String id) {
    return components.publishTool(id);
  }

  @PostMapping("/tools/{id}/archive")
  PrivateComponentService.ToolView archiveTool(@PathVariable String id) {
    return components.archiveTool(id);
  }

  @GetMapping("/tools/{id}/versions")
  List<PrivateComponentService.ToolView> toolVersions(@PathVariable String id) {
    return components.toolVersions(id);
  }

  @GetMapping("/agents")
  List<PrivateComponentService.AgentView> agents() {
    return components.agents();
  }

  @PostMapping("/agents")
  PrivateComponentService.AgentView createAgent(
      @RequestBody PrivateComponentService.AgentRequest request) {
    return components.createAgent(request);
  }

  @PutMapping("/agents/{id}")
  PrivateComponentService.AgentView updateAgent(
      @PathVariable String id, @RequestBody PrivateComponentService.AgentRequest request) {
    return components.updateAgent(id, request);
  }

  @PostMapping("/agents/{id}/publish")
  PrivateComponentService.AgentView publishAgent(@PathVariable String id) {
    return components.publishAgent(id);
  }

  @PostMapping("/agents/{id}/archive")
  PrivateComponentService.AgentView archiveAgent(@PathVariable String id) {
    return components.archiveAgent(id);
  }

  @GetMapping("/agents/{id}/versions")
  List<PrivateComponentService.AgentVersionView> agentVersions(@PathVariable String id) {
    return components.agentVersions(id);
  }

  public record DraftRequest(String idea, String mode) {}

  @PostMapping("/agents/draft")
  PrivateComponentService.Draft draft(@RequestBody DraftRequest request) {
    return components.draftFromIdea(request.idea(), request.mode());
  }
}
