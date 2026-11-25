package dev.workbench.workspace;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.tool.ConservativeToolRiskPolicy;
import dev.workbench.framework.tool.ToolEffect;
import dev.workbench.framework.tool.ToolRiskContext;
import dev.workbench.framework.tool.ToolRiskLevel;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.store.RunRecordService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 整个工作区（{@code "."}）目录授权：默认噪音排除、上限提示、运行前复验与越界拒绝。
 *
 * <p>同时核对敏感文件仍在有效范围内，从而继续由 Gate 5.5 风险策略拦截。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:whole_workspace_scope;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-whole-scope"
    })
class WorkspaceWholeScopeAuthorizationIntegrationTest {
  @Autowired RunService runs;
  @Autowired RunRecordService records;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceAuthorizationService authorization;
  @TempDir Path temp;

  @Test
  void wholeWorkspaceScopeExpandsAndExcludesNoiseDirectoriesByDefault() throws Exception {
    var workspace = repository();
    String run = create(workspace.id(), List.of("."), List.of());
    String taskId = records.runSnapshot(run).taskId().toString();

    var saved = authorization.find(taskId);
    assertEquals(List.of("."), saved.get("DIRECTORY_SCOPES"));
    assertEquals(List.of(), saved.get("EXCLUDED_PATHS"));
    List<String> effective = effectiveFiles(saved);
    assertEquals(
        List.of(
            "README.md",
            "application.yml",
            "docs/guide.md",
            "package.json",
            "pom.xml",
            "src/keep.py",
            "src/skip.py"),
        effective,
        "整个工作区展开必须排除构建/依赖噪音目录：" + effective);
    assertEquals(effective, records.taskDefinition(taskId).fileScopes());
    authorization.requireCurrent(taskId);
    // 运行前复验同样接受 "./" 写法，不需要用户已经手工归一化。
    workspaces.requireDirectoryStillAuthorized(workspace.id(), List.of("./"), List.of(), effective);
  }

  @Test
  void dotSlashNormalizesToRootAndUserExclusionsMerge() throws Exception {
    var workspace = repository();
    String run = create(workspace.id(), List.of("./"), List.of("docs", "src/skip.py"));
    String taskId = records.runSnapshot(run).taskId().toString();

    var saved = authorization.find(taskId);
    assertEquals(List.of("."), saved.get("DIRECTORY_SCOPES"));
    assertEquals(List.of("docs", "src/skip.py"), saved.get("EXCLUDED_PATHS"));
    assertEquals(
        List.of("README.md", "application.yml", "package.json", "pom.xml", "src/keep.py"),
        effectiveFiles(saved));
  }

  @Test
  void subdirectoryScopeStillWorksAndKeepsGitMetadataOut() throws Exception {
    var workspace = repository();
    String run = create(workspace.id(), List.of("src"), List.of());
    String taskId = records.runSnapshot(run).taskId().toString();
    // 子目录范围不套用整个工作区的默认排除规则，但仍只允许文本文件。
    assertEquals(List.of("src/keep.py", "src/skip.py"), effectiveFiles(authorization.find(taskId)));
  }

  @Test
  void moreThanTwoHundredFilesIsRejectedWithActionableGuidance() throws Exception {
    var workspace = manyFiles(205);
    var failure =
        assertThrows(
            IllegalArgumentException.class, () -> create(workspace.id(), List.of("."), List.of()));
    assertTrue(
        failure.getMessage() != null && failure.getMessage().contains("200"),
        "超限提示必须说明上限：" + failure.getMessage());
  }

  @Test
  void reverifyRejectsRemovedFilesAndNewlyExcludedPaths() throws Exception {
    var workspace = repository();
    String run = create(workspace.id(), List.of("."), List.of());
    String taskId = records.runSnapshot(run).taskId().toString();
    List<String> effective = effectiveFiles(authorization.find(taskId));
    WorkspaceService.Workspace saved = workspaces.get(workspace.id());

    // 授权文件被删除后运行前复验必须拒绝旧范围。
    Files.delete(Path.of(saved.workspacePath()).resolve("src/keep.py"));
    assertThrows(IllegalStateException.class, () -> authorization.requireCurrent(taskId));
    assertThrows(
        IllegalStateException.class,
        () ->
            workspaces.requireDirectoryStillAuthorized(
                workspace.id(), List.of("."), List.of(), effective));

    // 排除规则收紧后，原本已冻结的文件同样被拒绝（防止借旧快照绕过新排除项）。
    assertThrows(
        IllegalStateException.class,
        () ->
            authorization.reverifyDirectories(
                workspace.id(), List.of("."), List.of("src"), List.of("src/skip.py", "README.md")));
  }

  @Test
  void traversalAndGitMetadataScopesAreRejected() throws Exception {
    var workspace = repository();
    for (List<String> directories :
        List.of(
            List.of(".."),
            List.of("../outside"),
            List.of("./../outside"),
            List.of(".git"),
            List.of("/absolute"))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> create(workspace.id(), directories, List.of()),
          "目录范围必须拒绝越界写法：" + directories);
    }
  }

  /** 敏感文件仍在整个工作区范围内，写入风险由 Gate 5.5 策略继续判定为 HIGH。 */
  @Test
  void sensitiveFilesStayInScopeAndRemainHighRiskForWrites() throws Exception {
    var workspace = repository();
    String run = create(workspace.id(), List.of("."), List.of());
    String taskId = records.runSnapshot(run).taskId().toString();
    List<String> effective = effectiveFiles(authorization.find(taskId));
    assertTrue(effective.containsAll(List.of("pom.xml", "application.yml", "package.json")));

    var policy = new ConservativeToolRiskPolicy();
    for (String path : List.of("pom.xml", "application.yml", "package.json", ".env")) {
      assertEquals(
          ToolRiskLevel.HIGH,
          policy
              .assess(
                  new ToolRiskContext(
                      "workspace.write", ToolEffect.WRITE, "{}", List.of(path)))
              .level(),
          "敏感文件写入必须继续触发 Gate 5.5 审批：" + path);
    }
    assertTrue(
        policy
            .assess(
                new ToolRiskContext(
                    "workspace.write", ToolEffect.WRITE, "{}", List.of("src/keep.py")))
            .level()
            != ToolRiskLevel.HIGH,
        "普通授权文件写入不应被误判为高危");
  }

  @SuppressWarnings("unchecked")
  private static List<String> effectiveFiles(java.util.Map<String, Object> saved) {
    return (List<String>) saved.get("EFFECTIVE_FILES");
  }

  private String create(String workspaceId, List<String> directories, List<String> exclusions) {
    return runs.create(
        CreateTaskRequest.builder()
            .goal("整个工作区目录授权验证")
            .scenario("normal")
            .limits(2, 1, 30)
            .modelMode("mock")
            .workspace(workspaceId, null)
            .writeEnabled(true)
            .entryKind(TaskEntryKind.CODE)
            .directoryScopes(directories)
            .excludedPaths(exclusions)
            .reviewPlan(true)
            .build());
  }

  private WorkspaceService.Workspace repository() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo-" + System.nanoTime()));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    write(repo, "README.md", "# 示例仓库\n");
    write(repo, "docs/guide.md", "# 使用说明\n");
    write(repo, "src/keep.py", "def keep():\n    return 1\n");
    write(repo, "src/skip.py", "SECRET = 'skip'\n");
    write(repo, "pom.xml", "<project/>\n");
    write(repo, "application.yml", "server:\n  port: 8080\n");
    write(repo, "package.json", "{\"name\":\"fixture\"}\n");
    // 这些目录在展开整个工作区时必须被默认排除，即使它们已被 Git 跟踪。
    write(repo, "node_modules/pkg/index.js", "module.exports = 1;\n");
    write(repo, "target/classes/App.java.txt", "class App {}\n");
    write(repo, "dist/app.js", "console.log(1);\n");
    write(repo, "build/out.txt", "out\n");
    write(repo, ".venv/lib/site.py", "x = 1\n");
    write(repo, "__pycache__/module.py", "x = 1\n");
    write(repo, ".idea/workspace.xml", "<project/>\n");
    write(repo, ".vscode/settings.json", "{}\n");
    git(repo, "add", "-A");
    git(repo, "commit", "-m", "fixture");
    return workspaces.create(repo.toString(), "main");
  }

  private WorkspaceService.Workspace manyFiles(int count) throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo-many-" + System.nanoTime()));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    for (int index = 0; index < count; index++)
      write(repo, "src/file-%03d.txt".formatted(index), "x\n");
    git(repo, "add", "-A");
    git(repo, "commit", "-m", "many files");
    return workspaces.create(repo.toString(), "main");
  }

  private static void write(Path repo, String relative, String content) throws Exception {
    Path target = repo.resolve(relative);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
  }

  private void git(Path dir, String... args) throws Exception {
    var command = new ArrayList<String>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }
}
