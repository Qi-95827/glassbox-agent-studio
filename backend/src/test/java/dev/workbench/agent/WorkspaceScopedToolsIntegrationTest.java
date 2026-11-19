package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.TestJpa;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.tool.AgentToolContext;
import dev.workbench.framework.tool.AgentToolRegistry;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 使用隔离 Git 仓库验证多文件授权、版本冲突和原仓库不受影响。 */
class WorkspaceScopedToolsIntegrationTest {
  @Test
  void scopedToolsReadAndWriteOnlyAuthorizedExistingFiles(@TempDir Path temp) throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo"));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(repo.resolve("a.txt"), "原始 A", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve("b.txt"), "原始 B", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve("secret.txt"), "不授权", StandardCharsets.UTF_8);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "fixture");
    var ds =
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
    var single =
        registry.assemble(
            Map.of(ToolKind.WORKSPACE_READ, 1),
            new AgentToolContext(
                json, created.id(), List.of("a.txt"), Scenario.NORMAL, runtime, failure));
    assertEquals(
        ToolContracts.SINGLE_FILE_VERSION,
        call(json, single, "read_authorized_file", Map.of()).path("toolVersion").asInt());
    verify(runtime).beforeTool(eq(ToolKind.WORKSPACE_READ), eq(1), anyString());

    var toolkit =
        registry.assemble(
            Map.of(
                ToolKind.WORKSPACE_READ,
                2,
                ToolKind.WORKSPACE_WRITE,
                2,
                ToolKind.WORKSPACE_SEARCH,
                1),
            new AgentToolContext(
                json, created.id(), List.of("a.txt", "b.txt"), Scenario.NORMAL, runtime, failure));
    var search = call(json, toolkit, "search_authorized_files", Map.of("query", "原始"));
    assertEquals("SUCCEEDED", search.path("status").asText());
    assertEquals("WORKSPACE_SEARCH", search.path("toolId").asText());
    assertEquals(2, search.path("data").path("matches").size());
    assertEquals("a.txt", search.path("data").path("matches").get(0).path("path").asText());
    assertEquals(1, search.path("data").path("matches").get(0).path("line").asInt());
    assertFalse(search.toString().contains("secret.txt"));
    assertEquals(
        "REJECTED",
        call(json, toolkit, "search_authorized_files", Map.of("query", "\n"))
            .path("status")
            .asText());
    verify(runtime, times(2)).beforeTool(eq(ToolKind.WORKSPACE_SEARCH), eq(1), anyString());

    var a = call(json, toolkit, "read_authorized_file", Map.of("path", "a.txt"));
    var b = call(json, toolkit, "read_authorized_file", Map.of("path", "b.txt"));
    assertEquals("SUCCEEDED", a.path("status").asText());
    assertEquals("原始 A", a.path("data").path("content").asText());
    assertEquals("原始 B", b.path("data").path("content").asText());
    assertEquals(2, a.path("toolVersion").asInt());
    assertEquals(
        "REJECTED",
        call(json, toolkit, "read_authorized_file", Map.of("path", "secret.txt"))
            .path("status")
            .asText());
    assertEquals(
        "REJECTED",
        call(json, toolkit, "read_authorized_file", Map.of("path", "../secret.txt"))
            .path("status")
            .asText());

    String hash = b.path("data").path("sha256").asText();
    var wrongFileVersion = write(json, toolkit, "a.txt", hash, "不能修改 A");
    assertEquals("STALE_VERSION", wrongFileVersion.path("error").path("code").asText());
    var written = write(json, toolkit, "b.txt", hash, "修改 B");
    assertEquals("SUCCEEDED", written.path("status").asText());
    assertEquals(2, written.path("toolVersion").asInt());
    var stale = write(json, toolkit, "b.txt", hash, "再次修改");
    assertEquals("STALE_VERSION", stale.path("error").path("code").asText());
    var frameworkResult =
        write(json, toolkit, "a.txt", a.path("data").path("sha256").asText(), "框架修改 A");
    assertEquals("SUCCEEDED", frameworkResult.path("status").asText());
    assertEquals(2, frameworkResult.path("toolVersion").asInt());
    assertEquals(
        "REJECTED", write(json, toolkit, "secret.txt", hash, "越权").path("status").asText());
    assertEquals("原始 B", Files.readString(repo.resolve("b.txt"), StandardCharsets.UTF_8));
    assertEquals(
        "修改 B",
        Files.readString(
            Path.of(created.workspacePath()).resolve("b.txt"), StandardCharsets.UTF_8));
    assertEquals(
        "框架修改 A",
        Files.readString(
            Path.of(created.workspacePath()).resolve("a.txt"), StandardCharsets.UTF_8));
    assertEquals(
        "不授权",
        Files.readString(
            Path.of(created.workspacePath()).resolve("secret.txt"), StandardCharsets.UTF_8));
    assertNull(failure.get());
  }

  private static com.fasterxml.jackson.databind.JsonNode write(
      ObjectMapper json, Toolkit toolkit, String path, String hash, String content)
      throws Exception {
    return call(
        json,
        toolkit,
        "write_authorized_file",
        Map.of("path", path, "expectedSha256", hash, "content", content));
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
