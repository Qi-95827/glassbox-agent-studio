package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.model.AgentModelProvider;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.planning.PlanCandidateInput;
import dev.workbench.framework.planning.PlanCandidateModel;
import dev.workbench.planning.PlanCandidateService;
import dev.workbench.store.RunRecordService;
import dev.workbench.workspace.WorkspaceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import dev.workbench.framework.planning.PlanCandidateModel;
import dev.workbench.infrastructure.agentscope.AgentScopeModelClient;
import dev.workbench.store.RunRecordService;

/**
 * 浏览器验收夹具：把多文件冒泡排序运行写入隔离文件数据库和工作区。
 *
 * <p>类名不以 Test 结尾，不进入默认回归；需要时用 {@code -Dtest=BubbleSortBrowserFixture} 显式执行。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/agent-workbench-browser-task3/browser;DB_CLOSE_ON_EXIT=FALSE",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-browser-task3/workspaces"
    })
@Import(BubbleSortBrowserFixture.ScriptedModels.class)
class BubbleSortBrowserFixture {
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
                public dev.workbench.framework.model.ModelClient create(AgentExecutionRequest request) {
                  return new AgentScopeModelClient(new ScriptedWriteModel(
                      request.role(),
                      request.fileScopes(),
                      request.writeFiles(),
                      BubbleSortFixture.contents()));
                }
              }));
    }
  }

  @Autowired PlanCandidateService candidates;
  @Autowired WorkspaceService workspaces;
  @Autowired RunRecordService records;

  @Test
  void seedBubbleSortRunForBrowserReview() throws Exception {
    String workspaceId = workspaces.createIdeaWorkspace(FILES).id();
    var view = ready(workspaceId);
    var confirmed = candidates.confirm(view.id(), confirm(view));
    waitTerminal(confirmed.runId());
    assertEquals("BLOCKED", records.runSnapshot(confirmed.runId()).status().name());
    var root = Path.of(System.getProperty("java.io.tmpdir"), "agent-workbench-browser-task3");
    Files.writeString(
        root.resolve("fixture.txt"),
        "runId="
            + confirmed.runId()
            + "\nworkspaceId="
            + workspaceId
            + "\n"
            + "workspacePath="
            + workspaces.get(workspaceId).workspacePath()
            + "\n",
        java.nio.charset.StandardCharsets.UTF_8);
  }

  private PlanCandidateService.CandidateView ready(String workspaceId) {
    var created =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                input(workspaceId),
                null,
                "draft-browser-0001"));
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    var view = candidates.get(created.id());
    while (view.status().equals("GENERATING")) {
      if (System.nanoTime() > deadline) throw new IllegalStateException("候选未结算");
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("等待被中断");
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
      if (System.nanoTime() > deadline) throw new IllegalStateException("运行未结束");
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("等待被中断");
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
