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
    // 唯一口径：直接复用执行端真正注入 Prompt 的解析结果（含冻结版本回读、归档跳过与真实版本回退），
    // 不再直读原始 references，避免「快照说注入了 v1、模型其实收到 v2/什么都没收到」。
    return memories.frozenMatches(runId).stream()
        .map(
            match ->
                new MemoryMatchView(
                    match.memoryId().toString(),
                    match.memoryType().name(),
                    match.totalScore(),
                    match.matchedReason(),
                    match.memoryVersion()))
        .toList();
  }
}
