package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.TestJpa;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.tool.AgentToolContext;
import dev.workbench.infrastructure.agentscope.AgentToolRegistry;
import dev.workbench.framework.tool.ToolContracts;
import dev.workbench.persistence.repository.WorkspaceDeclaredFileRepository;
import dev.workbench.persistence.repository.WorkspaceRepository;
import dev.workbench.workspace.WorkspaceService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 节点级读写范围在工具入口独立生效，不能借另一节点的授权读写文件。 */
class NodeScopeToolIntegrationTest {
  @Test
  void nodeScopesDenySiblingFilesAndVersionFollowsWriteCount(@TempDir Path temp) throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo"));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(repo.resolve("sort.js"), "sort-v1\n", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve("index.html"), "page-v1\n", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve("style.css"), "style-v1\n", StandardCharsets.UTF_8);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "fixture");
    DataSource ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    try (var connection = ds.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
    }
    var jpa = new TestJpa(ds);
    var workspace =
        new WorkspaceService(
            jpa.repository(WorkspaceRepository.class),
            jpa.repository(WorkspaceDeclaredFileRepository.class),
            temp.resolve("worktrees").toString());
    var created = workspace.create(repo.toString(), "main");
    var runtime = mock(AgentRuntime.class);
    when(runtime.beforeTool(any(), anyInt(), anyString()))
        .thenAnswer(invocation -> UUID.randomUUID());
    var json = new ObjectMapper().findAndRegisterModules();
    var failure = new AtomicReference<RuntimeException>();
    var providers = new BuiltInToolProviders();
    var registry =
        new AgentToolRegistry(
            List.of(
                providers.workspaceReadProvider(workspace),
                providers.workspaceSearchProvider(workspace),
                providers.workspaceWriteProvider(workspace)));

    // 节点 A：读写 sort.js 与 index.html；style.css 属于另一节点。
    var nodeA =
        registry.assemble(
            Map.of(
                ToolKind.WORKSPACE_READ,
                ToolContracts.MULTI_FILE_VERSION,
                ToolKind.WORKSPACE_WRITE,
                ToolContracts.MULTI_FILE_VERSION),
            new AgentToolContext(
                json,
                created.id(),
                List.of("sort.js", "index.html"),
                Scenario.NORMAL,
                runtime,
                failure,
                List.of("sort.js", "index.html")));
    String sortSha =
        call(json, nodeA, "read_authorized_file", Map.of("path", "sort.js"))
            .path("data")
            .path("sha256")
            .asText();
    assertEquals(
        "SUCCEEDED",
        call(
                json,
                nodeA,
                "write_authorized_file",
                Map.of("path", "sort.js", "expectedSha256", sortSha, "content", "sort-v2\n"))
            .path("status")
            .asText());
    assertEquals(
        "REJECTED",
        call(json, nodeA, "read_authorized_file", Map.of("path", "style.css"))
            .path("status")
            .asText());
    String indexSha =
        call(json, nodeA, "read_authorized_file", Map.of("path", "index.html"))
            .path("data")
            .path("sha256")
            .asText();
    assertEquals(
        "REJECTED",
        call(
                json,
                nodeA,
                "write_authorized_file",
                Map.of("path", "style.css", "expectedSha256", indexSha, "content", "hacked\n"))
            .path("status")
            .asText());
    assertTrue(workspace.read(created.id(), "style.css").content().startsWith("style-v1"));
    assertEquals("sort-v2\n", workspace.read(created.id(), "sort.js").content());

    // 节点 B：只写 index.html；写入契约按写入数量选择，不能借读取范围扩大。
    var nodeB =
        registry.assemble(
            Map.of(
                ToolKind.WORKSPACE_READ,
                ToolContracts.MULTI_FILE_VERSION,
                ToolKind.WORKSPACE_WRITE,
                ToolContracts.SINGLE_FILE_VERSION),
            new AgentToolContext(
                json,
                created.id(),
                List.of("index.html", "style.css"),
                Scenario.NORMAL,
                runtime,
                failure,
                List.of("index.html")));
    assertEquals(
        "REJECTED",
        call(json, nodeB, "read_authorized_file", Map.of("path", "sort.js"))
            .path("status")
            .asText());
    String pageSha =
        call(json, nodeB, "read_authorized_file", Map.of("path", "index.html"))
            .path("data")
            .path("sha256")
            .asText();
    assertEquals(
        "SUCCEEDED",
        call(
                json,
                nodeB,
                "write_authorized_file",
                Map.of("expectedSha256", pageSha, "content", "page-v2\n"))
            .path("status")
            .asText());
    assertEquals("page-v2\n", workspace.read(created.id(), "index.html").content());
    assertEquals("sort-v2\n", workspace.read(created.id(), "sort.js").content());
  }

  private static com.fasterxml.jackson.databind.JsonNode call(
      ObjectMapper json, Toolkit toolkit, String name, Map<String, Object> input) throws Exception {
    var request =
        ToolUseBlock.builder()
            .id(UUID.randomUUID().toString())
            .name(name)
            .input(input)
            .content(json.writeValueAsString(input))
            .build();
    var result =
        toolkit
            .callTool(ToolCallParam.builder().toolUseBlock(request).input(input).build())
            .block();
    assertNotNull(result);
    assertNotEquals(ToolResultState.ERROR, result.getState());
    return json.readTree(((TextBlock) result.getOutput().getFirst()).getText());
  }

  private static void git(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }
}
