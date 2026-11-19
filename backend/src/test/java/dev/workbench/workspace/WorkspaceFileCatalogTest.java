package dev.workbench.workspace;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.TestJpa;
import dev.workbench.persistence.repository.WorkspaceDeclaredFileRepository;
import dev.workbench.persistence.repository.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 以独立仓库验证候选范围，不访问用户项目。 */
class WorkspaceFileCatalogTest {
  @Test
  void listsOnlyExistingTrackedFilesAndBoundsResults(@TempDir Path temp) throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo"));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.createDirectory(repo.resolve("路径目录"));
    Files.writeString(repo.resolve("路径目录/中文 名称.java"), "class Example {}", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve("removed.txt"), "removed", StandardCharsets.UTF_8);
    Files.write(repo.resolve("too-large.bin"), new byte[1_000_001]);
    for (int i = 0; i < 105; i++)
      Files.writeString(repo.resolve("file-%03d.txt".formatted(i)), "x");
    git(repo, "add", ".");
    git(repo, "commit", "-m", "fixture");
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var db = new JdbcTemplate(dataSource);
    db.execute(
        "CREATE TABLE workspaces (id VARCHAR(36) PRIMARY KEY, repository_path VARCHAR(2000),"
            + " workspace_path VARCHAR(2000), base_branch VARCHAR(500), base_commit VARCHAR(64),"
            + " branch_name VARCHAR(500), created_at VARCHAR(40))");
    var jpa = new TestJpa(dataSource);
    var service =
        new WorkspaceService(
            jpa.repository(WorkspaceRepository.class),
            jpa.repository(WorkspaceDeclaredFileRepository.class),
            temp.resolve("workspaces").toString());
    var workspace = service.create(repo.toString(), "main");
    Path root = Path.of(workspace.workspacePath());
    Files.writeString(root.resolve("untracked.txt"), "secret");
    Files.delete(root.resolve("removed.txt"));
    var catalog = new WorkspaceFileCatalog(service);

    var all = catalog.find(workspace.id(), "");
    assertEquals(100, all.paths().size());
    assertTrue(all.truncated());
    assertFalse(all.paths().contains("untracked.txt"));
    assertEquals(List.of("路径目录/中文 名称.java"), catalog.find(workspace.id(), "中文 名称").paths());
    assertEquals(List.of("file-000.txt"), catalog.find(workspace.id(), "FILE-000").paths());
    assertTrue(catalog.find(workspace.id(), "removed").paths().isEmpty());
    assertTrue(catalog.find(workspace.id(), "untracked").paths().isEmpty());
    assertTrue(catalog.find(workspace.id(), "too-large").paths().isEmpty());
    assertFalse(catalog.find(workspace.id(), "file-000").truncated());
    assertThrows(IllegalArgumentException.class, () -> catalog.find(workspace.id(), "x\ny"));
    assertThrows(
        IllegalArgumentException.class, () -> catalog.find(workspace.id(), "x".repeat(101)));
    assertThrows(UnsupportedOperationException.class, () -> all.paths().clear());
  }

  private static void git(Path dir, String... args) throws Exception {
    var command = new ArrayList<String>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }
}
