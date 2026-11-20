package dev.workbench.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.task.RunRecordService;
import dev.workbench.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:idea_endpoints;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-idea-tests"
    })
class TaskIdeaEndpointIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired WorkspaceService workspaces;
  @Autowired RunRecordService runs;

  @Test
  void vagueIdeaPreparesOnlyConfirmedFilesAndPlanWaitsForApproval() throws Exception {
    int workspaceCount = workspaces.list().size();
    int taskCount = runs.tasks().size();
    var draft = postJson("/api/task-ideas/draft", Map.of("idea", "做个冒泡排序网页，可以点下一步看柱子交换。"));
    assertEquals("CODE", draft.path("entryKind").asText());
    assertEquals("index.html", draft.path("suggestedFiles").get(0).asText());
    assertTrue(draft.path("acceptanceCriteria").toString().contains("1,2,4,5,8"));
    assertEquals(workspaceCount, workspaces.list().size());
    assertEquals(taskCount, runs.tasks().size());

    var workspace = postJson("/api/workspaces/from-idea", Map.of("files", List.of("index.html")));
    String workspaceId = workspace.path("id").asText();
    assertEquals("", workspaces.read(workspaceId, "index.html").content());
    var created =
        postJson(
            "/api/tasks",
            Map.of(
                "goal",
                draft.path("goal").asText(),
                "entryKind",
                "CODE",
                "workspaceId",
                workspaceId,
                "fileScopes",
                List.of("index.html"),
                "writeEnabled",
                true,
                "modelMode",
                "mock",
                "acceptanceCriteria",
                draft.path("acceptanceCriteria"),
                "reviewPlan",
                true));
    String runId = created.path("runId").asText();
    assertEquals("PENDING", runs.run(runId).get("STATUS"));
    assertTrue(runs.models(runId).isEmpty());
    assertTrue(runs.tools(runId).isEmpty());
    assertEquals(
        List.of("index.html"),
        runs.taskDefinition(runs.runSnapshot(runId).taskId().toString()).fileScopes());
  }

  @Test
  void rejectsUnsafeFileBeforeCreatingWorkspaceAndExistingProjectDraftDoesNotSuggestOne()
      throws Exception {
    int before = workspaces.list().size();
    mvc.perform(
            post("/api/workspaces/from-idea")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":[\"../index.html\"]}"))
        .andExpect(status().isBadRequest());
    var draft = postJson("/api/task-ideas/draft", Map.of("idea", "修复已有仓库里的网页"));
    assertEquals("CODE", draft.path("entryKind").asText());
    assertTrue(draft.path("suggestedFiles").isEmpty());
    assertEquals(before, workspaces.list().size());
  }

  private JsonNode postJson(String path, Object body) throws Exception {
    var result =
        mvc.perform(
                post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body)))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsByteArray());
  }
}
