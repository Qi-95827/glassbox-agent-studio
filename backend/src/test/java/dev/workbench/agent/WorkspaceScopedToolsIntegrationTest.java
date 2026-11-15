package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.workspace.AuthorizedFileSet;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
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
    var workspace =
        new WorkspaceService(new JdbcTemplate(ds), temp.resolve("worktrees").toString());
    var created = workspace.create(repo.toString(), "main");
    var runtime = mock(AgentRuntime.class);
    when(runtime.beforeTool(any(), anyInt(), anyString()))
        .thenAnswer(invocation -> UUID.randomUUID());
    var json = new ObjectMapper().findAndRegisterModules();
    var scope = new AuthorizedFileSet(List.of("a.txt", "b.txt"));
    var failure = new AtomicReference<RuntimeException>();
    var singleReader =
        new WorkspaceReadTool(workspace, json, created.id(), "a.txt", runtime, failure);
    assertEquals(
        dev.workbench.tool.ToolContracts.SINGLE_FILE_VERSION,
        json.readTree(singleReader.read()).path("toolVersion").asInt());
    verify(runtime)
        .beforeTool(
            eq(dev.workbench.domain.ToolKind.WORKSPACE_READ),
            eq(dev.workbench.tool.ToolContracts.SINGLE_FILE_VERSION),
            anyString());
    var reader =
        new WorkspaceScopedReadTool(workspace, created.id(), scope, json, runtime, failure);
    var writer =
        new WorkspaceScopedWriteTool(workspace, created.id(), scope, json, runtime, failure);
    var toolkit = new io.agentscope.core.tool.Toolkit();
    toolkit.registerTool(reader);
    var call =
        io.agentscope.core.message.ToolUseBlock.builder()
            .id("test-call")
            .name("read_authorized_file")
            .input(java.util.Map.of("path", "a.txt"))
            .content("{\"path\":\"a.txt\"}")
            .build();
    var result =
        toolkit
            .callTool(
                io.agentscope.core.tool.ToolCallParam.builder()
                    .toolUseBlock(call)
                    .input(call.getInput())
                    .build())
            .block();
    assertNotNull(result);
    assertNotEquals(io.agentscope.core.message.ToolResultState.ERROR, result.getState());
    String resultText =
        ((io.agentscope.core.message.TextBlock) result.getOutput().getFirst()).getText();
    var resultJson = json.readTree(resultText);
    if (resultJson.isTextual()) resultJson = json.readTree(resultJson.asText());
    assertEquals("SUCCEEDED", resultJson.path("status").asText());
    var a = json.readTree(reader.read("a.txt"));
    var b = json.readTree(reader.read("b.txt"));
    assertEquals("原始 A", a.path("data").path("content").asText());
    assertEquals("原始 B", b.path("data").path("content").asText());
    assertEquals(2, a.path("toolVersion").asInt());
    assertEquals("REJECTED", json.readTree(reader.read("secret.txt")).path("status").asText());
    assertEquals("REJECTED", json.readTree(reader.read("../secret.txt")).path("status").asText());
    String hash = b.path("data").path("sha256").asText();
    var wrongFileVersion = json.readTree(writer.write("a.txt", hash, "不能修改 A"));
    assertEquals("STALE_VERSION", wrongFileVersion.path("error").path("code").asText());
    var written = json.readTree(writer.write("b.txt", hash, "修改 B"));
    assertEquals("SUCCEEDED", written.path("status").asText());
    assertEquals(2, written.path("toolVersion").asInt());
    var stale = json.readTree(writer.write("b.txt", hash, "再次修改"));
    assertEquals("STALE_VERSION", stale.path("error").path("code").asText());
    toolkit.registerTool(writer);
    java.util.Map<String, Object> writeInput =
        java.util.Map.of(
            "path",
            "a.txt",
            "expectedSha256",
            a.path("data").path("sha256").asText(),
            "content",
            "框架修改 A");
    var writeCall =
        io.agentscope.core.message.ToolUseBlock.builder()
            .id("test-write")
            .name("write_authorized_file")
            .input(writeInput)
            .content(json.writeValueAsString(writeInput))
            .build();
    var frameworkWrite =
        toolkit
            .callTool(
                io.agentscope.core.tool.ToolCallParam.builder()
                    .toolUseBlock(writeCall)
                    .input(writeCall.getInput())
                    .build())
            .block();
    assertNotNull(frameworkWrite);
    assertNotEquals(io.agentscope.core.message.ToolResultState.ERROR, frameworkWrite.getState());
    var frameworkResult =
        json.readTree(
            ((io.agentscope.core.message.TextBlock) frameworkWrite.getOutput().getFirst())
                .getText());
    if (frameworkResult.isTextual()) frameworkResult = json.readTree(frameworkResult.asText());
    assertEquals("SUCCEEDED", frameworkResult.path("status").asText());
    assertEquals(2, frameworkResult.path("toolVersion").asInt());
    var denied = json.readTree(writer.write("secret.txt", hash, "越权"));
    assertEquals("REJECTED", denied.path("status").asText());
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

  private static void git(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }
}
