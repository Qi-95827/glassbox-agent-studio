package dev.workbench.memory;

import java.util.List;

/** 按版本倒序的历史页；下一游标为空表示没有更多记录。 */
public record MemoryHistoryPage(List<MemoryHistoryVersion> items, Integer nextBeforeVersion) {
  public MemoryHistoryPage {
    items = List.copyOf(items);
  }
}
