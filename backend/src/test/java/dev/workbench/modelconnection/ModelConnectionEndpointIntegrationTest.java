package dev.workbench.modelconnection;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 模型连接接口的密钥保护与按登录用户隔离。 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:model_connections_endpoint;DB_CLOSE_DELAY=-1",
      "spring.jpa.hibernate.ddl-auto=none"
    })
class ModelConnectionEndpointIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;

  @Test
  void keysAreEncryptedAndIsolatedPerUser() throws Exception {
    MockHttpSession admin = initializeAdmin();
    addUser(admin, "alice-mc");
    addUser(admin, "bob-mc");
    MockHttpSession alice = login("alice-mc");
    MockHttpSession bob = login("bob-mc");

    var presets =
        mvc.perform(get("/api/model-connections/presets").session(alice))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(presets.contains("DEEPSEEK"));
    assertTrue(presets.contains("CUSTOM_OPENAI"));

    String plain = "sk-integration-secret-9876";
    var created =
        mvc.perform(
                post("/api/model-connections")
                    .with(csrf())
                    .session(alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(createBody(plain))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.keyMasked").value("sk-****9876"))
            .andReturn();
    String body = created.getResponse().getContentAsString();
    assertFalse(body.contains(plain), "接口响应不能包含明文密钥");
    String id = json.readTree(body).path("id").asText();

    String encrypted =
        db.queryForObject(
            "select encrypted_api_key from user_model_connections where id=?", String.class, id);
    assertNotEquals(plain, encrypted);
    assertFalse(encrypted.contains(plain));

    var aliceList =
        mvc.perform(get("/api/model-connections").session(alice))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(aliceList.contains("sk-****9876"));
    assertFalse(aliceList.contains(plain));
    assertEquals(1, json.readTree(aliceList).path("connections").size());
    assertTrue(json.readTree(aliceList).path("presets").size() >= 6);

    var bobList =
        mvc.perform(get("/api/model-connections").session(bob))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertEquals(0, json.readTree(bobList).path("connections").size());

    mvc.perform(
            put("/api/model-connections/{id}", id)
                .with(csrf())
                .session(bob)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createBody("sk-bob-attempt"))))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/model-connections/{id}/set-default", id).with(csrf()).session(bob))
        .andExpect(status().isNotFound());
    mvc.perform(delete("/api/model-connections/{id}", id).with(csrf()).session(bob))
        .andExpect(status().isNotFound());

    // 编辑时密钥留空：保留原密文与掩码。
    mvc.perform(
            put("/api/model-connections/{id}", id)
                .with(csrf())
                .session(alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createBody(""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keyMasked").value("sk-****9876"));
    assertEquals(
        encrypted,
        db.queryForObject(
            "select encrypted_api_key from user_model_connections where id=?", String.class, id));
  }

  private static Map<String, Object> createBody(String apiKey) {
    return Map.of(
        "displayName", "我的 DeepSeek",
        "providerType", "DEEPSEEK",
        "baseUrl", "https://api.deepseek.com",
        "modelName", "deepseek-chat",
        "apiKey", apiKey,
        "enabled", true,
        "isDefault", true,
        "currency", "CNY");
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

  private void addUser(MockHttpSession admin, String username) throws Exception {
    mvc.perform(
            post("/api/auth/users")
                .with(csrf())
                .session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "username", username,
                            "password", username + "-pass-1",
                            "displayName", username,
                            "role", "USER"))))
        .andExpect(status().isOk());
  }

  private MockHttpSession login(String username) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("username", username, "password", username + "-pass-1"))))
            .andExpect(status().isOk())
            .andReturn();
    return (MockHttpSession) result.getRequest().getSession(false);
  }
}
