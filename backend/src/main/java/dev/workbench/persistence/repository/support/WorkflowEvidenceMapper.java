package dev.workbench.persistence.repository.support;

import dev.workbench.framework.workflow.plan.WorkflowHandoff.ToolEvidence;
import dev.workbench.persistence.repository.view.ToolEvidenceRow;
import dev.workbench.persistence.repository.view.WriteEvidenceRow;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 工具调用与接收证据行到领域证据的组装。 */
public final class WorkflowEvidenceMapper {
  private WorkflowEvidenceMapper() {}

  public static List<ToolEvidence> toEvidence(List<ToolEvidenceRow> rows) {
    return rows.stream().map(ToolEvidenceRow::toEvidence).toList();
  }

  /** 读取证据与写入证据按工具调用 id 合并，保持原有稳定顺序。 */
  public static List<ToolEvidence> merge(
      List<ToolEvidenceRow> reads, List<WriteEvidenceRow> writes) {
    return Stream.concat(reads.stream(), writes.stream().map(WriteEvidenceRow::withKind))
        .sorted(Comparator.comparing(ToolEvidenceRow::toolId))
        .map(ToolEvidenceRow::toEvidence)
        .toList();
  }
}
