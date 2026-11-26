package dev.workbench.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.workbench.planning.PlanDraftConflictAnalyzer.Code;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 编排画布的结构化冲突判定。
 *
 * <p>这些用例锁定「冲突必须带回节点键与重叠路径」：前端只有拿到节点键才能把对应的节点和连线
 * 标红，只返回一句错误文本等于让用户自己去猜哪两个节点打架。
 */
class PlanDraftConflictAnalyzerTest {

  private static PlanCandidateDraft.Node node(
      String key, List<String> dependsOn, List<String> readFiles, List<String> writeFiles) {
    return new PlanCandidateDraft.Node(
        key,
        key,
        PlanCandidateDraft.Role.IMPLEMENT,
        "目标",
        dependsOn,
        readFiles,
        writeFiles,
        List.of(),
        List.of("验收"));
  }

  private static PlanCandidateDraft draft(PlanCandidateDraft.Node... nodes) {
    return new PlanCandidateDraft(
        PlanCandidateDraft.PROTOCOL_VERSION, List.of(nodes), List.of(), List.of(), List.of());
  }

  @Test
  void reportsWriteConflictWithBothNodeKeysAndOverlappingPaths() {
    var analysis =
        PlanDraftConflictAnalyzer.analyze(
            draft(
                node("a", List.of(), List.of(), List.of("src/App.jsx", "README.md")),
                node("b", List.of(), List.of(), List.of("SRC/App.jsx"))));

    assertEquals(1, analysis.conflicts().size());
    var conflict = analysis.conflicts().getFirst();
    assertEquals(Code.WRITE_CONFLICT, conflict.code());
    assertEquals(List.of("a", "b"), conflict.nodeKeys());
    // 路径比较忽略大小写与分隔符差异，否则大小写不同的同一文件会漏判。
    assertEquals(List.of("src/app.jsx"), conflict.paths());
    assertTrue(analysis.byNodeKey().containsKey("a"));
    assertTrue(analysis.byNodeKey().containsKey("b"));
  }

  @Test
  void reportsCycleWithTheNodeChain() {
    var analysis =
        PlanDraftConflictAnalyzer.analyze(
            draft(
                node("a", List.of("c"), List.of(), List.of()),
                node("b", List.of("a"), List.of(), List.of()),
                node("c", List.of("b"), List.of(), List.of())));

    var cycle =
        analysis.conflicts().stream().filter(item -> item.code() == Code.CYCLE).findFirst();
    assertTrue(cycle.isPresent());
    assertEquals(3, cycle.get().nodeKeys().size());
    assertTrue(cycle.get().nodeKeys().contains("a"));
    assertTrue(cycle.get().nodeKeys().contains("b"));
    assertTrue(cycle.get().nodeKeys().contains("c"));
  }

  @Test
  void rejectsAbsoluteTraversalAndBlankPaths() {
    var analysis =
        PlanDraftConflictAnalyzer.analyze(
            draft(node("a", List.of(), List.of("/etc/passwd"), List.of("../secret", " "))));

    var invalid =
        analysis.conflicts().stream()
            .filter(item -> item.code() == Code.INVALID_SCOPE)
            .findFirst()
            .orElseThrow();
    assertEquals(List.of("a"), invalid.nodeKeys());
    assertTrue(invalid.paths().contains("/etc/passwd"));
    assertTrue(invalid.paths().contains("../secret"));
  }

  @Test
  void reportsUnknownDependencyAndDuplicateKey() {
    var analysis =
        PlanDraftConflictAnalyzer.analyze(
            draft(node("a", List.of("ghost"), List.of(), List.of()), node("a", List.of(), List.of(), List.of())));

    assertTrue(analysis.conflicts().stream().anyMatch(item -> item.code() == Code.UNKNOWN_DEPENDENCY));
    assertTrue(analysis.conflicts().stream().anyMatch(item -> item.code() == Code.DUPLICATE_KEY));
    assertFalse(analysis.clean());
  }

  @Test
  void cleanLinearGraphHasNoConflicts() {
    var analysis =
        PlanDraftConflictAnalyzer.analyze(
            draft(
                node("analyze", List.of(), List.of("src/App.jsx"), List.of()),
                node("implement", List.of("analyze"), List.of("src/App.jsx"), List.of("src/App.jsx")),
                node("review", List.of("implement"), List.of("src/App.jsx"), List.of())));

    assertTrue(analysis.clean(), analysis.conflicts().toString());
    assertTrue(analysis.byNodeKey().isEmpty());
  }

  @Test
  void emptyDraftIsTriviallyClean() {
    assertTrue(PlanDraftConflictAnalyzer.analyze(null).clean());
    assertTrue(
        PlanDraftConflictAnalyzer.analyze(
                new PlanCandidateDraft(PlanCandidateDraft.PROTOCOL_VERSION, List.of(), List.of(), List.of()))
            .clean());
  }

  @Test
  void relativePathGuardCoversWindowsAndUnixForms() {
    assertTrue(PlanDraftConflictAnalyzer.isSafeRelative("src/App.jsx"));
    assertTrue(PlanDraftConflictAnalyzer.isSafeRelative("src\\App.jsx"));
    assertFalse(PlanDraftConflictAnalyzer.isSafeRelative("C:\\secret.txt"));
    assertFalse(PlanDraftConflictAnalyzer.isSafeRelative("/etc/passwd"));
    assertFalse(PlanDraftConflictAnalyzer.isSafeRelative("src/../../etc/passwd"));
    assertFalse(PlanDraftConflictAnalyzer.isSafeRelative("  "));
    assertFalse(PlanDraftConflictAnalyzer.isSafeRelative(null));
  }
}
