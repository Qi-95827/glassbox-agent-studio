package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.model.AgentModelProvider;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.planning.PlanCandidateInput;
import dev.workbench.planning.PlanCandidateModel;
import dev.workbench.planning.PlanCandidateService;
import dev.workbench.task.RunDeliveryService;
import dev.workbench.task.RunRecordService;
import dev.workbench.workspace.WorkspaceService;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/** 多文件冒泡排序走完整条链：候选确认、两个开发节点分别装配与写入、审查汇合、交付投影。 */
@AutoConfigureMockMvc
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:bubble_sort_e2e;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-bubble-e2e"
    })
@Import(BubbleSortDeliveryIntegrationTest.ScriptedModels.class)
class BubbleSortDeliveryIntegrationTest {
  private static final List<String> FILES = List.of("index.html", "sort.js", "style.css");

  @TestConfiguration
  static class ScriptedModels {
    @Bean
    @Primary
    PlanCandidateModel stubPlanCandidateModel() {
      return (instructions, input) -> new PlanCandidateModel.Reply(PLAN, "stub-plan", 10, 20);
    }

    @Bean
    @Primary
    AgentModelRegistry scriptedAgentModelRegistry() {
      return new AgentModelRegistry(
          List.of(
              new AgentModelProvider() {
                @Override
                public ModelMode mode() {
                  return ModelMode.MOCK;
                }

                @Override
                public io.agentscope.core.model.Model create(AgentExecutionRequest request) {
                  return new ScriptedWriteModel(
                      request.role(),
                      request.fileScopes(),
                      request.writeFiles(),
                      BubbleSortFixture.contents());
                }
              }));
    }
  }

  @Autowired PlanCandidateService candidates;
  @Autowired WorkspaceService workspaces;
  @Autowired RunRecordService records;
  @Autowired RunDeliveryService deliveries;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void bubbleSortMultiFileChainProducesTwoNodeDeliveriesAndReviewHandoffs() throws Exception {
    String workspaceId = workspaces.createIdeaWorkspace(FILES).id();
    var view = ready(workspaceId);
    var confirmed = candidates.confirm(view.id(), confirm(view));
    waitTerminal(confirmed.runId());

    var run = records.runSnapshot(confirmed.runId());
    assertEquals("BLOCKED", run.status().name());
    assertTrue(String.valueOf(records.run(confirmed.runId()).get("REASON")).contains("写入"));
    assertTrue(workspaces.read(workspaceId, "sort.js").content().contains("bubbleSteps"));
    assertTrue(workspaces.read(workspaceId, "index.html").content().contains("冒泡排序演示"));
    assertTrue(workspaces.read(workspaceId, "style.css").content().contains(".bar"));

    var delivery = deliveries.delivery(confirmed.runId());
    assertEquals("BLOCKED", delivery.status());
    var byKey =
        delivery.nodes().stream()
            .collect(Collectors.toMap(RunDeliveryService.NodeDelivery::key, node -> node));
    assertEquals(
        Set.of("sort.js"),
        byKey.get("engine").files().stream()
            .map(RunDeliveryService.FileDelivery::path)
            .collect(Collectors.toSet()));
    assertEquals(
        Set.of("index.html", "style.css"),
        byKey.get("ui").files().stream()
            .map(RunDeliveryService.FileDelivery::path)
            .collect(Collectors.toSet()));
    delivery
        .files()
        .forEach(file -> assertTrue(file.currentMatches(), "交付文件摘要必须与当前文件一致：" + file.path()));
    byKey.values().forEach(node -> assertTrue(node.missingRequiredFiles().isEmpty(), node.label()));
    assertEquals(2, byKey.get("review").inbound().size(), "审查必须收到两个开发节点的交接");
    assertNotNull(byKey.get("review").result());
    assertTrue(byKey.get("review").result().contains("审查完成"));

    // 两个开发节点装配为不同实例，写入回执归属于各自节点。
    var writers = delivery.nodes().stream().filter(node -> !node.files().isEmpty()).toList();
    assertEquals(2, writers.size());
    assertEquals(
        2, writers.stream().map(RunDeliveryService.NodeDelivery::nodeId).distinct().count());
    assertTrue(
        records.tools(confirmed.runId()).stream()
                .filter(tool -> "COMPLETED".equals(String.valueOf(tool.get("STATUS"))))
                .filter(tool -> "修改授权文件".equals(String.valueOf(tool.get("TOOL_NAME"))))
                .map(tool -> String.valueOf(tool.get("AGENT_ID")))
                .distinct()
                .count()
            >= 2,
        "两个开发节点必须分别产生写入回执");

    var body =
        json.readTree(
            mvc.perform(get("/api/runs/{id}/delivery", confirmed.runId()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray());
    assertEquals(3, body.path("files").size());
    assertEquals(4, body.path("nodes").size());

    // P08：交付正文只允许该运行写过的路径；当前摘要不一致时不返回正文。
    var sortFile = deliveries.file(confirmed.runId(), "sort.js");
    assertTrue(sortFile.currentMatches());
    assertTrue(sortFile.content().contains("bubbleSteps"));
    assertThrows(
        IllegalArgumentException.class,
        () -> deliveries.file(confirmed.runId(), "not-written.txt"));
    var current = workspaces.read(workspaceId, "sort.js");
    workspaces.write(workspaceId, "sort.js", current.sha256(), "changed after delivery\n");
    var stale = deliveries.file(confirmed.runId(), "sort.js");
    assertFalse(stale.currentMatches());
    assertNull(stale.content(), "摘要变化时不能返回正文");
    assertEquals(sortFile.receiptSha256(), stale.receiptSha256(), "历史回执必须保留");
    assertTrue(deliveries.file(confirmed.runId(), "style.css").currentMatches());
    var staleBody =
        json.readTree(
            mvc.perform(
                    get("/api/runs/{id}/delivery/files", confirmed.runId())
                        .param("path", "sort.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray());
    assertFalse(staleBody.path("currentMatches").asBoolean());
    assertTrue(staleBody.path("content").isNull());
    assertEquals(sortFile.receiptSha256(), staleBody.path("receiptSha256").asText());
  }

  private PlanCandidateService.CandidateView ready(String workspaceId) {
    var created =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                input(workspaceId),
                null,
                "draft-bubble-0001"));
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    var view = candidates.get(created.id());
    while (view.status().equals("GENERATING")) {
      if (System.nanoTime() > deadline) fail("候选未结算");
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
      view = candidates.get(created.id());
    }
    assertEquals("READY", view.status(), String.valueOf(view.errors()));
    return view;
  }

  private PlanCandidateService.ConfirmRequest confirm(PlanCandidateService.CandidateView view) {
    return new PlanCandidateService.ConfirmRequest(
        view.candidateSha256(), view.inputSha256(), view.inputVersion(), "mock", null);
  }

  private PlanCandidateInput input(String workspaceId) {
    return new PlanCandidateInput(
        "制作可单步演示的冒泡排序网页",
        TaskEntryKind.CODE,
        workspaceId,
        true,
        FILES,
        List.of("使用原生 JavaScript，不引入构建工具。"),
        List.of("输入 5,1,4,2,8，点下一步逐对比较，最终显示升序结果 1,2,4,5,8。", "自动播放可暂停和继续，重置后回到初始状态。"),
        "冒泡排序网页");
  }

  private void waitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (records.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline) fail("运行未结束：" + records.runSnapshot(runId).status());
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
  }

  private static final String PLAN =
      """
      {"schemaVersion":1,"nodes":[
        {"key":"spec","title":"约定排序接口","role":"ANALYZE","objective":"核对现有排序数据的格式与边界。",
         "dependsOn":[],"readFiles":["sort.js"],"writeFiles":[],
         "expectedOutputs":[{"kind":"TEXT","path":null,"required":true}],
         "acceptanceCriteria":["列出输入、单步和结果的数据结构。"]},
        {"key":"engine","title":"实现排序逻辑","role":"IMPLEMENT","objective":"生成逐轮比较与交换的结果。",
         "dependsOn":["spec"],"readFiles":["sort.js"],"writeFiles":["sort.js"],
         "expectedOutputs":[{"kind":"FILE","path":"sort.js","required":true}],
         "acceptanceCriteria":["sort.js 导出可调用的排序步骤函数。"]},
        {"key":"ui","title":"接入页面","role":"IMPLEMENT","objective":"接入输入区、柱状图和播放控制。",
         "dependsOn":["spec"],"readFiles":["index.html","style.css"],"writeFiles":["index.html","style.css"],
         "expectedOutputs":[{"kind":"FILE","path":"index.html","required":true},{"kind":"FILE","path":"style.css","required":true}],
         "acceptanceCriteria":["页面可输入数组并显示柱状条。"]},
        {"key":"review","title":"汇合审查","role":"REVIEW","objective":"核对两个开发节点的产出和证据。",
         "dependsOn":["engine","ui"],"readFiles":[],"writeFiles":[],
         "expectedOutputs":[{"kind":"TEXT","path":null,"required":true}],
         "acceptanceCriteria":["列出未验证项。"]}],
       "assumptions":[],"openQuestions":[]}
      """;
}
