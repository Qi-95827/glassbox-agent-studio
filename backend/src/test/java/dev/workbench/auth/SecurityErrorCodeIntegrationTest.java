package dev.workbench.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 认证与 CSRF 边界的稳定错误码。
 *
 * <p>前端在英文界面下也必须能区分「CSRF token 过期需要刷新重试」与「确实没有权限」，
 * 因此服务端错误响应必须带与界面语言无关的 {@code errorCode}；{@code message} 只作为人类可读兜底，
 * 并按 {@code Accept-Language} 本地化。
 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:security_error_code;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-security-error-code"
    })
class SecurityErrorCodeIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void authenticationAndCsrfFailuresCarryStableErrorCodes() throws Exception {
    MockHttpSession admin = ensureAdmin();
    createUser(admin, "alice", "alice-pass-1");
    MockHttpSession alice = login("alice", "alice-pass-1");

    // 未登录：错误码与语言无关，中文请求得到中文兜底文案。
    JsonNode unauthenticated = exchange(get("/api/tasks"), null, "zh-CN", 401);
    assertEquals("AUTH_REQUIRED", unauthenticated.path("errorCode").asText());
    assertEquals("请先登录。", unauthenticated.path("message").asText());

    // 英文界面同一条路径：错误码不变，文案随 Accept-Language 切换。
    JsonNode english = exchange(get("/api/tasks"), null, "en-US", 401);
    assertEquals("AUTH_REQUIRED", english.path("errorCode").asText());
    assertEquals("Please sign in first.", english.path("message").asText());

    // 已登录但缺少 CSRF token：必须给出可结构化识别的 CSRF_INVALID。
    JsonNode csrfFailure =
        exchange(
            post("/api/tasks").contentType(MediaType.APPLICATION_JSON), alice, "en-US", 403);
    assertEquals("CSRF_INVALID", csrfFailure.path("errorCode").asText());
    assertTrue(csrfFailure.path("message").asText().toLowerCase().contains("request validation"));
  }

  @Test
  void adminOnlyEndpointsReportAccessDeniedRatherThanCsrfFailure() throws Exception {
    MockHttpSession admin = ensureAdmin();
    createUser(admin, "bob", "bob-pass-1");
    MockHttpSession bob = login("bob", "bob-pass-1");

    JsonNode denied = exchange(get("/api/admin/repositories"), bob, "en-US", 403);
    assertEquals("ACCESS_DENIED", denied.path("errorCode").asText());
    assertEquals(
        "Only administrators may perform this operation.", denied.path("message").asText());

    // 管理员访问同一端点不应被拒绝。
    mvc.perform(get("/api/admin/repositories").session(admin)).andExpect(status().isOk());
  }

  private JsonNode exchange(
      MockHttpServletRequestBuilder builder,
      MockHttpSession session,
      String language,
      int expectedStatus)
      throws Exception {
    MockHttpServletRequestBuilder request = builder.header("Accept-Language", language);
    if (session != null) request = request.session(session);
    MvcResult result = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    String content = result.getResponse().getContentAsString();
    return content.isBlank() ? json.createObjectNode() : json.readTree(content);
  }

  /**
   * 同一测试类共享一个 Spring 上下文与内存库，两个用例都会用到管理员：
   * 已初始化时直接用既定口令登录，未初始化时才执行首次初始化。
   */
  private MockHttpSession ensureAdmin() throws Exception {
    MvcResult probe = mvc.perform(get("/api/auth/status")).andExpect(status().isOk()).andReturn();
    boolean initialized =
        json.readTree(probe.getResponse().getContentAsString()).path("initialized").asBoolean();
    if (initialized) return login("admin", "admin-pass-1");
    return initializeAdmin();
  }

  private MockHttpSession initializeAdmin() throws Exception {
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

  private void createUser(MockHttpSession admin, String username, String password)
      throws Exception {
    mvc.perform(
            post("/api/auth/users")
                .with(csrf())
                .session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "username", username,
                            "password", password,
                            "displayName", username,
                            "role", "USER"))))
        .andExpect(status().isOk());
  }

  private MockHttpSession login(String username, String password) throws Exception {
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
