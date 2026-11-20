package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.model.AgentModelProvider;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.task.DeliveryAccessDeniedException;
import dev.workbench.task.RunDeliveryService;
import dev.workbench.task.RunRecordService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/** P08：目录撤权后，即使文件内容未变也不能读取交付正文；历史回执仍可查。 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:delivery_revocation;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-delivery-revocation"
    })
@Import(DeliveryRevocationIntegrationTest.ScriptedModels.class)
class DeliveryRevocationIntegrationTest {
  @TestConfiguration
  static class ScriptedModels {
    @Bean
    @Primary
    AgentModelRegistry scriptedAgentModelRegistry() {
      return new AgentModelRegistry(
          List.of(
              new AgentModelProvider() {
                @Override
                public ModelMode mode() {
                  return ModelMode.MOCK;
                }

                @Override
                public io.agentscope.core.model.Model create(AgentExecutionRequest request) {
                  var contents = new LinkedHashMap<String, String>();
                  for (String path : request.writeFiles())
                    contents.put(path, "written:" + path + "\n");
                  return new ScriptedWriteModel(
                      request.role(), request.fileScopes(), request.writeFiles(), contents);
                }
              }));
    }
  }

  @Autowired RunService runs;
  @Autowired RunRecordService records;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceAuthorizationService workspaceAuthorization;
  @Autowired RunDeliveryService deliveries;
  @Autowired MockMvc mvc;
  @TempDir Path temp;

  @Test
  void revokedDirectoryHidesDeliveryContentButKeepsHistoricalReceipt() throws Exception {
    var workspace = workspace();
    String runId =
        runs.create(
            new CreateTaskRequest(
                "验证撤权后的交付正文",
                "normal",
                20,
                20,
                60,
                "mock",
                workspace.id(),
                null,
                true,
                null,
                false,
                null,
                List.of(),
                List.of("写入后仍可由人工核对"),
                null,
                null,
                TaskEntryKind.CODE,
                null,
                null,
                null,
                null,
                null,
                "workspace-change",
                false,
                null,
                List.of("src", "docs"),
                List.of(),
                null));
    waitTerminal(runId);
    String taskId = records.runSnapshot(runId).taskId().toString();

    var before = deliveries.file(runId, "src/a.txt");
    assertTrue(before.currentMatches());
    assertTrue(before.content().contains("written:src/a.txt"));
    String contentSha = workspaces.read(workspace.id(), "src/a.txt").sha256();

    // 内容不变，只撤销覆盖该文件的目录授权。
    workspaceAuthorization.revoke(taskId, "src");
    assertEquals(contentSha, workspaces.read(workspace.id(), "src/a.txt").sha256());
    assertThrows(DeliveryAccessDeniedException.class, () -> deliveries.file(runId, "src/a.txt"));

    // 历史回执仍可查，正文不可读。
    var delivery = deliveries.delivery(runId);
    var historical =
        delivery.files().stream()
            .filter(file -> file.path().equals("src/a.txt"))
            .findFirst()
            .orElseThrow();
    assertEquals(before.receiptSha256(), historical.sha256());
    assertEquals(contentSha, historical.currentSha256());
    assertTrue(historical.currentMatches());
    mvc.perform(get("/api/runs/{id}/delivery/files", runId).param("path", "src/a.txt"))
        .andExpect(status().isForbidden());
    // 未撤销的目录仍可读取正文。
    assertTrue(deliveries.file(runId, "docs/keep.txt").content().contains("written:docs/keep.txt"));
  }

  private WorkspaceService.Workspace workspace() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo"));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.createDirectories(repo.resolve("src"));
    Files.createDirectories(repo.resolve("docs"));
    Files.writeString(repo.resolve("src/a.txt"), "a-v1\n", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve("src/b.txt"), "b-v1\n", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve("docs/keep.txt"), "keep-v1\n", StandardCharsets.UTF_8);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "fixture");
    return workspaces.create(repo.toString(), "main");
  }

  private void waitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (records.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline) fail("运行未结束：" + records.runSnapshot(runId).status());
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
  }

  private void git(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }
}
