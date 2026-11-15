package dev.workbench.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 明确列出的相对文件才能被工具选择，路径别名不能越过集合。 */
class AuthorizedFileSetTest {
  @Test
  void normalizesAndRejectsOutsideOrDuplicatePaths() {
    var files = new AuthorizedFileSet(List.of("src\\a.txt", "src/b.txt"));
    assertEquals(List.of("src/a.txt", "src/b.txt"), files.paths());
    assertEquals("src/a.txt", files.require("src/./a.txt"));
    assertThrows(IllegalArgumentException.class, () -> files.require("src/../secret.txt"));
    assertThrows(IllegalArgumentException.class, () -> files.require("../secret.txt"));
    assertThrows(IllegalArgumentException.class, () -> files.require("C:/secret.txt"));
    assertThrows(IllegalArgumentException.class, () -> files.require(".git/config"));
    assertThrows(IllegalArgumentException.class, () -> files.require("src/a\n.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthorizedFileSet(List.of("src/a.txt", "src/./a.txt")));
    assertThrows(IllegalArgumentException.class, () -> new AuthorizedFileSet(List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthorizedFileSet(java.util.Collections.nCopies(21, "a")));
  }
}
