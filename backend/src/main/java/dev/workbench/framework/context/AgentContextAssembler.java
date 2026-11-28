package dev.workbench.framework.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/** 有序合并上下文来源；注册与装配失败时不调用模型。 */
@Component
public final class AgentContextAssembler {
  /** 0.1 的显式装配上限；超过时阻塞，不静默截断必需约束。 */
  public static final int DEFAULT_MAX_CHARS = 60_000;

  private final List<AgentContextContributor> contributors;
  private final int maxChars;

  @org.springframework.beans.factory.annotation.Autowired
  public AgentContextAssembler(List<AgentContextContributor> contributors) {
    this(contributors, DEFAULT_MAX_CHARS);
  }

  AgentContextAssembler(List<AgentContextContributor> contributors, int maxChars) {
    if (maxChars < 1_000) throw new IllegalArgumentException("上下文限额过小。");
    var ids = new HashSet<String>();
    for (var contributor : contributors) {
      if (contributor == null
          || contributor.id() == null
          || contributor.id().isBlank()
          || !ids.add(contributor.id())) throw new IllegalArgumentException("上下文来源标识缺失或重复。");
    }
    this.contributors =
        contributors.stream()
            .sorted(
                Comparator.comparingInt(AgentContextContributor::order)
                    .thenComparing(AgentContextContributor::id))
            .toList();
    this.maxChars = maxChars;
  }

  public String assemble(AgentContextInput input) {
    return assembleInput(input).text();
  }

  public AgentInput assembleInput(AgentContextInput input) {
    var result = new StringBuilder();
    var sources = new ArrayList<AgentInput.Source>();
    var memoryMatches =
        new ArrayList<dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.MemoryMatchView>();
    for (var contributor : contributors) {
      var section = contributor.contribute(input);
      if (section == null) throw new IllegalStateException("上下文来源返回空值：" + contributor.id());
      if (section.text().isEmpty()) continue;
      result.append(section.text());
      sources.add(
          new AgentInput.Source(
              AgentInput.Kind.CONTRIBUTOR, contributor.id(), contributor.version()));
      sources.addAll(section.sources());
      memoryMatches.addAll(section.memoryMatches());
    }
    if (result.isEmpty()) throw new IllegalStateException("Agent 上下文不能为空。");
    // 必需约束与交接文本整体装配；超过上限时在启动模型前阻塞，避免静默丢失硬约束。
    if (result.length() > maxChars)
      throw new IllegalStateException("Agent 上下文超过 " + maxChars + " 字符上限，已在启动模型前停止，请缩小授权范围或任务输入。");
    return new AgentInput(input.runId(), result.toString(), sources, memoryMatches);
  }
}
