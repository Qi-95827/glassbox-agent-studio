package dev.workbench.control;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 玻璃盒 Artifact 回查：卸载后的完整正文仍可按受控引用读取。 */
@RestController
@RequestMapping("/api/context-artifacts")
public class ContextArtifactController {
  private final ContextArtifactService artifacts;

  public ContextArtifactController(ContextArtifactService artifacts) {
    this.artifacts = artifacts;
  }

  @GetMapping("/{reference}")
  public Map<String, Object> read(@PathVariable String reference) {
    return artifacts.read(reference);
  }
}
