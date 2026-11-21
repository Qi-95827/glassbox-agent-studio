package dev.workbench.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import dev.workbench.framework.planning.PlanCandidateModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 两个普通账号之间的服务端归属隔离；管理员能添加用户并领取旧数据。 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:auth_ownership;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-auth-ownership"
    })
@Import(AuthOwnershipIntegrationTest.StubPlan.class)
class AuthOwnershipIntegrationTest {
  @TestConfiguration
  static class StubPlan {
    @Bean
    @Primary
    PlanCandidateModel stubPlan() {
      return (instructions, input) ->
          new PlanCandidateModel.Reply(
              "{\"schemaVersion\":1,\"nodes\":[],\"assumptions\":[],\"openQuestions\":[]}",
              "stub",
              1,
              1);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @TempDir Path temp;

  @Test
  void adminAddsUsersAndOrdinaryUsersCannotReachEachOther() throws Exception {
    MockHttpSession admin = initializeAdmin();
    mvc.perform(post("/api/auth/users").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "username", "alice", "password", "alice-pass-1", "displayName", "Alice", "role", "USER"))))
        .andExpect(status().isOk());
    mvc.perform(post("/api/auth/users").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "username", "bob", "password", "bob-pass-1", "displayName", "Bob", "role", "USER"))))
        .andExpect(status().isOk());
    MockHttpSession alice = login("alice", "alice-pass-1");
    MockHttpSession bob = login("bob", "bob-pass-1");
    var csrfBody = json.readTree(mvc.perform(get("/api/auth/csrf").session(alice))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertTrue(csrfBody.path("token").asText().length() >= 20);

    // 先授权再调用 Git：未授权的普通目录也应返回 403，而不是 Git 解析错误。
    Path plain = Files.createDirectory(temp.resolve("plain"));
    mvc.perform(post("/api/workspaces").with(csrf()).session(alice).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "repositoryPath", plain.toString(), "baseBranch", "main"))))
        .andExpect(status().isForbidden());
    grant(admin, "alice", plain);
    mvc.perform(post("/api/workspaces").with(csrf()).session(alice).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "repositoryPath", plain.toString(), "baseBranch", "main"))))
        .andExpect(status().isBadRequest());

    // 仓库导入授权：未授权不能导入，管理员显式授权后可以导入。
    Path repo = gitRepo();
    mvc.perform(post("/api/workspaces").with(csrf()).session(alice).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "repositoryPath", repo.toString(), "baseBranch", "main"))))
        .andExpect(status().isForbidden());
    grant(admin, "alice", repo);
    var created =
        mvc.perform(post("/api/workspaces").with(csrf()).session(alice).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "repositoryPath", repo.toString(), "baseBranch", "main"))))
            .andExpect(status().isOk())
            .andReturn();
    String workspaceId = json.readTree(created.getResponse().getContentAsString()).path("id").asText();
    mvc.perform(post("/api/workspaces").with(csrf()).session(bob).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "repositoryPath", repo.toString(), "baseBranch", "main"))))
        .andExpect(status().isForbidden());
    grant(admin, "bob", repo);
    mvc.perform(post("/api/workspaces").with(csrf()).session(bob).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "repositoryPath", repo.toString(), "baseBranch", "main"))))
        .andExpect(status().isOk());

    // 工作区归属：乙看不到甲的工作区，也不能在创建任务时引用。
    assertEquals(1, json.readTree(mvc.perform(get("/api/workspaces").session(bob))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).size());
    mvc.perform(post("/api/tasks").with(csrf()).session(bob).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(taskBody(workspaceId))))
        .andExpect(status().isForbidden());

    // 撤销导入授权只阻止新导入，已有工作区仍可访问。
    var grants = json.readTree(mvc.perform(get("/api/admin/repositories").session(admin))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    String aliceGrant = null;
    for (var grant : grants)
      if ("alice".equals(grant.path("username").asText())
          && repo.equals(Path.of(grant.path("repositoryRoot").asText())))
        aliceGrant = grant.path("id").asText();
    assertNotNull(aliceGrant);
    mvc.perform(delete("/api/admin/repositories/{id}", aliceGrant).with(csrf()).session(admin))
        .andExpect(status().isOk());
    mvc.perform(get("/api/workspaces/{id}/status", workspaceId).session(alice))
        .andExpect(status().isOk());
    mvc.perform(post("/api/workspaces").with(csrf()).session(alice).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "repositoryPath", repo.toString(), "baseBranch", "main"))))
        .andExpect(status().isForbidden());
    grant(admin, "alice", repo);

    // 已登录的状态修改请求缺少 CSRF token 时被拒绝。
    mvc.perform(post("/api/tasks").session(alice).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(taskBody(null))))
        .andExpect(status().isForbidden());

    // 任务、运行、报告和交付下载都按任务归属校验。
    var task =
        mvc.perform(post("/api/tasks").with(csrf()).session(alice).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(taskBody(null))))
            .andExpect(status().isOk())
            .andReturn();
    String runId = json.readTree(task.getResponse().getContentAsString()).path("runId").asText();
    mvc.perform(get("/api/runs/{id}", runId).session(bob)).andExpect(status().isForbidden());
    mvc.perform(get("/api/runs/{id}/report", runId).session(bob)).andExpect(status().isForbidden());
    mvc.perform(get("/api/runs/{id}/events", runId).session(bob)).andExpect(status().isForbidden());
    mvc.perform(get("/api/runs/{id}/delivery", runId).session(bob)).andExpect(status().isForbidden());
    mvc.perform(get("/api/runs/{id}", runId).session(alice)).andExpect(status().isOk());
    // 运行概况按归属统计：乙不应看到甲的全库计数。
    var aliceOverview = json.readTree(mvc.perform(get("/api/overview").session(alice))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    var bobOverview = json.readTree(mvc.perform(get("/api/overview").session(bob))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertTrue(aliceOverview.path("TOTAL").asLong() >= 1);
    assertEquals(0, bobOverview.path("TOTAL").asLong());
    mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(taskBody(null))))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());

    // 私有 Agent 只属于甲；乙列表看不到，知道 ID 也不能在计划中引用。
    var agent =
        mvc.perform(post("/api/my/agents").with(csrf()).session(alice).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "name", "甲的检查 Agent",
                    "description", "只读检查",
                    "responsibility", "检查授权文件",
                    "outputRequirements", "列出读取范围",
                    "permissionRole", "ANALYST",
                    "recommendable", true,
                    "modelMode", "mock",
                    "skills", List.of(),
                    "tools", List.of()))))
            .andExpect(status().isOk())
            .andReturn();
    String agentId = json.readTree(agent.getResponse().getContentAsString()).path("id").asText();
    mvc.perform(post("/api/my/agents/{id}/publish", agentId).with(csrf()).session(alice))
        .andExpect(status().isOk());
    assertEquals(0, json.readTree(mvc.perform(get("/api/my/agents").session(bob))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).size());
    mvc.perform(put("/api/my/agents/{id}", agentId).with(csrf()).session(bob).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "name", "篡改", "description", "x", "responsibility", "x",
                "outputRequirements", "x", "permissionRole", "ANALYST",
                "recommendable", true, "modelMode", "mock",
                "skills", List.of(), "tools", List.of()))))
        .andExpect(status().isNotFound());

    String candidateId = createCandidate(bob, agentId);
    var candidate = waitCandidate(bob, candidateId);
    assertTrue(List.of("INVALID", "FAILED", "READY").contains(candidate.path("status").asText()),
        candidate.toString());
    assertNotEquals("READY", candidate.path("status").asText(),
        "跨用户引用的私有 Agent 不能成为可确认候选");
    mvc.perform(get("/api/plan-candidates/{id}", candidateId).session(alice))
        .andExpect(status().isNotFound());

    // 退出同样要求 CSRF；退出后会话立即失效。
    mvc.perform(post("/api/auth/logout").session(bob)).andExpect(status().isForbidden());
    mvc.perform(post("/api/auth/logout").with(csrf()).session(bob)).andExpect(status().isOk());
    mvc.perform(get("/api/tasks").session(bob)).andExpect(status().isUnauthorized());
  }

  private MockHttpSession initializeAdmin() throws Exception {
    MvcResult result =
        mvc.perform(post("/api/auth/initialize").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "username", "admin", "password", "admin-pass-1",
                    "displayName", "管理员", "claimLegacyHistory", true))))
            .andExpect(status().isOk())
            .andReturn();
    MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
    assertNotNull(session);
    return session;
  }

  private void grant(MockHttpSession admin, String username, Path repo) throws Exception {
    mvc.perform(post("/api/admin/repositories").with(csrf()).session(admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "username", username, "repositoryPath", repo.toString()))))
        .andExpect(status().isOk());
  }

  private MockHttpSession login(String username, String password) throws Exception {
    MvcResult result =
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
    return (MockHttpSession) result.getRequest().getSession(false);
  }

  private Path gitRepo() throws Exception {
    Path repo = Files.createDirectory(temp.resolve("repo"));
    run(repo, "init", "-b", "main");
    run(repo, "config", "user.email", "test@example.invalid");
    run(repo, "config", "user.name", "Test");
    Files.writeString(repo.resolve("App.jsx"), "export default function App(){return null}\n", StandardCharsets.UTF_8);
    run(repo, "add", ".");
    run(repo, "commit", "-m", "fixture");
    return repo;
  }

  private static Map<String, Object> taskBody(String workspaceId) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("goal", "只读检查前端");
    body.put("entryKind", workspaceId == null ? "GENERAL" : "CODE");
    body.put("modelMode", "mock");
    body.put("scenario", "normal");
    if (workspaceId != null) {
      body.put("workspaceId", workspaceId);
      body.put("fileScopes", List.of("App.jsx"));
    }
    body.put("constraints", List.of());
    body.put("acceptanceCriteria", List.of("列出检查范围"));
    return body;
  }

  private String createCandidate(MockHttpSession session, String agentId) throws Exception {
    Map<String, Object> input = new java.util.LinkedHashMap<>();
    input.put("goal", "只读检查前端");
    input.put("entryKind", "CODE");
    input.put("workspaceId", null);
    input.put("writeEnabled", false);
    input.put("readOnly", true);
    input.put("fileScopes", List.of("App.jsx"));
    input.put("constraints", List.of());
    input.put("acceptanceCriteria", List.of("列出检查范围"));
    input.put("deliverable", null);
    input.put("agentMode", "MANUAL");
    input.put("manualAgentId", agentId);
    input.put("manualAgentVersion", 1);
    input.put("candidateAgentIds", List.of());
    var result =
        mvc.perform(post("/api/plan-candidates").with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "requestId", "req" + System.nanoTime(),
                    "input", input,
                    "previousCandidateId", "",
                    "draftKey", "draft-" + System.nanoTime()))))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).path("id").asText();
  }

  private com.fasterxml.jackson.databind.JsonNode waitCandidate(MockHttpSession session, String id)
      throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
    while (true) {
      var body = mvc.perform(get("/api/plan-candidates/{id}", id).session(session))
          .andExpect(status().isOk())
          .andReturn().getResponse().getContentAsString();
      var node = json.readTree(body);
      if (!"GENERATING".equals(node.path("status").asText())) return node;
      if (System.nanoTime() > deadline) fail("候选未结算");
      Thread.sleep(30);
    }
  }

  private static void run(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }
}
