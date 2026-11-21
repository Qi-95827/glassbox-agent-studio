package dev.workbench.privateagent;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.planning.PlanService;
import dev.workbench.planning.PlanCandidateInput;
import dev.workbench.framework.planning.PlanCandidateModel;
import dev.workbench.planning.PlanCandidateService;
import dev.workbench.store.RunRecordService;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import dev.workbench.store.RunRecordService;

/** 私有只读 Agent 从发布、推荐、确认到实际搜索和交接的闭环。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:private_agent_flow;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-private-agent-flow"
    })
@Import(PrivateAgentFlowIntegrationTest.StubPlan.class)
class PrivateAgentFlowIntegrationTest {
  private static final String RECOMMENDED_PLAN =
      """
      {
        "schemaVersion": 1,
        "nodes": [
          {
            "key": "inspect",
            "title": "前端交付检查",
            "role": "ANALYZE",
            "agentRef": "P1",
            "objective": "检查前端文件并列出实际读取范围和遗漏。",
            "dependsOn": [],
            "readFiles": ["App.jsx", "util.tsx", "notes.md"],
            "writeFiles": [],
            "expectedOutputs": [{"kind":"TEXT","path":null,"required":true}],
            "acceptanceCriteria": ["说明实际读取的文件与未验证事项"]
          },
          {
            "key": "summary",
            "title": "汇总检查结论",
            "role": "REVIEW",
            "objective": "汇总前端检查结论与证据。",
            "dependsOn": ["inspect"],
            "readFiles": [],
            "writeFiles": [],
            "expectedOutputs": [{"kind":"TEXT","path":null,"required":true}],
            "acceptanceCriteria": ["交接正文包含检查结论"]
          }
        ],
        "assumptions": [],
        "openQuestions": []
      }
      """;

  @TestConfiguration
  static class StubPlan {
    @Bean
    @Primary
    PlanCandidateModel stubPrivatePlanModel() {
      return (instructions, input) ->
          new PlanCandidateModel.Reply(RECOMMENDED_PLAN, "stub-private-plan", 20, 30);
    }
  }

  @Autowired PrivateComponentService components;
  @Autowired PlanCandidateService candidates;
  @Autowired PlanService plans;
  @Autowired RunRecordService records;
  @Autowired dev.workbench.task.WorkflowExecutionService workflowRuns;
  @Autowired WorkspaceService workspaces;
  @Autowired dev.workbench.workspace.WorkspaceAuthorizationService authorization;

  @Test
  void recommendedPrivateAgentPublishesVersionsAndRunsRealSearch() throws Exception {
    var skill =
        components.publishSkill(
            components
                .createSkill(
                    new PrivateComponentService.SkillRequest(
                        "前端交付检查",
                        "检查前端交互和可访问性",
                        null,
                        "读取 .jsx/.tsx 文件",
                        "列出覆盖和遗漏",
                        "# 前端交付检查\n\n只引用实际读取的文件，列出未验证事项。"))
                .id());
    var tool =
        components.publishTool(
            components
                .createTool(
                    new PrivateComponentService.ToolRequest(
                        "前端标签搜索", "只搜索前端组件文件", "SEARCH", List.of(".jsx", ".tsx")))
                .id());
    var agent =
        components.publishAgent(
            components
                .createAgent(
                    new PrivateComponentService.AgentRequest(
                        "前端交付检查 Agent",
                        "只读核对前端文件",
                        "核对页面交互与可访问性，只引用实际搜索到的文件。",
                        "列出实际读取的文件、覆盖范围和未验证事项。",
                        "ANALYST",
                        true,
                        "mock",
                        List.of(new PrivateComponentService.ComponentRef(skill.id(), null)),
                        List.of(new PrivateComponentService.ComponentRef(tool.id(), null))))
                .id());
    assertEquals(1, agent.currentVersion());
    assertTrue(agent.specSha256().matches("[0-9a-f]{64}"));

    String workspace =
        workspaces.createIdeaWorkspace(List.of("App.jsx", "util.tsx", "notes.md")).id();
    var view =
        generate(
            workspace, agent.id(), List.of(agent.id()), List.of("App.jsx", "util.tsx", "notes.md"));

    assertTrue(view.agentBindings().containsKey("inspect"), view.agentBindings().toString());
    @SuppressWarnings("unchecked")
    var binding = (Map<String, Object>) view.agentBindings().get("inspect");
    assertEquals("RECOMMENDED", binding.get("source"));
    assertEquals(agent.id(), String.valueOf(binding.get("definitionId")).replace("pa-", ""));
    assertEquals(1, binding.get("definitionVersion"));

    var confirmed = candidates.confirm(view.id(), confirm(view));
    waitTerminal(confirmed.runId());
    var run = records.run(confirmed.runId());
    assertEquals(RunStatus.COMPLETED.name(), run.get("STATUS"));
    assertEquals(
        1,
        records.tools(confirmed.runId()).stream()
            .filter(row -> "搜索授权文件".equals(String.valueOf(row.get("TOOL_NAME"))))
            .count(),
        "私有搜索工具必须实际调用");
    List<PlanNode> nodes = plans.find(confirmed.runId());
    assertEquals("pa-" + agent.id(), nodes.getFirst().definitionRef().id());
    assertEquals(1, nodes.getFirst().definitionRef().version());
    assertNotNull(nodes.getFirst().agentBinding());
    assertEquals("PRIVATE", nodes.getFirst().agentBinding().kind());
    assertTrue(workflowRuns.workflowHandoffs(confirmed.runId()).size() >= 1);

    // 已发布 v2 后，旧运行仍固定 v1。
    components.updateAgent(
        agent.id(),
        new PrivateComponentService.AgentRequest(
            "前端交付检查 Agent",
            "第二版简介",
            "第二版职责",
            "第二版输出要求",
            "ANALYST",
            true,
            "mock",
            List.of(new PrivateComponentService.ComponentRef(skill.id(), null)),
            List.of(new PrivateComponentService.ComponentRef(tool.id(), null))));
    var v2 = components.publishAgent(agent.id());
    assertEquals(2, v2.currentVersion());
    assertEquals(1, plans.find(confirmed.runId()).getFirst().definitionRef().version());
    assertEquals(
        1,
        components.agentVersions(agent.id()).stream()
            .filter(row -> row.version() == 1)
            .findFirst()
            .orElseThrow()
            .version());

    // 归档后不能进入新计划；旧版本仍可追溯。
    components.archiveAgent(agent.id());
    var rejected =
        generate(
            workspace, agent.id(), List.of(agent.id()), List.of("App.jsx", "util.tsx", "notes.md"));
    assertEquals("INVALID", rejected.status(), rejected.errors().toString());
    assertTrue(
        rejected.errors().stream()
            .anyMatch(
                reason ->
                    reason.contains("没有可用") || reason.contains("归档") || reason.contains("未发布")));

    var versions = components.agentVersions(agent.id());
    assertEquals(2, versions.size());
    assertEquals("前端交付检查 Agent", versions.getFirst().name());
  }

  @Test
  void mixedModelPlanWithBudgetRejectedBeforeRun() {
    var tool =
        components
            .publishTool(
                components
                    .createTool(
                        new PrivateComponentService.ToolRequest(
                            "费用检查搜索", "只读搜索", "SEARCH", List.of(".jsx", ".tsx")))
                    .id())
            .id();
    var agent =
        components
            .publishAgent(
                components
                    .createAgent(
                        new PrivateComponentService.AgentRequest(
                            "固定 DeepSeek 检查",
                            "只读",
                            "检查",
                            "列出文件",
                            "ANALYST",
                            true,
                            "deepseek",
                            List.of(),
                            List.of(new PrivateComponentService.ComponentRef(tool, null)),
                            "FIXED"))
                    .id());
    String workspace =
        workspaces.createIdeaWorkspace(List.of("App.jsx", "util.tsx", "notes.md")).id();
    var view =
        generate(
            workspace, agent.id(), List.of(agent.id()), List.of("App.jsx", "util.tsx", "notes.md"));
    assertEquals("READY", view.status(), view.errors().toString());
    @SuppressWarnings("unchecked")
    var binding = (Map<String, Object>) view.agentBindings().get("inspect");
    assertEquals("FIXED", binding.get("modelStrategy"));
    assertEquals("deepseek", binding.get("modelMode"));

    int tasks = records.tasks().size();
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                candidates.confirm(
                    view.id(),
                    new PlanCandidateService.ConfirmRequest(
                        view.candidateSha256(),
                        view.inputSha256(),
                        view.inputVersion(),
                        "mock",
                        1_000_000L)));
    assertTrue(failure.getMessage().contains("费用账本") || failure.getMessage().contains("多种实际模型"));
    assertEquals(tasks, records.tasks().size(), "拒绝发生在创建运行之前");
  }

  @Test
  void unpublishedAgentCannotEnterPlan() {
    var agent =
        components.createAgent(
            new PrivateComponentService.AgentRequest(
                "未发布检查", "只读", "检查", "列出文件", "ANALYST", true, "mock", List.of(), List.of()));
    String workspace =
        workspaces.createIdeaWorkspace(List.of("App.jsx", "util.tsx", "notes.md")).id();
    var input =
        new PlanCandidateInput(
            "只读检查前端交付",
            TaskEntryKind.CODE,
            workspace,
            false,
            List.of("App.jsx", "util.tsx", "notes.md"),
            List.of(),
            List.of("列出实际读取文件"),
            null,
            "MANUAL",
            agent.id(),
            null,
            null,
            true);
    var created =
        this.candidates.generate(
            new PlanCandidateService.GenerateRequest(
                dev.workbench.domain.WorkbenchIds.newId().replace("-", ""), input, null, "unpublished"));
    var view = wait(created);
    assertEquals("INVALID", view.status(), view.errors().toString());
    assertTrue(
        view.errors().stream().anyMatch(reason -> reason.contains("未发布") || reason.contains("无权")));
  }

  @Test
  void workspaceFileChangeAfterPlanRejectsConfirmation() throws Exception {
    var tool =
        components
            .publishTool(
                components
                    .createTool(
                        new PrivateComponentService.ToolRequest(
                            "变化检查搜索", "只读搜索", "SEARCH", List.of(".jsx", ".tsx")))
                    .id())
            .id();
    var agent =
        components.publishAgent(
            components
                .createAgent(
                    new PrivateComponentService.AgentRequest(
                        "变化检查",
                        "只读",
                        "检查",
                        "列出文件",
                        "ANALYST",
                        true,
                        "mock",
                        List.of(),
                        List.of(new PrivateComponentService.ComponentRef(tool, null))))
                .id());
    String workspace =
        workspaces.createIdeaWorkspace(List.of("App.jsx", "util.tsx", "notes.md")).id();
    var view =
        generate(
            workspace, agent.id(), List.of(agent.id()), List.of("App.jsx", "util.tsx", "notes.md"));
    assertEquals("READY", view.status(), view.errors().toString());
    int tasks = records.tasks().size();
    var file = Path.of(workspaces.get(workspace).workspacePath()).resolve("App.jsx");
    Files.writeString(file, "// changed after planning\n", StandardCharsets.UTF_8);
    assertThrows(
        IllegalStateException.class,
        () -> candidates.confirm(view.id(), confirm(view)),
        "规划后工作区变化必须拒绝确认");
    assertEquals(tasks, records.tasks().size(), "拒绝确认不能留下任务");
  }

  @Test
  void privateToolOutsideNodeScopeRejectsPlan() {
    var tool =
        components.publishTool(
            components
                .createTool(
                    new PrivateComponentService.ToolRequest(
                        "只搜样式", "只允许 css", "SEARCH", List.of(".css")))
                .id());
    var agent =
        components.publishAgent(
            components
                .createAgent(
                    new PrivateComponentService.AgentRequest(
                        "前端检查",
                        "只读",
                        "检查前端",
                        "列出文件",
                        "ANALYST",
                        true,
                        "mock",
                        List.of(),
                        List.of(new PrivateComponentService.ComponentRef(tool.id(), null))))
                .id());
    String workspace =
        workspaces.createIdeaWorkspace(List.of("App.jsx", "util.tsx", "notes.md")).id();
    var view =
        generate(
            workspace, agent.id(), List.of(agent.id()), List.of("App.jsx", "util.tsx", "notes.md"));
    assertEquals("INVALID", view.status(), view.errors().toString());
    assertTrue(
        view.errors().stream()
            .anyMatch(reason -> reason.contains("文件范围") || reason.contains("不匹配")));
  }

  private PlanCandidateService.CandidateView generate(
      String workspace, String agentId, List<String> candidates, List<String> files) {
    var input =
        new PlanCandidateInput(
            "只读检查前端交付",
            TaskEntryKind.CODE,
            workspace,
            false,
            files,
            List.of("只读"),
            List.of("列出实际读取文件"),
            null,
            "RECOMMEND",
            null,
            null,
            candidates,
            true);
    var created =
        this.candidates.generate(
            new PlanCandidateService.GenerateRequest(
                dev.workbench.domain.WorkbenchIds.newId().replace("-", ""), input, null, "private-" + agentId));
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    var view = created;
    while (view.status().equals("GENERATING")) {
      if (System.nanoTime() > deadline) fail("候选未结算");
      try {
        Thread.sleep(30);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
      view = this.candidates.get(created.id());
    }
    return view;
  }

  private PlanCandidateService.CandidateView wait(PlanCandidateService.CandidateView created) {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    var view = created;
    while (view.status().equals("GENERATING")) {
      if (System.nanoTime() > deadline) fail("候选未结算");
      try {
        Thread.sleep(30);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
      view = this.candidates.get(created.id());
    }
    return view;
  }

  private static PlanCandidateService.ConfirmRequest confirm(
      PlanCandidateService.CandidateView view) {
    return new PlanCandidateService.ConfirmRequest(
        view.candidateSha256(), view.inputSha256(), view.inputVersion(), "mock", null);
  }

  private void waitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
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
}
