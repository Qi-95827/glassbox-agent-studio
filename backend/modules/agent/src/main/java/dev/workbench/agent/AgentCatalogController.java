package dev.workbench.agent;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内置 Agent 定义目录，不提供动态注册或权限修改。 */
@RestController
@RequestMapping("/api/agents")
public class AgentCatalogController {
  private final AgentCatalogService catalog;

  public AgentCatalogController(AgentCatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/catalog")
  public List<AgentCatalogService.RoleCard> catalog() {
    return catalog.roles();
  }
}
