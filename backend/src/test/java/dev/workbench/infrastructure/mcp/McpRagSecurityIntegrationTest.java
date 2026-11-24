package dev.workbench.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MCP 与 RAG Embedding 的 HTTP 权限收口（S-01 / S-02 / S-03）。
 *
 * <p>覆盖真实过滤器链：普通用户访问全局 Embedding 配置返回 403，注册 STDIO MCP 返回 403， 内网/元数据端点与非受控 Header 返回
 * 400，管理员仍可正常配置。
 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:mcp_rag_security;DB_CLOSE_DELAY=-1",
      "spring.jpa.hibernate.ddl-auto=none",
      "workbench.rag.embedding.settings-file=target/test-output/rag-embedding-settings.json",
      "workbench.outbound.allow-loopback=true"
    })
class McpRagSecurityIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void ragEmbeddingSettingsAreAdminOnly() throws Exception {
    MockHttpSession admin = adminSession();
    addUser(admin, "alice-mcp");
    MockHttpSession alice = login("alice-mcp");

    mvc.perform(get("/api/rag-embedding")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/rag-embedding").session(alice)).andExpect(status().isForbidden());
    mvc.perform(post("/api/rag-embedding/probe").with(csrf()).session(alice))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/api/rag-embedding")
                .with(csrf())
                .session(alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isForbidden());

    mvc.perform(get("/api/rag-embedding").session(admin)).andExpect(status().isOk());
    mvc.perform(
            put("/api/rag-embedding")
                .with(csrf())
                .session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));

    // 引用不存在（或属于他人）的连接必须按“记录不存在”拒绝，而不是静默接受。
    mvc.perform(
            put("/api/rag-embedding")
                .with(csrf())
                .session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"connectionId\":\"not-exists\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void stdioMcpRegistrationIsAdminOnly() throws Exception {
    MockHttpSession admin = adminSession();
    addUser(admin, "bob-mcp");
    MockHttpSession bob = login("bob-mcp");

    mvc.perform(
            post("/api/mcp-servers")
                .with(csrf())
                .session(bob)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(stdioBody("bob-stdio"))))
        .andExpect(status().isForbidden());

    mvc.perform(
            post("/api/mcp-servers")
                .with(csrf())
                .session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(stdioBody("admin-stdio"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transport").value("STDIO"));
  }

  @Test
  void httpMcpEndpointMustPassOutboundChecks() throws Exception {
    MockHttpSession admin = adminSession();
    addUser(admin, "carol-mcp");
    MockHttpSession carol = login("carol-mcp");

    mvc.perform(
            post("/api/mcp-servers")
                .with(csrf())
                .session(carol)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        httpBody("carol-meta", "http://169.254.169.254/mcp", null))))
        .andExpect(status().isBadRequest());

    mvc.perform(
            post("/api/mcp-servers")
                .with(csrf())
                .session(carol)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        httpBody("carol-private", "https://10.0.0.5/mcp", null))))
        .andExpect(status().isBadRequest());

    Map<String, String> reserved = new LinkedHashMap<>();
    reserved.put("Host", "evil.example.test");
    mvc.perform(
            post("/api/mcp-servers")
                .with(csrf())
                .session(carol)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        httpBody("carol-host", "https://mcp.example.test/mcp", reserved))))
        .andExpect(status().isBadRequest());

    mvc.perform(
            post("/api/mcp-servers")
                .with(csrf())
                .session(carol)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        httpBody("carol-ok", "https://mcp.example.test/mcp", null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.endpointUrl").value("https://mcp.example.test/mcp"));
  }

  private static Map<String, Object> stdioBody(String slug) {
    return Map.of(
        "name",
        "本地演示 " + slug,
        "slug",
        slug,
        "transport",
        "STDIO",
        "command",
        "node",
        "args",
        java.util.List.of(),
        "enabled",
        true);
  }

  private static Map<String, Object> httpBody(
      String slug, String endpointUrl, Map<String, String> headers) {
    var body = new LinkedHashMap<String, Object>();
    body.put("name", "远程演示 " + slug);
    body.put("slug", slug);
    body.put("transport", "STREAMABLE_HTTP");
    body.put("endpointUrl", endpointUrl);
    if (headers != null) body.put("headers", headers);
    body.put("enabled", true);
    return body;
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
