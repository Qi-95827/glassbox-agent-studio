package dev.workbench.privateagent;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.model.AgentModelProvider;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.planning.PlanService;
import dev.workbench.planning.PlanCandidateInput;
import dev.workbench.framework.planning.PlanCandidateModel;
import dev.workbench.planning.PlanCandidateService;
import dev.workbench.task.CostService;
import dev.workbench.store.RunRecordService;
import dev.workbench.workspace.WorkspaceService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
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
import reactor.core.publisher.Flux;
import dev.workbench.framework.planning.PlanCandidateModel;
import dev.workbench.planning.PlanService;
import dev.workbench.infrastructure.agentscope.AgentScopeModelClient;
import dev.workbench.store.RunRecordService;

/** 固定模型节点在确认页、装配和费用记录中使用同一模型，并用桩提供者避免真实调用。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:private_agent_model_budget;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-private-agent-budget"
    })
@Import(PrivateAgentModelBudgetIntegrationTest.StubModels.class)
class PrivateAgentModelBudgetIntegrationTest {
  private static final String PLAN =
      """
      {
        "schemaVersion": 1,
        "nodes": [
          {
            "key": "inspect",
            "title": "固定模型分析",
            "role": "ANALYZE",
            "agentRef": "P1",
            "objective": "检查前端文件。",
            "dependsOn": [],
            "readFiles": ["App.jsx", "notes.md"],
            "writeFiles": [],
            "expectedOutputs": [{"kind":"TEXT","path":null,"required":true}],
            "acceptanceCriteria": ["列出读取范围"]
          },
          {
            "key": "summary",
            "title": "固定模型汇总",
            "role": "REVIEW",
            "agentRef": "P2",
            "objective": "汇总检查结论。",
            "dependsOn": ["inspect"],
            "readFiles": [],
            "writeFiles": [],
            "expectedOutputs": [{"kind":"TEXT","path":null,"required":true}],
            "acceptanceCriteria": ["包含交接正文"]
          }
        ],
        "assumptions": [],
        "openQuestions": []
      }
      """;

  @TestConfiguration
  static class StubModels {
    @Bean
    @Primary
    PlanCandidateModel stubPlan() {
      return (instructions, input) -> new PlanCandidateModel.Reply(PLAN, "stub-plan", 10, 10);
    }

    @Bean
    @Primary
    AgentModelRegistry stubRegistry() {
      return new AgentModelRegistry(
          List.of(
              provider(ModelMode.MOCK, PrivateAgentModelBudgetIntegrationTest::stubModel),
              provider(ModelMode.DEEPSEEK, PrivateAgentModelBudgetIntegrationTest::stubModel)));
    }

    private static AgentModelProvider provider(ModelMode mode, ModelFactory factory) {
      return new AgentModelProvider() {
        @Override
        public ModelMode mode() {
          return mode;
        }

        @Override
        public dev.workbench.framework.model.ModelClient create(AgentExecutionRequest request) {
          return new AgentScopeModelClient(factory.create(request));
        }
      };
    }
  }

  @FunctionalInterface
  interface ModelFactory {
    Model create(AgentExecutionRequest request);
  }

  private static Model stubModel(AgentExecutionRequest request) {
    if (request.role() == dev.workbench.domain.AgentRole.REVIEWER)
      return new Model() {
        @Override
        public String getModelName() {
          return "stub-review";
        }

        @Override
        public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
          return Flux.just(
              ChatResponse.builder()
                  .content(List.of(TextBlock.builder().text("桩审查：已核对上游结论。").build()))
                  .build());
        }
      };
    return new dev.workbench.infrastructure.model.DemoModel(
        request.role(), request.workspaceId() != null, request.scenario(), request.fileScopes());
  }

  @Autowired PrivateComponentService components;
  @Autowired PlanCandidateService candidates;
  @Autowired PlanService plans;
  @Autowired RunRecordService records;
  @Autowired WorkspaceService workspaces;
  @Autowired CostService costs;

  @Test
  void fixedModelIsShownInCandidateAssemblyAndCostLedger() {
    var tool =
        components
            .publishTool(
                components
                    .createTool(
                        new PrivateComponentService.ToolRequest(
                            "费用模型搜索", "只读搜索", "SEARCH", List.of(".jsx", ".tsx")))
                    .id())
            .id();
    var analyst =
        publishAgent(
            "固定 Mock 分析",
            "ANALYST",
            "检查",
            "列出文件",
            List.of(new PrivateComponentService.ComponentRef(tool, null)));
    var reviewer =
        publishAgent("固定 Mock 审查", "REVIEWER", "审查", "给出结论", List.of());
    String workspace = workspaces.createIdeaWorkspace(List.of("App.jsx", "notes.md")).id();
    var input =
        new PlanCandidateInput(
            "只读检查前端",
            TaskEntryKind.CODE,
            workspace,
            false,
            List.of("App.jsx", "notes.md"),
            List.of(),
            List.of("列出实际读取文件"),
            null,
            "RECOMMEND",
            null,
            null,
            List.of(analyst.id(), reviewer.id()),
            true);
    var created =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                dev.workbench.domain.WorkbenchIds.newId().replace("-", ""), input, null, "budget-key-0001"));
    var view = wait(created);
    assertEquals("READY", view.status(), view.errors().toString());
    @SuppressWarnings("unchecked")
    var inspect = (Map<String, Object>) view.agentBindings().get("inspect");
    @SuppressWarnings("unchecked")
    var summary = (Map<String, Object>) view.agentBindings().get("summary");
    assertEquals("FIXED", inspect.get("modelStrategy"));
    assertEquals("mock", inspect.get("modelMode"));
    assertEquals("FIXED", summary.get("modelStrategy"));
    assertEquals("mock", summary.get("modelMode"));

    // 运行模型选择 deepseek，但两个私有节点都固定 mock；混合校验只按实际节点模型计算。
    var confirmed =
        candidates.confirm(
            view.id(),
            new PlanCandidateService.ConfirmRequest(
                view.candidateSha256(),
                view.inputSha256(),
                view.inputVersion(),
                "deepseek",
                1_000_000L));
    waitTerminal(confirmed.runId());
    assertEquals(
        ModelMode.MOCK, records.taskDefinition(confirmed.taskId()).modelMode());
    List<PlanNode> nodes = plans.find(confirmed.runId());
    assertEquals(2, nodes.size());
    assertTrue(nodes.stream().allMatch(node -> node.definitionRef().id().startsWith("pa-")));
    var assemblies = records.agentAssemblies(confirmed.runId());
    assertFalse(assemblies.isEmpty());
    assertTrue(
        assemblies.stream()
            .allMatch(
                recorded ->
                    recorded.snapshot().components().model().mode() == ModelMode.MOCK));
    var estimate = costs.estimate(confirmed.runId());
    assertNotNull(estimate);
    assertEquals("mock", estimate.get("PRICE_MODEL"));
    assertEquals(1_000_000L, estimate.get("MAX_COST_MICROS"));
  }

  private PrivateComponentService.AgentView publishAgent(
      String name,
      String role,
      String duty,
      String output,
      List<PrivateComponentService.ComponentRef> tools) {
    var created =
        components.createAgent(
            new PrivateComponentService.AgentRequest(
                name,
                "桩模型测试",
                duty,
                output,
                role,
                true,
                "mock",
                List.of(),
                tools,
                "FIXED"));
    return components.publishAgent(created.id());
  }

  private PlanCandidateService.CandidateView wait(PlanCandidateService.CandidateView created) {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    var view = created;
    while ("GENERATING".equals(view.status())) {
      if (System.nanoTime() > deadline) fail("候选未结算");
      try {
        Thread.sleep(30);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
      view = candidates.get(created.id());
    }
    return view;
  }

  private void waitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (records.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline) fail("运行未结束");
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
  }
}
