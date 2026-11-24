package dev.workbench.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Task 02 的越权与运维端点收口回归。
 *
 * <p>覆盖六类此前缺少归属校验的接口：工作区代码审批决策、L1 上下文产物回查、任务入口路由、 可管理评测数据集/失败运行导入、Benchmark Hub 导入口，以及新收口到管理员的
 * Actuator 与宿主机 全局 DeepSeek 密钥。
 */
@AutoConfigureMockMvc
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task2_idor;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task2-idor",
      "workbench.outbound.allow-loopback=true"
    })
class Task2IdorIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @TempDir Path temp;

  @Test
  void approvalDecisionRequiresWorkspaceOwnership() throws Exception {
    MockHttpSession admin = adminSession();
    addUser(admin, "dave-idor");
    addUser(admin, "carol-idor");
    MockHttpSession dave = login("dave-idor");
    MockHttpSession carol = login("carol-idor");
    String workspaceId = createWorkspace(admin, dave, "dave-approval");
    // 审批指纹需要真实差异：先通过受控写接口修改已授权文件。
    modifyFile(dave, workspaceId, "App.jsx");

    var proposed =
        mvc.perform(post("/api/workspaces/{id}/approvals", workspaceId).with(csrf()).session(dave))
            .andExpect(status().isOk())
            .andReturn();
    String approvalId =
        json.readTree(proposed.getResponse().getContentAsString()).path("ID").asText();
    assertFalse(approvalId.isBlank());

    // 只要知道审批单标识，此前任何人都能替他人批准或拒绝。
    mvc.perform(
            post("/api/workspaces/approvals/{id}/decision", approvalId)
                .with(csrf())
                .session(carol)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isForbidden());

    String status =
        db.queryForObject("select status from approvals where id=?", String.class, approvalId);
    assertEquals("PENDING", status, "越权请求不得改变审批状态");

    mvc.perform(
            post("/api/workspaces/approvals/{id}/decision", approvalId)
                .with(csrf())
                .session(dave)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.STATUS").value("APPROVED"));
  }

  @Test
  void taskRouteAndContextArtifactRequireOwnership() throws Exception {
    MockHttpSession admin = adminSession();
    addUser(admin, "dave-route");
    addUser(admin, "carol-route");
    MockHttpSession dave = login("dave-route");
    MockHttpSession carol = login("carol-route");

    String runId = createTask(dave);
    String taskId = db.queryForObject("select task_id from runs where id=?", String.class, runId);

    mvc.perform(get("/api/tasks/{id}/entry-route", taskId).session(dave))
        .andExpect(status().isOk());
    mvc.perform(get("/api/tasks/{id}/entry-route", taskId).session(carol))
        .andExpect(status().isForbidden());

    String reference = "art-00000000deadbeef";
    db.update(
        "INSERT INTO context_artifacts(id,run_id,tool_call_id,title,content,char_count,"
            + "outline_json,created_at) VALUES(?,?,?,?,?,?,?,?)",
        reference,
        runId,
        "call-1",
        "tool-output",
        "敏感的工具输出正文",
        9,
        "[]",
        "2026-01-01T00:00:00Z");
    mvc.perform(get("/api/context-artifacts/{ref}", reference).session(dave))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value("敏感的工具输出正文"));
    mvc.perform(get("/api/context-artifacts/{ref}", reference).session(carol))
        .andExpect(status().isForbidden());

    // 历史产物没有 runId，无法判定归属，一律按不存在处理。
    String legacy = "art-00000000deadbeee";
    db.update(
        "INSERT INTO context_artifacts(id,run_id,tool_call_id,title,content,char_count,"
            + "outline_json,created_at) VALUES(?,NULL,?,?,?,?,?,?)",
        legacy,
        "call-0",
        "旧产物",
        "历史正文",
        4,
        "[]",
        "2026-01-01T00:00:00Z");
    mvc.perform(get("/api/context-artifacts/{ref}", legacy).session(dave))
        .andExpect(status().isNotFound());
  }

  @Test
  void evaluationDatasetsAndHubImportAreAdminOnly() throws Exception {
    MockHttpSession admin = adminSession();
    addUser(admin, "dave-eval");
    MockHttpSession dave = login("dave-eval");
    String runId = createTask(dave);

    mvc.perform(get("/api/evaluation-datasets/TASK2_UI").session(dave))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/evaluation-datasets/TASK2_UI/from-run")
                .with(csrf())
                .session(dave)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"runId\":\"" + runId + "\",\"confirmedRedaction\":true}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/eval-lab/hub/bfcl-v3-simple/import")
                .with(csrf())
                .session(dave)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonl\":\"\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/eval-lab/hub/bfcl-v3-simple/download").with(csrf()).session(dave))
        .andExpect(status().isForbidden());

    // 管理员可以通过鉴权层，但引用他人运行仍然被归属校验拒绝。
    mvc.perform(
            post("/api/evaluation-datasets/TASK2_UI/from-run")
                .with(csrf())
                .session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"runId\":\"" + runId + "\",\"confirmedRedaction\":true}"))
        .andExpect(status().isForbidden());
    assertFalse(
        db.queryForObject(
                "select count(*) from evaluation_dataset_samples where dataset_key=?",
                Integer.class,
                "TASK2_UI")
            > 0,
        "越权请求不得写入数据集样本");
  }

  @Test
  void actuatorRequiresAdminAndRejectsAnonymous() throws Exception {
    MockHttpSession admin = adminSession();
    addUser(admin, "carol-ops");
    MockHttpSession carol = login("carol-ops");

    mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/prometheus").session(carol)).andExpect(status().isForbidden());
    mvc.perform(get("/actuator/prometheus").session(admin)).andExpect(status().isOk());
    mvc.perform(get("/actuator/health").session(carol)).andExpect(status().isForbidden());
  }

  @Test
  void ordinaryUserCannotSpendHostDeepSeekKey() throws Exception {
    MockHttpSession admin = adminSession();
    addUser(admin, "carol-key");
    MockHttpSession carol = login("carol-key");
    String goal = "Task 02 宿主机密钥样例 " + java.util.UUID.randomUUID();

    Map<String, Object> body = taskBody(goal);
    body.put("modelMode", "deepseek");
    mvc.perform(
            post("/api/tasks")
                .with(csrf())
                .session(carol)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isForbidden());
    assertEquals(
        0,
        db.queryForObject(
            "select count(*) from runs where task_id in (select id from tasks where goal=?)",
            Integer.class,
            goal),
        "被拒绝的请求不得创建运行");
  }

  // ---------------------------------------------------------------- helpers

  private Map<String, Object> taskBody() {
    return taskBody("Task 02 权限样例");
  }

  private Map<String, Object> taskBody(String goal) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("goal", goal);
    body.put("entryKind", "GENERAL");
    body.put("modelMode", "mock");
    body.put("scenario", "normal");
    body.put("constraints", List.of());
    body.put("acceptanceCriteria", List.of("完成"));
    return body;
  }

  /** 通过受控写接口制造一次真实差异，供审批指纹使用。 */
  private void modifyFile(MockHttpSession session, String workspaceId, String path)
      throws Exception {
    var file =
        mvc.perform(
                get("/api/workspaces/{id}/file", workspaceId).param("path", path).session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String sha256 = json.readTree(file).path("sha256").asText();
    mvc.perform(
            put("/api/workspaces/{id}/file", workspaceId)
                .with(csrf())
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "path", path,
                            "expectedHash", sha256,
                            "content", "export default function App(){return 'changed'}\n"))))
        .andExpect(status().isOk());
  }

  private String createTask(MockHttpSession session) throws Exception {
    var result =
        mvc.perform(
                post("/api/tasks")
                    .with(csrf())
                    .session(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(taskBody())))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).path("runId").asText();
  }

  private String createWorkspace(MockHttpSession admin, MockHttpSession owner, String name)
      throws Exception {
    Path repo = gitRepo(name);
    mvc.perform(
            post("/api/admin/repositories")
                .with(csrf())
                .session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "username", ownerUsername(owner),
                            "repositoryPath", repo.toString()))))
        .andExpect(status().isOk());
    var created =
        mvc.perform(
                post("/api/workspaces")
                    .with(csrf())
                    .session(owner)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("repositoryPath", repo.toString(), "baseBranch", "main"))))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(created.getResponse().getContentAsString()).path("id").asText();
  }

  private String ownerUsername(MockHttpSession session) throws Exception {
    var body =
        mvc.perform(get("/api/auth/status").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).path("currentUser").path("username").asText();
  }

  private Path gitRepo(String name) throws Exception {
    Path repo = Files.createDirectory(temp.resolve(name));
    run(repo, "init", "-b", "main");
    run(repo, "config", "user.email", "test@example.invalid");
    run(repo, "config", "user.name", "Test");
    Files.writeString(
        repo.resolve("App.jsx"),
        "export default function App(){return null}\n",
        StandardCharsets.UTF_8);
    run(repo, "add", ".");
    run(repo, "commit", "-m", "fixture");
    return repo;
  }

  private static void run(Path dir, String... args) throws Exception {
    var command = new java.util.ArrayList<String>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }

  private MockHttpSession adminSession() throws Exception {
    String status =
        mvc.perform(get("/api/auth/status")).andReturn().getResponse().getContentAsString();
    if (!json.readTree(status).path("initialized").asBoolean()) {
      MvcResult result =
          mvc.perform(
                  post("/api/auth/initialize")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          json.writeValueAsString(
                              Map.of(
                                  "username", "admin",
                                  "password", "admin-pass-1",
                                  "displayName", "管理员",
                                  "claimLegacyHistory", true))))
              .andExpect(status().isOk())
              .andReturn();
      return (MockHttpSession) result.getRequest().getSession(false);
    }
    return login("admin");
  }

  private void addUser(MockHttpSession admin, String username) throws Exception {
    String users =
        mvc.perform(get("/api/auth/users").session(admin))
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (var node : json.readTree(users)) {
      if (username.equals(node.path("username").asText())) return;
    }
    mvc.perform(
            post("/api/auth/users")
                .with(csrf())
                .session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "username",
                            username,
                            "password",
                            username + "-pass-1",
                            "displayName",
                            username,
                            "role",
                            "USER"))))
        .andExpect(status().isOk());
  }

  private MockHttpSession login(String username) throws Exception {
    String password = "admin".equals(username) ? "admin-pass-1" : username + "-pass-1";
    MvcResult result =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("username", username, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
    return (MockHttpSession) result.getRequest().getSession(false);
  }
}
