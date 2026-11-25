package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import dev.workbench.auth.AuthService;
import dev.workbench.domain.RunStatus;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.planning.PlanService;
import dev.workbench.store.GlassboxTraceStore;
import dev.workbench.store.RunStateStore;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
 * 真实登录态全链路端到端集成测试。
 *
 * <p>模拟已初始化管理员账号 + Cookie 会话 + CSRF 的真实使用场景，一次性贯通：
 * 1) 中文后端校验文案不再返回裸 messageKey；
 * 2) 管理员一键授权导入本地仓库；
 * 3) 整仓 {@code "."} 授权创建代码任务 + 断点暂停与恢复完成；
 * 4) 登录态下 Eval Lab 内置微集 6 大类 24 题全跑（零 executionFailed，编码题真实创建运行并清理临时仓库）；
 * 5) 内部评测工作区入口拒绝任意非临时目录路径。
 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:auth_e2e_all;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-auth-e2e",
      "workbench.outbound.allow-loopback=true"
    })
class AuthenticatedEndToEndWorkflowAndEvalTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired PlanService plans;
  @Autowired GlassboxTraceStore traces;
  @Autowired RunStateStore states;
  @Autowired WorkspaceService workspaces;
  @Autowired AuthService authService;
  @Autowired ModelConnectionService modelConnections;
  @TempDir Path temp;

  @Test
  void authenticatedEndToEndCoversI18nAutoGrantWholeScopeBreakpointAndAllSixEvalCategories()
      throws Exception {
    MockHttpSession admin = initializedAdminSession();

    // 1. 创建类似 F:\tmp\demo-todo 的测试仓库并用管理员一键授权导入。
    Path repo = createDemoGitRepo("demo-todo-e2e");
    String workspaceId =
        json.readTree(
                mvc.perform(
                        post("/api/workspaces")
                            .with(csrf())
                            .session(admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                json.writeValueAsString(
                                    Map.of(
                                        "repositoryPath",
                                        repo.toString(),
                                        "baseBranch",
                                        "main",
                                        "autoGrantForAdmin",
                                        true))))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("id")
            .asText();
    assertFalse(workspaceId.isBlank());

    // 2. 验证空授权范围返回真实中文文案，不再泄露裸键名 validation.task.fileRequired。
    String emptyScopeBody =
        mvc.perform(
                post("/api/tasks")
                    .with(csrf())
                    .session(admin)
                    .header("Accept-Language", "zh-CN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "goal",
                                "空范围校验测试",
                                "entryKind",
                                "CODE",
                                "modelMode",
                                "mock",
                                "workspaceId",
                                workspaceId,
                                "fileScopes",
                                List.of(),
                                "directoryScopes",
                                List.of(),
                                "writeEnabled",
                                true))))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertTrue(
        emptyScopeBody.contains("请选择至少一个授权文件或目录"),
        "必须返回中文错误文案而非裸键名：" + emptyScopeBody);
    assertFalse(emptyScopeBody.contains("validation.task.fileRequired"));

    // 3. 整仓 "." 授权创建代码任务 + 断点暂停与恢复完成。
    var wholeScopeTaskPayload = new java.util.LinkedHashMap<String, Object>();
    wholeScopeTaskPayload.put("goal", "在 app.py 新增 format_greeting 并在 application.yml 增加默认问候配置");
    wholeScopeTaskPayload.put("entryKind", "CODE");
    wholeScopeTaskPayload.put("modelMode", "mock");
    wholeScopeTaskPayload.put("workspaceId", workspaceId);
    wholeScopeTaskPayload.put("fileScopes", List.of());
    wholeScopeTaskPayload.put("directoryScopes", List.of("."));
    wholeScopeTaskPayload.put("excludedPaths", List.of());
    wholeScopeTaskPayload.put("writeEnabled", true);
    wholeScopeTaskPayload.put("reviewPlan", true);
    wholeScopeTaskPayload.put("maxRounds", 10);
    wholeScopeTaskPayload.put("maxTools", 6);
    wholeScopeTaskPayload.put("maxSeconds", 60);
    String taskBody =
        mvc.perform(
                post("/api/tasks")
                    .with(csrf())
                    .session(admin)
                    .header("Accept-Language", "zh-CN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(wholeScopeTaskPayload)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String runId = json.readTree(taskBody).path("runId").asText();
    assertFalse(runId.isBlank());
    var plan = plans.workflowPlan(runId);
    assertNotNull(plan);
    String firstReadyNodeId =
        plans.readyNodes(plans.checkedWorkflowNodes(runId, plan)).getFirst().id().toString();
    mvc.perform(
            put("/api/runs/{id}/debug-control", runId)
                .with(csrf())
                .session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "executionMode",
                            GlassboxTraceStore.MODE_STEP_TEACHING,
                            "breakpoints",
                            List.of(firstReadyNodeId)))))
        .andExpect(status().isOk());

    mvc.perform(
            post("/api/runs/{id}/plans/{planVersionId}/approve", runId, plan.id())
                .with(csrf())
                .session(admin))
        .andExpect(status().isOk());

    awaitRunStatus(runId, RunStatus.PAUSED, Duration.ofSeconds(20));
    mvc.perform(post("/api/runs/{id}/resume", runId).with(csrf()).session(admin))
        .andExpect(status().isOk());
    RunStatus finalRunStatus = awaitTerminalRunStatus(runId, Duration.ofSeconds(20));
    assertNotEquals(RunStatus.FAILED, finalRunStatus, "断点恢复后运行绝不能因检查点冲突而 FAILED");

    // 4. 登录态下跑完整 6 大类 24 题内置微集，验证 CodingReflectionRunner 不再 0ms 失败。
    Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir"));
    Set<Path> existingEvalRepos;
    try (var stream = Files.list(tmpRoot)) {
      existingEvalRepos =
          stream
              .filter(p -> p.getFileName().toString().startsWith("eval-coding-"))
              .collect(Collectors.toSet());
    }

    String evalStartBody =
        mvc.perform(
                post("/api/eval-lab/runs")
                    .with(csrf())
                    .session(admin)
                    .header("Accept-Language", "zh-CN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "sourceKind",
                                "MINI",
                                "categories",
                                List.of(
                                    "CODING_REFLECTION",
                                    "TOOL_MCP",
                                    "CONTEXT_COMPACTION",
                                    "LONG_TERM_MEMORY",
                                    "PLANNING_MULTI_AGENT",
                                    "SAFETY_INJECTION"),
                                "modelMode",
                                "mock",
                                "enableCompaction",
                                true,
                                "enableLongTermMemory",
                                true,
                                "enableReflection",
                                true,
                                "enableMcp",
                                false,
                                "repeatK",
                                1,
                                "sampleLimit",
                                4))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String evalRunId = json.readTree(evalStartBody).path("id").asText();
    assertFalse(evalRunId.isBlank());

    JsonNode evalDetail = awaitEvalFinished(admin, evalRunId, Duration.ofSeconds(90));
    assertEquals("COMPLETED", evalDetail.path("run").path("status").asText());
    JsonNode cases = evalDetail.path("cases");
    assertEquals(24, cases.size(), "6 大类各 4 题应产出 24 条逐题结果");

    int codingCases = 0;
    for (JsonNode row : cases) {
      assertFalse(
          row.path("metrics").path("executionFailed").asBoolean(false),
          "用例不得执行失败：" + row.path("sampleId").asText() + " -> " + row.path("trace"));
      assertTrue(
          row.path("metrics").path("executionError").asText("").isBlank(),
          "用例不得出现 executionError：" + row.path("sampleId").asText() + " -> " + row.path("metrics"));
      assertTrue(
          row.path("trace").path("executionError").asText("").isBlank(),
          "用例 trace 不得出现 executionError："
              + row.path("sampleId").asText()
              + " -> "
              + row.path("trace"));
      if ("CODING_REFLECTION".equals(row.path("category").asText())) {
        codingCases++;
        assertFalse(
            row.path("linkedRunId").asText("").isBlank(),
            "编码评测题在登录态下必须真实创建运行：" + row.path("sampleId").asText());
        assertTrue(
            row.path("metrics").path("terminal").asBoolean(false),
            "编码评测题必须进入终态：" + row.path("sampleId").asText() + " -> " + row.path("metrics"));
        String wsPath = row.path("trace").path("workspacePath").asText("");
        assertFalse(wsPath.isBlank());
        assertFalse(Files.exists(Path.of(wsPath)), "评测临时 worktree 必须被清理：" + wsPath);
      }
    }
    assertEquals(4, codingCases);
    try (var stream = Files.list(tmpRoot)) {
      List<Path> leaked =
          stream
              .filter(p -> p.getFileName().toString().startsWith("eval-coding-"))
              .filter(p -> !existingEvalRepos.contains(p))
              .toList();
      assertTrue(leaked.isEmpty(), "评测结束后不得残留临时源仓库：" + leaked);
    }

    // 5) 登录态下创建 BYOK 连接，模拟评测实验室异步线程（无 HttpServletRequest，仅传递 SecurityContext）
    //    验证 AuthService.current() 与 ModelConnectionService.requireEnabledOwned(...) 不会再报
    //    EmptyResultDataAccessException (Incorrect result size: expected 1, actual 0)。
    String connBody =
        mvc.perform(
                post("/api/model-connections")
                    .with(csrf())
                    .session(admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "displayName",
                                "E2E BYOK",
                                "providerType",
                                "DEEPSEEK",
                                "baseUrl",
                                "https://api.deepseek.com",
                                "modelName",
                                "deepseek-chat",
                                "apiKey",
                                "sk-e2e-test-key-1234567890",
                                "enabled",
                                true,
                                "isDefault",
                                true))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String connId = json.readTree(connBody).path("id").asText();
    assertFalse(connId.isBlank());
    var asyncCheck =
        java.util.concurrent.CompletableFuture.supplyAsync(
                () -> {
                  var ctx =
                      org.springframework.security.core.context.SecurityContextHolder
                          .createEmptyContext();
                  ctx.setAuthentication(
                      new org.springframework.security.authentication
                          .UsernamePasswordAuthenticationToken(
                          "e2e-admin",
                          null,
                          List.of(
                              new org.springframework.security.core.authority
                                  .SimpleGrantedAuthority("ROLE_ADMIN"))));
                  org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
                  try {
                    assertTrue(authService.current().isPresent());
                    return modelConnections.requireEnabledOwned(connId);
                  } finally {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                  }
                })
            .join();
    assertEquals(connId, asyncCheck);

    // 5. 安全反例：内部评测工作区入口绝不允许导入非临时目录下的任意仓库。
    assertThrows(
        IllegalArgumentException.class,
        () -> workspaces.createInternalEvaluationWorkspace(repo.toString(), "main"));
  }

  private MockHttpSession initializedAdminSession() throws Exception {
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
                                  "username",
                                  "e2e-admin",
                                  "password",
                                  "admin-pass-123",
                                  "displayName",
                                  "E2E管理员",
                                  "claimLegacyHistory",
                                  true))))
              .andExpect(status().isOk())
              .andReturn();
      return (MockHttpSession) result.getRequest().getSession(false);
    }
    MvcResult login =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("username", "e2e-admin", "password", "admin-pass-123"))))
            .andExpect(status().isOk())
            .andReturn();
    return (MockHttpSession) login.getRequest().getSession(false);
  }

  private Path createDemoGitRepo(String name) throws Exception {
    Path repo = Files.createDirectory(temp.resolve(name));
    git(repo, "init", "-b", "main");
    git(repo, "config", "user.email", "e2e@example.invalid");
    git(repo, "config", "user.name", "E2E");
    Files.writeString(
        repo.resolve("app.py"), "def greet(name: str) -> str:\n    return f\"hi, {name}\"\n", StandardCharsets.UTF_8);
    Files.writeString(
        repo.resolve("application.yml"), "app:\n  name: demo\n", StandardCharsets.UTF_8);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "init");
    return repo;
  }

  private static void git(Path dir, String... args) throws Exception {
    var command = new ArrayList<String>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }

  private void awaitRunStatus(String runId, RunStatus expected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (states.runSnapshot(runId).status() != expected) {
      if (System.nanoTime() >= deadline) {
        fail("等待运行 " + runId + " 进入 " + expected + " 超时，当前：" + states.runSnapshot(runId).status());
      }
      Thread.sleep(50);
    }
  }

  private RunStatus awaitTerminalRunStatus(String runId, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    RunStatus latest = states.runSnapshot(runId).status();
    while (latest.canCancel()) {
      if (System.nanoTime() >= deadline) {
        fail("等待运行 " + runId + " 结束超时，当前：" + latest);
      }
      Thread.sleep(50);
      latest = states.runSnapshot(runId).status();
    }
    return latest;
  }

  private JsonNode awaitEvalFinished(MockHttpSession session, String evalRunId, Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      String body =
          mvc.perform(get("/api/eval-lab/runs/{id}", evalRunId).session(session))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString(StandardCharsets.UTF_8);
      JsonNode detail = json.readTree(body);
      String status = detail.path("run").path("status").asText();
      if (!"QUEUED".equals(status) && !"RUNNING".equals(status) && !"CANCELLING".equals(status)) {
        return detail;
      }
      if (System.nanoTime() >= deadline) {
        fail("等待评测批次 " + evalRunId + " 结束超时，当前状态：" + status);
      }
      Thread.sleep(100);
    }
  }
}
