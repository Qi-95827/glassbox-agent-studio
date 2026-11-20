package dev.workbench.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 旧 LOCAL 历史数据只在管理员显式领取后可见。 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:legacy_ownership;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-legacy-ownership"
    })
class LegacyOwnershipIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void legacyLocalTaskRequiresExplicitClaim() throws Exception {
    // 初始化前的单机模式创建旧 LOCAL 任务。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("goal", "旧本地任务");
    body.put("entryKind", "GENERAL");
    body.put("modelMode", "mock");
    body.put("scenario", "normal");
    body.put("constraints", List.of());
    body.put("acceptanceCriteria", List.of());
    var created =
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn();
    String runId = json.readTree(created.getResponse().getContentAsString()).path("runId").asText();

    MvcResult initialized =
        mvc.perform(post("/api/auth/initialize").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "username", "admin", "password", "admin-pass-1",
                    "displayName", "管理员", "claimLegacyHistory", false))))
            .andExpect(status().isOk())
            .andReturn();
    MockHttpSession admin = (MockHttpSession) initialized.getRequest().getSession(false);

    assertEquals(0, json.readTree(mvc.perform(get("/api/tasks").session(admin))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).size(),
        "未领取前旧数据不可见");
    mvc.perform(get("/api/runs/{id}", runId).session(admin)).andExpect(status().isForbidden());

    mvc.perform(post("/api/auth/claim-legacy").with(csrf()).session(admin)).andExpect(status().isOk());
    var tasks = json.readTree(mvc.perform(get("/api/tasks").session(admin))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertEquals(1, tasks.size());
    mvc.perform(get("/api/runs/{id}", runId).session(admin)).andExpect(status().isOk());
  }
}
