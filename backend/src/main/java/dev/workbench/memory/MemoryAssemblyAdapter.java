package dev.workbench.memory;

import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.MemoryMatchView;
import dev.workbench.framework.agent.assembly.MemoryAssemblyPort;
import java.util.List;
import org.springframework.stereotype.Component;

/** 把运行记忆引用转换为装配快照所需的纯值对象。 */
@Component
public class MemoryAssemblyAdapter implements MemoryAssemblyPort {
  private final MemoryService memories;

  public MemoryAssemblyAdapter(MemoryService memories) {
    this.memories = memories;
  }

  @Override
  public List<MemoryMatchView> matchesForRun(String runId) {
    if (runId == null || runId.isBlank()) return List.of();
    return memories.loadForRun(runId).references().stream()
        .map(
            reference ->
                new MemoryMatchView(
                    reference.memoryId().toString(),
                    reference.memoryType().name(),
                    reference.score(),
                    reference.matchedReason(),
                    reference.version()))
        .toList();
  }
}
