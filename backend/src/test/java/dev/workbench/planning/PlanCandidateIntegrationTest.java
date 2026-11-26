package dev.workbench.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.store.RunRecordService;
import dev.workbench.workspace.WorkspaceService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import dev.workbench.framework.planning.PlanCandidateModel;
import dev.workbench.store.RunRecordService;

/** 候选计划只保存草稿，不创建任务、运行或工作区写入。 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:plan_candidates;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-plan-candidates"
    })
@Import(PlanCandidateIntegrationTest.StubModel.class)
class PlanCandidateIntegrationTest {
  private static final String VALID = validPlan();

  static final BlockingQueue<String> REPLIES = new LinkedBlockingQueue<>();
  static final AtomicInteger PROPOSALS = new AtomicInteger();

  @TestConfiguration
  static class StubModel {
    @Bean
    @Primary
    PlanCandidateModel stubPlanCandidateModel() {
      return (instructions, input) -> {
        String reply = null;
        // 模拟不响应中断的模型提供方：取消失败时仍会返回，服务端必须靠状态条件结算拒绝。
        while (reply == null) {
          try {
            reply = REPLIES.poll(10, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
            /* 继续等待，用于验证晚到结果不能改写取消状态。 */
          }
        }
        PROPOSALS.incrementAndGet();
        if (reply.equals("NO_REPLY")) throw new IllegalStateException("测试没有提供模型响应。");
        return new PlanCandidateModel.Reply(reply, "stub-plan", 120, 80);
      };
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired PlanCandidateService candidates;
  @Autowired WorkspaceService workspaces;
  @Autowired dev.workbench.workspace.WorkspaceAuthorizationService workspaceAuthorization;
  @Autowired RunRecordService records;

  @BeforeEach
  void drain() {
    REPLIES.clear();
  }

  @Test
  void validCandidateIsAPersistedDraftWithoutCreatingRunsOrWrites() throws Exception {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    int tasks = records.tasks().size();
    int workspacesBefore = workspaces.list().size();
    REPLIES.offer(VALID);

    var response =
        mvc.perform(
                post("/api/plan-candidates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "requestId",
                                "req-valid-0001",
                                "draftKey",
                                "draft-valid-0001",
                                "input",
                                input(workspace, "制作可单步演示的冒泡排序网页")))))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode created = json.readTree(response.getResponse().getContentAsByteArray());
    String id = created.path("id").asText();
    assertEquals("GENERATING", created.path("status").asText());

    var view = await(id);
    assertEquals("READY", view.status());
    assertNotNull(view.candidateSha256());
    assertTrue(view.candidateSha256().matches("[0-9a-f]{64}"));
    assertEquals(
        List.of("spec", "engine", "ui", "review"),
        view.candidate().nodes().stream().map(PlanCandidateDraft.Node::key).toList());
    assertEquals("stub-plan", view.modelName());
    assertEquals(120, view.inputTokens());
    assertEquals(80, view.outputTokens());
    assertEquals(tasks, records.tasks().size(), "生成候选不能创建任务或运行");
    assertEquals(workspacesBefore, workspaces.list().size(), "生成候选不能创建工作区");
    assertEquals("", workspaces.read(workspace, "sort.js").content(), "生成候选不能写文件");
    assertTrue(view.errors().isEmpty());

    // 同一请求标识和相同输入重试返回同一候选，不会重复调用模型。
    int proposals = PROPOSALS.get();
    var repeated =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-valid-0001", input(workspace, "制作可单步演示的冒泡排序网页"), null, "draft-valid-0001"));
    assertEquals(id, repeated.id());
    assertEquals(proposals, PROPOSALS.get());
  }

  @Test
  void invalidCandidateIsRejectedWithLocatedReasonsAndCreatesNothing() throws Exception {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    int tasks = records.tasks().size();
    REPLIES.offer(invalidPlan());
    var created =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-invalid-0001", input(workspace, "制作越权的排序网页"), null, "draft-invalid-0001"));
    var view = await(created.id());
    assertEquals("INVALID", view.status());
    assertNull(view.candidateSha256());
    assertTrue(view.errors().stream().anyMatch(reason -> reason.contains("secret.txt")));
    assertTrue(view.errors().stream().anyMatch(reason -> reason.contains("重叠")));
    assertTrue(view.errors().stream().anyMatch(reason -> reason.contains("审查")));
    assertEquals(tasks, records.tasks().size());

    // 同一请求标识改内容必须冲突，不能复用旧候选。
    mvc.perform(
            post("/api/plan-candidates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "requestId",
                            "req-invalid-0001",
                            "draftKey",
                            "draft-invalid-0001",
                            "input",
                            input(workspace, "完全不同的任务目标")))))
        .andExpect(status().isConflict());
  }

  @Test
  void optionalFileOutputDoesNotCountAsRequiredDeliverable() throws Exception {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    REPLIES.offer(optionalOnlyPlan());
    var created =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-optional-0001",
                input(workspace, "制作可单步演示的冒泡排序网页"),
                null,
                "draft-optional-0001"));
    var view = await(created.id());
    assertEquals("INVALID", view.status());
    assertTrue(
        view.errors().stream().anyMatch(reason -> reason.contains("必需的文件产出")),
        "可选文件产出不能被当作必需交付：" + view.errors());
  }

  @Test
  void newDraftVersionMakesPreviousCandidateStaleEvenWhenGoalChanges() throws Exception {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    REPLIES.offer(VALID);
    var first =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-version-0001",
                input(workspace, "制作可单步演示的冒泡排序网页"),
                null,
                "draft-version-0001"));
    assertEquals("READY", await(first.id()).status());

    // 同一草稿修改目标后重新生成：分组保持不变，旧候选立即过期。
    REPLIES.offer(VALID);
    var second =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-version-0002",
                input(workspace, "把排序网页改成支持负数与重复值"),
                null,
                "draft-version-0001"));
    var latest = await(second.id());
    assertEquals("READY", latest.status());
    assertEquals(first.groupId(), latest.groupId());
    assertEquals(1, first.inputVersion());
    assertEquals(2, latest.inputVersion());
    assertEquals("STALE", candidates.get(first.id()).status());
  }

  @Test
  void cancelSurvivesLateModelResponse() throws Exception {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    int proposals = PROPOSALS.get();
    var created =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-cancel-0001", input(workspace, "制作可单步演示的冒泡排序网页"), null, "draft-cancel-0001"));
    assertEquals("GENERATING", created.status());
    assertEquals("CANCELLED", candidates.cancel(created.id()).status());

    REPLIES.offer(VALID);
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (PROPOSALS.get() == proposals) {
      if (System.nanoTime() > deadline) fail("模型响应没有被消费。");
      Thread.sleep(25);
    }
    // 晚到的模型结果不能把取消状态改写为 READY，也不能补写候选正文。
    long settleDeadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (System.nanoTime() < settleDeadline) {
      var view = candidates.get(created.id());
      assertEquals("CANCELLED", view.status());
      assertNull(view.candidate(), "取消后不能保存模型候选正文");
      Thread.sleep(50);
    }
  }

  @Test
  void cancellingAQueuedCandidateSkipsTheModelCall() throws Exception {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    int proposals = PROPOSALS.get();
    // 第一个候选占住单线程执行器并等待模型；第二个候选只能排队。
    var running =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-queue-0001", input(workspace, "制作可单步演示的冒泡排序网页"), null, "draft-queue-0001"));
    var queued =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-queue-0002", input(workspace, "制作另一个排序网页"), null, "draft-queue-0002"));
    assertEquals("GENERATING", queued.status());
    assertEquals("CANCELLED", candidates.cancel(queued.id()).status());

    REPLIES.offer(VALID);
    assertEquals("READY", await(running.id()).status());
    // 给被取消的排队任务留出执行窗口；它必须是取消状态且没有消耗模型。
    Thread.sleep(300);
    assertEquals("CANCELLED", candidates.get(queued.id()).status());
    assertEquals(proposals + 1, PROPOSALS.get(), "被取消的排队候选不能调用模型");
  }

  private PlanCandidateService.CandidateView await(String id) {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    var view = candidates.get(id);
    while (view.status().equals("GENERATING")) {
      if (System.nanoTime() > deadline) fail("候选未在时限内结算：" + id + "；状态 " + view.status());
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
      view = candidates.get(id);
    }
    return view;
  }

  private static final List<String> FILES = List.of("index.html", "sort.js", "style.css");

  @Test
  void revisedDraftReplacesTheCandidateAndInvalidDraftIsRejectedWithoutTouchingIt() throws Exception {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    int tasks = records.tasks().size();
    REPLIES.offer(VALID);
    var created =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-revise-0001",
                input(workspace, "制作可单步演示的冒泡排序网页"),
                null,
                "draft-revise-0001"));
    var view = await(created.id());
    assertEquals("READY", view.status());

    // 人工把审查节点的标题改掉：结构仍然合法，必须落库、换成新摘要，且不创建运行。
    var reviewed =
        view.candidate().nodes().stream()
            .map(
                node ->
                    "review".equals(node.key())
                        ? new PlanCandidateDraft.Node(
                            node.key(),
                            "人工改过的交付审查",
                            node.role(),
                            node.objective(),
                            node.dependsOn(),
                            node.readFiles(),
                            node.writeFiles(),
                            node.expectedOutputs(),
                            node.acceptanceCriteria(),
                            node.agentRef())
                        : node)
            .toList();
    var edited =
        new PlanCandidateDraft(
            view.candidate().schemaVersion(),
            reviewed,
            view.candidate().assumptions(),
            view.candidate().openQuestions(),
            view.candidate().revisionEdges());
    var applied = revise(created.id(), view.candidateSha256(), edited).andExpect(status().isOk()).andReturn();
    JsonNode appliedBody = json.readTree(applied.getResponse().getContentAsByteArray());
    assertTrue(appliedBody.path("applied").asBoolean(), appliedBody.toString());
    String revisedSha = appliedBody.path("candidate").path("candidateSha256").asText();
    assertNotEquals(view.candidateSha256(), revisedSha, "改写后摘要必须变化，确认时才不会再撞旧摘要");
    assertEquals("人工改过的交付审查", titleOf(appliedBody, "review"));
    assertEquals(revisedSha, candidates.get(created.id()).candidateSha256());
    assertEquals(tasks, records.tasks().size(), "改写候选不能创建任务或运行");
    assertEquals("", workspaces.read(workspace, "sort.js").content(), "改写候选不能写文件");

    // 去掉审查节点的验收条件：服务端必须整份拒绝，并且候选保持上一次的摘要。
    var broken =
        new PlanCandidateDraft(
            view.candidate().schemaVersion(),
            reviewed.stream()
                .map(
                    node ->
                        "review".equals(node.key())
                            ? new PlanCandidateDraft.Node(
                                node.key(),
                                node.title(),
                                node.role(),
                                node.objective(),
                                node.dependsOn(),
                                node.readFiles(),
                                node.writeFiles(),
                                node.expectedOutputs(),
                                List.of(),
                                node.agentRef())
                            : node)
                .toList(),
            List.of(),
            List.of(),
            view.candidate().revisionEdges());
    var rejected =
        revise(created.id(), revisedSha, broken).andExpect(status().isOk()).andReturn();
    JsonNode rejectedBody = json.readTree(rejected.getResponse().getContentAsByteArray());
    assertFalse(rejectedBody.path("applied").asBoolean());
    assertFalse(rejectedBody.path("errors").isEmpty(), "拒绝时必须给出可操作原因");
    assertEquals(revisedSha, candidates.get(created.id()).candidateSha256(), "被拒绝的改写不能落库");

    // 客户端拿着过期摘要提交时必须拒绝，避免并发覆盖别人的修改。
    revise(created.id(), "0".repeat(64), edited).andExpect(status().isBadRequest());
  }

  private org.springframework.test.web.servlet.ResultActions revise(
      String id, String expectedCandidateSha256, PlanCandidateDraft draft) throws Exception {
    return mvc.perform(
        put("/api/plan-candidates/{id}/draft", id)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsBytes(
                    Map.of("expectedCandidateSha256", expectedCandidateSha256, "draft", draft))));
  }

  private static String titleOf(JsonNode body, String key) {
    for (JsonNode node : body.path("candidate").path("candidate").path("nodes")) {
      if (key.equals(node.path("key").asText())) return node.path("title").asText();
    }
    return null;
  }

  @Test
  void wholeWorkspaceScopeIsExpandedBeforePlanning() {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    REPLIES.offer(VALID);
    var created =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-scope-0001", wholeScopeInput(workspace), null, "draft-scope-0001"));
    var view = await(created.id());

    assertEquals("READY", view.status(), String.valueOf(view.errors()));
    // 输入快照必须保存服务端展开后的有效文件集合与原始目录范围。
    assertEquals(List.of("."), view.input().directoryScopes());
    assertEquals(List.of(), view.input().excludedPaths());
    assertEquals(FILES, view.input().fileScopes());
    assertEquals(view.input().sha256(), view.inputSha256());
    assertTrue(view.candidate().nodes().stream().anyMatch(node -> !node.readFiles().isEmpty()));
  }

  @Test
  void wholeWorkspaceCandidateCanBeConfirmedIntoARun() throws Exception {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    REPLIES.offer(VALID);
    var created =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-scope-confirm-0001", wholeScopeInput(workspace), null, "draft-scope-confirm"));
    var view = await(created.id());
    assertEquals("READY", view.status(), String.valueOf(view.errors()));

    var confirmed =
        candidates.confirm(
            created.id(),
            new PlanCandidateService.ConfirmRequest(
                view.candidateSha256(), view.inputSha256(), view.inputVersion(), "mock", null));

    // 候选被唯一消费：确认事务提交后再次确认返回同一运行。
    assertEquals(confirmed.runId(), candidates.get(created.id()).runId());
    // 运行沿用展开后的有效文件集合；工具边界仍按任务文件授权与目录范围复验。
    assertEquals(FILES, records.taskDefinition(confirmed.taskId()).fileScopes());
    workspaceAuthorization.requireCurrent(confirmed.taskId());
  }

  private PlanCandidateInput wholeScopeInput(String workspaceId) {
    return new PlanCandidateInput(
        "制作可单步演示的冒泡排序网页",
        TaskEntryKind.CODE,
        workspaceId,
        true,
        List.of(),
        List.of("使用原生 JavaScript，不引入构建工具。"),
        List.of("输入 5,1,4,2,8，单步执行后显示升序结果 1,2,4,5,8。"),
        "冒泡排序网页",
        "BUILTIN",
        null,
        null,
        List.of(),
        false,
        null,
        null,
        List.of("."),
        List.of());
  }

  private PlanCandidateInput input(String workspaceId, String goal) {
    return new PlanCandidateInput(
        goal,
        TaskEntryKind.CODE,
        workspaceId,
        true,
        FILES,
        List.of("使用原生 JavaScript，不引入构建工具。"),
        List.of("输入 5,1,4,2,8，单步执行后显示升序结果 1,2,4,5,8。"),
        "冒泡排序网页");
  }

  private static String validPlan() {
    return """
    {"schemaVersion":1,"nodes":[
      {"key":"spec","title":"约定排序接口","role":"ANALYZE","objective":"确定输入、单步和结果的数据格式。",
       "dependsOn":[],"readFiles":[],"writeFiles":[],
       "expectedOutputs":[{"kind":"TEXT","path":null,"required":true}],
       "acceptanceCriteria":["列出输入、单步和结果的数据结构。"]},
      {"key":"engine","title":"实现排序逻辑","role":"IMPLEMENT","objective":"生成逐轮比较与交换的结果。",
       "dependsOn":["spec"],"readFiles":["sort.js"],"writeFiles":["sort.js"],
       "expectedOutputs":[{"kind":"FILE","path":"sort.js","required":true}],
       "acceptanceCriteria":["sort.js 导出可调用的排序步骤函数。"]},
      {"key":"ui","title":"接入页面","role":"IMPLEMENT","objective":"接入输入区、柱状图和播放控制。",
       "dependsOn":["spec"],"readFiles":["index.html","style.css"],"writeFiles":["index.html","style.css"],
       "expectedOutputs":[{"kind":"FILE","path":"index.html","required":true},{"kind":"FILE","path":"style.css","required":false}],
       "acceptanceCriteria":["页面可输入数组并显示柱状条。"]},
      {"key":"review","title":"汇合审查","role":"REVIEW","objective":"核对两个开发节点的产出和证据。",
       "dependsOn":["engine","ui"],"readFiles":[],"writeFiles":[],
       "expectedOutputs":[{"kind":"TEXT","path":null,"required":true}],
       "acceptanceCriteria":["列出未验证项。"]}],
     "assumptions":["页面使用原生 JavaScript。"],"openQuestions":[]}
    """;
  }

  private static String invalidPlan() {
    return """
    {"schemaVersion":1,"nodes":[
      {"key":"engine","title":"越权写文件","role":"IMPLEMENT","objective":"尝试写入授权外文件。",
       "dependsOn":[],"readFiles":["sort.js","secret.txt"],"writeFiles":["sort.js","secret.txt"],
       "expectedOutputs":[{"kind":"FILE","path":"secret.txt","required":true}],
       "acceptanceCriteria":["写出文件。"]},
      {"key":"ui","title":"重复写文件","role":"IMPLEMENT","objective":"与另一节点争用同一文件。",
       "dependsOn":[],"readFiles":["index.html","sort.js"],"writeFiles":["index.html","sort.js"],
       "expectedOutputs":[{"kind":"FILE","path":"index.html","required":true}],
       "acceptanceCriteria":["写出页面。"]}],
     "assumptions":[],"openQuestions":[]}
    """;
  }

  private static String optionalOnlyPlan() {
    return """
    {"schemaVersion":1,"nodes":[
      {"key":"engine","title":"实现排序逻辑","role":"IMPLEMENT","objective":"实现排序逻辑。",
       "dependsOn":[],"readFiles":["sort.js"],"writeFiles":["sort.js"],
       "expectedOutputs":[{"kind":"FILE","path":"sort.js","required":false}],
       "acceptanceCriteria":["写出 sort.js。"]},
      {"key":"review","title":"审查","role":"REVIEW","objective":"审查结果。",
       "dependsOn":["engine"],"readFiles":[],"writeFiles":[],
       "expectedOutputs":[{"kind":"TEXT","path":null,"required":true}],
       "acceptanceCriteria":["列出未验证项。"]}],
     "assumptions":[],"openQuestions":[]}
    """;
  }
}
