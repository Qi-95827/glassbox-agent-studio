package dev.workbench.framework.memory;

import java.util.List;

/** 混合记忆检索输入；路径集合来自任务授权文件，不包含正文。 */
public record MemorySearchQuery(String text, List<String> pathPatterns, int limit) {
  public MemorySearchQuery {
    text = text == null ? "" : text;
    pathPatterns = pathPatterns == null ? List.of() : List.copyOf(pathPatterns);
    if (limit < 1 || limit > 20) throw new IllegalArgumentException("记忆召回数量应为 1 至 20。");
  }
}
