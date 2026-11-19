package dev.workbench.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.task.RunRecordService;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 从页面使用的 HTTP 入口创建任务、接入真实需求文件并查看运行产物。 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task8page;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task8-page"
    })
class Task8PageAcceptanceIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired RunRecordService records;
  @Autowired WorkspaceService workspaces;
  @TempDir Path temp;

  @Test
  void pageFlowCreatesDocumentThenDesignAndShowsContentAndEvidence() throws Exception {
    var workspace = workspace();
    String source = createTask("整理需求", "REQUIREMENT", workspace.id(), List.of("requirements.md"), false);
    waitTerminal(source);
    JsonNode sourceDetail = detail(source);
    String sourceTaskVersion = sourceDetail.path("task").path("VERSION").asText();
    assertEquals("REQUIREMENT_TEXT_RULE".replace("TEXT", "WORKSPACE"), sourceDetail.path("entryRoute").path("RULE_CODE").asText());

    String target = createTask("根据需求设计", "DESIGN", workspace.id(), List.of("requirements.md"), true);
    JsonNode targetDetail = detail(target);
    String targetTaskVersion = targetDetail.path("task").path("VERSION").asText();
    String attach =
        mvc.perform(
                post("/api/runs/" + target + "/source-artifacts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            java.util.Map.of(
                                "sourceRunId", source,
                                "kind", "DOCUMENT",
                                "expectedTargetTaskVersion", Integer.parseInt(targetTaskVersion),
                                "expectedSourceTaskVersion", Integer.parseInt(sourceTaskVersion)))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(json.readTree(attach).path("contentPreview").asText().contains("需求正文：登录必须记录失败次数"));

    JsonNode pending = detail(target);
    assertTrue(
        pending.path("sourceArtifacts").get(0).path("summary").asText().contains("来源文档"));
    assertTrue(
        pending.path("sourceArtifacts").get(0).path("contentPreview").asText().contains("需求正文"));
    String planId = pending.path("workflowPlan").path("id").asText();
    mvc.perform(post("/api/runs/" + target + "/plans/" + planId + "/approve"))
        .andExpect(status().isOk());
    waitTerminal(target);
    JsonNode completed = detail(target);
    assertFalse(completed.path("run").path("RESULT").asText().isBlank());
    assertTrue(completed.path("sourceArtifacts").size() >= 1);
    assertTrue(completed.path("entryRoute").path("DECISION_SHA256").asText().length() == 64);
  }

  private String createTask(
      String goal, String entryKind, String workspaceId, List<String> files, boolean reviewPlan)
      throws Exception {
    String response =
        mvc.perform(
                post("/api/tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            new java.util.LinkedHashMap<String, Object>() {
                              {
                                put("goal", goal);
                                put("scenario", "normal");
                                put("maxRounds", 4);
                                put("maxTools", 2);
                                put("maxSeconds", 60);
                                put("modelMode", "mock");
                                put("workspaceId", workspaceId);
                                put("fileScopes", files);
                                put("writeEnabled", false);
                                put("entryKind", entryKind);
                                put("reviewPlan", reviewPlan);
                              }
                            })))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response).path("runId").asText();
  }

  private JsonNode detail(String runId) throws Exception {
    return json.readTree(
        mvc.perform(get("/api/runs/" + runId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private void waitTerminal(String runId) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (records.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline) fail("运行未结束：" + runId);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
  }

  private WorkspaceService.Workspace workspace() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo"));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(
        repo.resolve("requirements.md"),
        "需求正文：登录必须记录失败次数，并在连续失败后锁定账户。\n",
        StandardCharsets.UTF_8);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "requirement");
    return workspaces.create(repo.toString(), "main");
  }

  private void git(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.add("-C");
    command.add(dir.toString());
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }
}
