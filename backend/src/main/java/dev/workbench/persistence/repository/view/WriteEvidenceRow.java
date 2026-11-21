package dev.workbench.persistence.repository.view;

import dev.workbench.domain.ToolKind;

public record WriteEvidenceRow(String toolId, Integer version, String resourceVersion) {
  public ToolEvidenceRow withKind() {
    return new ToolEvidenceRow(toolId, ToolKind.WORKSPACE_WRITE.name(), version, resourceVersion);
  }
}
