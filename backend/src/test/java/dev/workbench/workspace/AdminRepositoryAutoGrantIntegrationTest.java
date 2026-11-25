package dev.workbench.workspace;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 管理员导入本地仓库的一键授权：管理员成功且幂等，普通用户明确 403，旧请求体行为不变。
 *
 * <p>安全铁律：{@code autoGrantForAdmin} 只有在服务端会话确认为管理员时才生效，绝不能成为提权入口。
 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:admin_auto_grant;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-admin-auto-grant"
    })
class AdminRepositoryAutoGrantIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @TempDir Path temp;

  @Test
  void adminGrantsRepositoryAndCreatesWorkspaceInOneStep() throws Exception {
    MockHttpSession admin = adminSession();
    Path repo = gitRepo("admin-repo");

    var created = createWorkspace(admin, repo, true);
    assertEquals(200, created.getResponse().getStatus());
    String workspaceId = json.readTree(created.getResponse().getContentAsString()).path("id").asText();
    assertFalse(workspaceId.isBlank());

    var grants = json.readTree(
        mvc.perform(get("/api/admin/repositories").session(admin))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    long matched = countGrants(grants, "admin", repo);
    assertEquals(1, matched, "管理员一键导入必须落库一条授权记录：" + grants);

    // 幂等：再次导入同一仓库不会产生第二条授权，也不会失败。
    var repeated = createWorkspace(admin, repo, true);
    assertEquals(200, repeated.getResponse().getStatus());
    var afterRepeat = json.readTree(
        mvc.perform(get("/api/admin/repositories").session(admin))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertEquals(1, countGrants(afterRepeat, "admin", repo));
  }

  @Test
  void ordinaryUserCannotAutoGrant() throws Exception {
    MockHttpSession admin = adminSession();
    addUser(admin, "alice", "alice-pass-1");
    MockHttpSession alice = login("alice", "alice-pass-1");
    Path repo = gitRepo("user-repo");

    var denied = mvc.perform(
            post("/api/workspaces").with(csrf()).session(alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body(repo, true))))
        .andExpect(status().isForbidden())
        .andReturn();
    assertNotNull(denied.getResponse().getContentAsString());

    // 普通用户不能借此获得授权，也不能创建工作区。
    var grants = json.readTree(
        mvc.perform(get("/api/admin/repositories").session(admin))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertEquals(0, countGrants(grants, "alice", repo));
    var workspaces = json.readTree(
        mvc.perform(get("/api/workspaces").session(alice))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertEquals(0, workspaces.size(), "拒绝提权后不应创建任何工作区");

    // 不带该字段的旧请求同样继续被授权检查拒绝。
    mvc.perform(
            post("/api/workspaces").with(csrf()).session(alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body(repo, null))))
        .andExpect(status().isForbidden());
  }

  @Test
  void legacyRequestBodiesKeepPreviousBehavior() throws Exception {
    MockHttpSession admin = adminSession();
    addUser(admin, "bob", "bob-pass-1");
    MockHttpSession bob = login("bob", "bob-pass-1");
    Path repo = gitRepo("legacy-repo");

    // 旧行为一：未登记的仓库，即使是管理员也不能直接导入。
    mvc.perform(
            post("/api/workspaces").with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(legacyBody(repo))))
        .andExpect(status().isForbidden());

    // 旧行为二：管理员显式授权后，普通用户用旧请求体正常导入。
    mvc.perform(
            post("/api/admin/repositories").with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(
                    Map.of("username", "bob", "repositoryPath", repo.toString()))))
        .andExpect(status().isOk());
    var created = mvc.perform(
            post("/api/workspaces").with(csrf()).session(bob)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(legacyBody(repo))))
        .andExpect(status().isOk())
        .andReturn();
    assertFalse(
        json.readTree(created.getResponse().getContentAsString()).path("id").asText().isBlank());

    // 显式 false 与不传字段完全等价：仍然是原有授权检查。
    Path other = gitRepo("legacy-repo-2");
    mvc.perform(
            post("/api/workspaces").with(csrf()).session(bob)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body(other, false))))
        .andExpect(status().isForbidden());
  }

  private static long countGrants(JsonNode grants, String username, Path repository) {
    long matched = 0;
    String expected;
    try {
      expected = repository.toRealPath().toString();
    } catch (java.io.IOException e) {
      expected = repository.toString();
    }
    for (var grant : grants)
      if (username.equals(grant.path("username").asText())
          && expected.equals(grant.path("repositoryRoot").asText())) matched++;
    return matched;
  }

  private Map<String, Object> body(Path repository, Boolean autoGrant) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("repositoryPath", repository.toString());
    body.put("baseBranch", "main");
    if (autoGrant != null) body.put("autoGrantForAdmin", autoGrant);
    return body;
  }

  private Map<String, Object> legacyBody(Path repository) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("repositoryPath", repository.toString());
    body.put("baseBranch", "main");
    body.put("workBranch", "legacy/work");
    return body;
  }

  private MvcResult createWorkspace(MockHttpSession session, Path repository, boolean autoGrant)
      throws Exception {
    return mvc.perform(
            post("/api/workspaces").with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body(repository, autoGrant))))
        .andReturn();
  }

  /** 初始化只允许执行一次；后续用例复用同一管理员账号登录。 */
  private MockHttpSession adminSession() throws Exception {
    var status =
        json.readTree(
            mvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    if (status.path("initialized").asBoolean()) return login("admin", "admin-pass-1");
    MvcResult result =
        mvc.perform(
                post("/api/auth/initialize").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(
                        Map.of(
                            "username", "admin",
                            "password", "admin-pass-1",
                            "displayName", "管理员",
                            "claimLegacyHistory", true))))
            .andExpect(status().isOk())
            .andReturn();
    MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
    assertNotNull(session);
    return session;
  }

  private void addUser(MockHttpSession admin, String username, String password) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", username);
    body.put("password", password);
    body.put("displayName", username);
    body.put("role", "USER");
    mvc.perform(
            post("/api/auth/users").with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)))
        .andExpect(status().isOk());
  }

  private MockHttpSession login(String username, String password) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(
                        Map.of("username", username, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
    return (MockHttpSession) result.getRequest().getSession(false);
  }

  private Path gitRepo(String name) throws Exception {
    Path repo = Files.createDirectory(temp.resolve(name + "-" + System.nanoTime()));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "test@example.invalid");
    git(repo, "config", "user.name", "Test");
    Files.writeString(repo.resolve("App.jsx"), "export default function App(){return null}\n",
        StandardCharsets.UTF_8);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "fixture");
    return repo;
  }

  private void git(Path dir, String... args) throws Exception {
    var command = new ArrayList<String>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }
}
