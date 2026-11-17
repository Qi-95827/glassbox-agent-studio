package dev.workbench.framework.context;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/** 有序合并上下文来源；注册与装配失败时不调用模型。 */
@Component
public final class AgentContextAssembler {
  private final List<AgentContextContributor> contributors;

  public AgentContextAssembler(List<AgentContextContributor> contributors) {
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
  }

  public String assemble(AgentContextInput input) {
    var result = new StringBuilder();
    for (var contributor : contributors) {
      String section = contributor.contribute(input);
      if (section == null) throw new IllegalStateException("上下文来源返回空值：" + contributor.id());
      result.append(section);
    }
    if (result.isEmpty()) throw new IllegalStateException("Agent 上下文不能为空。");
    return result.toString();
  }
}
