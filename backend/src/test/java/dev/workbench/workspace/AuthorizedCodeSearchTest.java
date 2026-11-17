package dev.workbench.workspace;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 测试范围、截断、取消及读取失败，不访问用户仓库。 */
class AuthorizedCodeSearchTest {
  @Test
  void searchesOnlyBoundFilesAndReturnsLineAndVersion() {
    var workspaces = mock(WorkspaceService.class);
    when(workspaces.read("workspace", "a.java"))
        .thenReturn(
            new WorkspaceService.FileContent("ignore\nString needle = 1;\nneedle again", "hash-a"));
    when(workspaces.read("workspace", "b.java"))
        .thenReturn(new WorkspaceService.FileContent("Needle\nneedle", "hash-b"));
    var search = new AuthorizedCodeSearch(workspaces);
    var result =
        search.search(
            "workspace", new AuthorizedFileSet(List.of("a.java", "b.java")), "needle", () -> {});
    assertEquals(3, result.matches().size());
    assertEquals("a.java", result.matches().getFirst().path());
    assertEquals(2, result.matches().getFirst().line());
    assertEquals("hash-a", result.matches().getFirst().sha256());
    assertEquals("b.java", result.matches().getLast().path());
    assertEquals(2, result.matches().getLast().line());
    assertFalse(result.truncated());
    verify(workspaces, never()).read("workspace", "secret.java");
  }

  @Test
  void limitsResultsAndExcerptsButStillChecksRemainingFiles() {
    var workspaces = mock(WorkspaceService.class);
    String longLine = "😀".repeat(300) + "needle" + "x".repeat(300);
    when(workspaces.read("workspace", "a.java"))
        .thenReturn(new WorkspaceService.FileContent((longLine + "\n").repeat(51), "hash"));
    when(workspaces.read("workspace", "b.java"))
        .thenReturn(new WorkspaceService.FileContent("nothing", "hash2"));
    var result =
        new AuthorizedCodeSearch(workspaces)
            .search(
                "workspace",
                new AuthorizedFileSet(List.of("a.java", "b.java")),
                "needle",
                () -> {});
    assertEquals(50, result.matches().size());
    assertTrue(result.truncated());
    assertTrue(result.matches().getFirst().lineTruncated());
    assertTrue(result.matches().getFirst().excerpt().contains("needle"));
    assertTrue(
        result
                .matches()
                .getFirst()
                .excerpt()
                .codePointCount(0, result.matches().getFirst().excerpt().length())
            <= 240);
    verify(workspaces).read("workspace", "b.java");
    when(workspaces.read("workspace", "b.java")).thenThrow(new IllegalArgumentException("文件不可读"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuthorizedCodeSearch(workspaces)
                .search(
                    "workspace",
                    new AuthorizedFileSet(List.of("a.java", "b.java")),
                    "needle",
                    () -> {}));
  }

  @Test
  void rejectsInvalidQueriesAndHonorsCancellation() {
    for (String query : List.of("", " ", "x\ny", "x".repeat(101))) {
      assertThrows(IllegalArgumentException.class, () -> AuthorizedCodeSearch.validateQuery(query));
    }
    var workspaces = mock(WorkspaceService.class);
    when(workspaces.read("workspace", "a.java"))
        .thenReturn(new WorkspaceService.FileContent("x\n".repeat(300), "hash"));
    var checks = new AtomicInteger();
    assertThrows(
        IllegalStateException.class,
        () ->
            new AuthorizedCodeSearch(workspaces)
                .search(
                    "workspace",
                    new AuthorizedFileSet(List.of("a.java")),
                    "missing",
                    () -> {
                      if (checks.incrementAndGet() == 2)
                        throw new IllegalStateException("cancelled");
                    }));
    assertEquals(2, checks.get());
  }
}
