package dev.workbench.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 编排画布校验接口的真实 DTO 契约。
 *
 * <p>前端冲突红框与徽标直接读取 {@code byNodeKey}，因此这里断言真实 HTTP JSON 确实返回该字段、键与冲突明细一致，
 * 而不是靠前端单测自己伪造一个后端从未产出的字段。
 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:plan-draft-contract;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-plan-draft-contract"
    })
class PlanDraftValidationContractTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void writeConflictReturnsByNodeKeyIndexForBothNodes() throws Exception {
    var draft =
        Map.of(
            "schemaVersion",
            1,
            "nodes",
            List.of(
                node("analyze", List.of("src/app.jsx"), List.of()),
                node("implement", List.of("src/app.jsx"), List.of("analyze"))),
            "assumptions",
            List.of(),
            "openQuestions",
            List.of());

    JsonNode body = validate(Map.of("draft", draft));

    assertFalse(body.path("valid").asBoolean(), body.toString());
    JsonNode conflicts = body.path("conflicts");
    assertEquals(1, conflicts.size(), body.toString());
    assertEquals("WRITE_CONFLICT", conflicts.get(0).path("code").asText());

    // 线上红框读取的就是这个字段：必须真实存在，且与冲突明细逐项一致。
    JsonNode byNodeKey = body.path("byNodeKey");
    assertTrue(byNodeKey.isObject(), "byNodeKey 必须由服务端真实返回：" + body);
    assertEquals(2, byNodeKey.size(), body.toString());
    for (String key : List.of("analyze", "implement")) {
      assertTrue(byNodeKey.has(key), "缺少节点冲突索引：" + key + " => " + body);
      assertEquals("WRITE_CONFLICT", byNodeKey.path(key).get(0).path("code").asText());
      assertEquals(
          "src/app.jsx", byNodeKey.path(key).get(0).path("paths").get(0).asText());
    }

    // 旧前端读取的高亮集合与 byNodeKey 的键必须保持一致，避免两套口径分叉。
    var conflictingKeys = body.path("conflictingNodeKeys");
    assertEquals(byNodeKey.size(), conflictingKeys.size(), body.toString());
    for (JsonNode key : conflictingKeys) assertTrue(byNodeKey.has(key.asText()), body.toString());
  }

  @Test
  void cleanDraftReturnsAnEmptyIndexInsteadOfOmittingTheField() throws Exception {
    var draft =
        Map.of(
            "schemaVersion",
            1,
            "nodes",
            List.of(node("analyze", List.of("src/app.jsx"), List.of())),
            "assumptions",
            List.of(),
            "openQuestions",
            List.of());

    JsonNode body = validate(Map.of("draft", draft));

    assertTrue(body.path("valid").asBoolean(), body.toString());
    assertTrue(body.path("byNodeKey").isObject(), body.toString());
    assertEquals(0, body.path("byNodeKey").size(), body.toString());
    assertEquals(0, body.path("conflictingNodeKeys").size(), body.toString());
  }

  private JsonNode validate(Map<String, Object> payload) throws Exception {
    String response =
        mvc.perform(
                post("/api/plans/validate-draft")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return json.readTree(response);
  }

  private static Map<String, Object> node(
      String key, List<String> writeFiles, List<String> dependsOn) {
    return Map.of(
        "key",
        key,
        "title",
        key,
        "role",
        "IMPLEMENT",
        "objective",
        "按授权修改文件",
        "dependsOn",
        dependsOn,
        "readFiles",
        writeFiles,
        "writeFiles",
        writeFiles,
        "expectedOutputs",
        List.of(Map.of("kind", "FILE", "path", writeFiles.get(0), "required", true)),
        "acceptanceCriteria",
        List.of("改动能被核对"));
  }
}
