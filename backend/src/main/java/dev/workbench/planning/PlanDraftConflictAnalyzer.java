package dev.workbench.planning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 可视化编排画布的实时校验器。
 *
 * <p>只做「图形层面」的判定：写集互斥、依赖成环、路径越界、未知依赖与重复键。任务契约层面的
 * 规则（产物、独立审查、反思上限等）仍由 {@link PlanCandidateValidator} 负责，两者在接口层
 * 合并输出，避免把同一套规则实现两遍而逐渐分叉。
 *
 * <p>本类保持纯函数：不依赖 Spring、不做 IO，因此可以被单元测试直接驱动，也能在浏览器
 * 每次拖动后高频调用。
 */
public final class PlanDraftConflictAnalyzer {

  /** 冲突类型：前端按 code 决定高亮方式与提示文案。 */
  public enum Code {
    /** 两个并发节点的写入集合重叠。 */
    WRITE_CONFLICT,
    /** 依赖关系构成环。 */
    CYCLE,
    /** 路径越界：绝对路径、目录跳转或空路径。 */
    INVALID_SCOPE,
    /** 依赖了不存在的节点键。 */
    UNKNOWN_DEPENDENCY,
    /** 节点键重复。 */
    DUPLICATE_KEY
  }

  /**
   * 一条结构化冲突。
   *
   * @param nodeKeys 涉及的节点键，按图上的出现顺序去重。
   * @param paths 涉及的路径；成环类冲突为空列表。
   * @param detail 供人类阅读的补充说明，不参与前端分支判断。
   */
  public record Conflict(
      Code code, List<String> nodeKeys, List<String> paths, String detail) {
    public Conflict {
      nodeKeys = nodeKeys == null ? List.of() : List.copyOf(nodeKeys);
      paths = paths == null ? List.of() : List.copyOf(paths);
      detail = detail == null ? "" : detail;
    }
  }

  /** 分析结果：冲突列表与一张「节点键 → 冲突」的索引，便于画布直接高亮。 */
  public record Analysis(List<Conflict> conflicts, Map<String, List<Conflict>> byNodeKey) {
    public Analysis {
      conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
      byNodeKey = byNodeKey == null ? Map.of() : Map.copyOf(byNodeKey);
    }

    public boolean clean() {
      return conflicts.isEmpty();
    }
  }

  private PlanDraftConflictAnalyzer() {}

  public static Analysis analyze(PlanCandidateDraft draft) {
    var conflicts = new ArrayList<Conflict>();
    if (draft == null || draft.nodes().isEmpty()) return new Analysis(conflicts, Map.of());

    Map<String, PlanCandidateDraft.Node> byKey = new LinkedHashMap<>();
    var duplicates = new LinkedHashSet<String>();
    for (var node : draft.nodes()) {
      if (node == null) continue;
      String key = normalizeKey(node.key());
      if (key.isEmpty()) continue;
      if (byKey.putIfAbsent(key, node) != null) duplicates.add(key);
    }
    for (String key : duplicates)
      conflicts.add(new Conflict(Code.DUPLICATE_KEY, List.of(key), List.of(), ""));

    conflicts.addAll(unknownDependencies(draft, byKey));
    conflicts.addAll(cycles(draft, byKey));
    conflicts.addAll(invalidScopes(draft));
    conflicts.addAll(writeConflicts(draft));

    var index = new LinkedHashMap<String, List<Conflict>>();
    for (var conflict : conflicts) {
      for (String key : conflict.nodeKeys())
        index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(conflict);
    }
    var immutable = new LinkedHashMap<String, List<Conflict>>();
    index.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
    return new Analysis(conflicts, immutable);
  }

  private static List<Conflict> unknownDependencies(
      PlanCandidateDraft draft, Map<String, PlanCandidateDraft.Node> byKey) {
    var result = new ArrayList<Conflict>();
    for (var node : draft.nodes()) {
      if (node == null) continue;
      String key = normalizeKey(node.key());
      var missing = new ArrayList<String>();
      for (String dependency : node.dependsOn()) {
        String target = normalizeKey(dependency);
        if (!target.isEmpty() && !byKey.containsKey(target) && !missing.contains(target))
          missing.add(target);
      }
      if (!missing.isEmpty())
        result.add(new Conflict(Code.UNKNOWN_DEPENDENCY, List.of(key), missing, ""));
    }
    return result;
  }

  /**
   * 环检测：沿 dependsOn 做深度优先，第一次遇到回边时把栈上的闭环原样取出。
   *
   * <p>只报告一条环而不是全部环：画布一次只需要高亮一条可视闭环，把全部环都列出来既
   * 难以阅读，也会随节点数指数增长。
   */
  private static List<Conflict> cycles(
      PlanCandidateDraft draft, Map<String, PlanCandidateDraft.Node> byKey) {
    var state = new LinkedHashMap<String, Integer>();
    var stack = new ArrayList<String>();
    for (String key : byKey.keySet()) {
      var cycle = visit(key, byKey, state, stack);
      if (cycle != null) return List.of(new Conflict(Code.CYCLE, cycle, List.of(), ""));
    }
    return List.of();
  }

  private static List<String> visit(
      String key,
      Map<String, PlanCandidateDraft.Node> byKey,
      Map<String, Integer> state,
      List<String> stack) {
    Integer current = state.get(key);
    if (current != null && current == 1) {
      int from = stack.indexOf(key);
      return from < 0 ? List.of(key) : List.copyOf(stack.subList(from, stack.size()));
    }
    if (current != null && current == 2) return null;
    var node = byKey.get(key);
    if (node == null) return null;
    state.put(key, 1);
    stack.add(key);
    for (String dependency : node.dependsOn()) {
      String target = normalizeKey(dependency);
      if (target.isEmpty() || !byKey.containsKey(target)) continue;
      var cycle = visit(target, byKey, state, stack);
      if (cycle != null) return cycle;
    }
    stack.remove(stack.size() - 1);
    state.put(key, 2);
    return null;
  }

  private static List<Conflict> invalidScopes(PlanCandidateDraft draft) {
    var result = new ArrayList<Conflict>();
    for (var node : draft.nodes()) {
      if (node == null) continue;
      var bad = new LinkedHashSet<String>();
      for (String path : node.writeFiles()) if (!isSafeRelative(path)) bad.add(path);
      for (String path : node.readFiles()) if (!isSafeRelative(path)) bad.add(path);
      if (!bad.isEmpty())
        result.add(
            new Conflict(
                Code.INVALID_SCOPE, List.of(normalizeKey(node.key())), List.copyOf(bad), ""));
    }
    return result;
  }

  /** 写集互斥：任意两个节点的写入集合相交即为冲突，与它们是否并发无关。 */
  private static List<Conflict> writeConflicts(PlanCandidateDraft draft) {
    var writers = new ArrayList<PlanCandidateDraft.Node>();
    for (var node : draft.nodes())
      if (node != null && !node.writeFiles().isEmpty()) writers.add(node);

    var result = new ArrayList<Conflict>();
    for (int left = 0; left < writers.size(); left++) {
      for (int right = left + 1; right < writers.size(); right++) {
        var a = writers.get(left);
        var b = writers.get(right);
        var overlap = new ArrayList<String>();
        for (String path : a.writeFiles()) {
          String normalized = normalizePath(path);
          for (String other : b.writeFiles()) {
            if (normalized.equals(normalizePath(other)) && !overlap.contains(normalized))
              overlap.add(normalized);
          }
        }
        if (!overlap.isEmpty())
          result.add(
              new Conflict(
                  Code.WRITE_CONFLICT,
                  List.of(normalizeKey(a.key()), normalizeKey(b.key())),
                  overlap,
                  ""));
      }
    }
    return result;
  }

  private static String normalizeKey(String value) {
    return value == null ? "" : value.strip();
  }

  private static String normalizePath(String value) {
    if (value == null) return "";
    return value.strip().replace('\\', '/').toLowerCase(Locale.ROOT);
  }

  /** 只接受工作区内的相对路径：绝对路径、盘符和 {@code ..} 一律视为越界。 */
  static boolean isSafeRelative(String path) {
    if (path == null || path.isBlank()) return false;
    String normalized = path.strip().replace('\\', '/');
    if (normalized.startsWith("/")) return false;
    if (normalized.matches("^[A-Za-z]:.*")) return false;
    for (String segment : normalized.split("/")) {
      if ("..".equals(segment)) return false;
    }
    return true;
  }

  /** 供前端高亮使用的「(节点键) → 冲突码集合」摘要。 */
  public static Set<Code> codesFor(Analysis analysis, String nodeKey) {
    var codes = new LinkedHashSet<Code>();
    for (var conflict : analysis.byNodeKey().getOrDefault(nodeKey, List.of()))
      codes.add(conflict.code());
    return codes;
  }
}
