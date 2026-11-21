package dev.workbench.persistence.repository.view;

import dev.workbench.domain.ToolKind;
import dev.workbench.framework.workflow.plan.WorkflowHandoff.ToolEvidence;
import java.util.UUID;

public record ToolEvidenceRow(
    String toolId, String kind, Integer version, String resourceVersion) {
  public ToolEvidence toEvidence() {
    return new ToolEvidence(
        UUID.fromString(toolId), ToolKind.valueOf(kind), version, resourceVersion);
  }
}
