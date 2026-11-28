package dev.workbench.delivery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.planning.PlanService;
import dev.workbench.store.GlassboxTraceStore;
import dev.workbench.store.RunRecordService;
import dev.workbench.store.RunStateStore;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 单节点详情必须按节点下推载荷查询。
 *
 * <p>回归：{@code detail()} 之前调用 {@code modelPayloadsByRun}/{@code toolTracesByRun} 拉整次运行所有节点的
 * 64KB 级载荷。这里用真实两节点运行验证：查询节点 A 时只按节点 A 的调用 ID 下推，节点 B 的载荷绝不进入本次查询，
 * 且整运行的兜底查询一次都不会发生。
 */
@SpringBootTest(
    classes = dev.workbench.app.WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:node_detail_pushdown;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-node-detail"
    })
class ExecutionNodeDetailPushdownIntegrationTest {
  @Autowired ExecutionViewService executionViews;
  @Autowired PlanService plans;
  @Autowired RunService runs;
  @Autowired RunRecordService records;
  @Autowired RunStateStore states;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  GlassboxTraceStore traces;

  @Test
  void detailQueriesOnlyTheRequestedNodePayloads() {
    // 纯文本任务固定物化为两节点标准工作流：ANALYST → REVIEWER。
    String runId =
        runs.create(
            CreateTaskRequest.builder()
                .goal("单节点载荷按需下推验证")
                .scenario("normal")
                .limits(4, 2, 30)
                .modelMode("mock")
                .build());
    awaitTerminal(runId);
    var nodes = plans.find(runId);
    assertEquals(2, nodes.size(), "标准纯文本工作流应有两节点");
    var target = nodes.getFirst();
    String targetAgent = target.agentId().toString();

    org.mockito.Mockito.clearInvocations(traces);
    var detail = assertDoesNotThrow(() -> executionViews.detail(runId, target.id().toString()));
    assertNotNull(detail);
    assertFalse(detail.attempts().isEmpty(), "目标节点必须能读到尝试记录");

    @SuppressWarnings("unchecked")
    org.mockito.ArgumentCaptor<Collection<String>> captor =
        org.mockito.ArgumentCaptor.forClass(Collection.class);
    verify(traces).modelPayloadsByIds(org.mockito.ArgumentMatchers.eq(runId), captor.capture());
    Set<String> expected =
        records.models(runId).stream()
            .filter(row -> targetAgent.equals(String.valueOf(row.get("AGENT_ID"))))
            .map(row -> String.valueOf(row.get("ID")))
            .collect(Collectors.toSet());
    assertEquals(expected, Set.copyOf(captor.getValue()), "只允许下推本节点的模型调用 ID");

    @SuppressWarnings("unchecked")
    org.mockito.ArgumentCaptor<Collection<String>> toolCaptor =
        org.mockito.ArgumentCaptor.forClass(Collection.class);
    verify(traces).toolTracesByIds(org.mockito.ArgumentMatchers.eq(runId), toolCaptor.capture());
    Set<String> expectedTools =
        records.tools(runId).stream()
            .filter(row -> targetAgent.equals(String.valueOf(row.get("AGENT_ID"))))
            .map(row -> String.valueOf(row.get("ID")))
            .collect(Collectors.toSet());
    assertEquals(expectedTools, Set.copyOf(toolCaptor.getValue()), "只允许下推本节点的工具调用 ID");

    verify(traces, never()).modelPayloadsByRun(any());
    verify(traces, never()).toolTracesByRun(any());

    // 另一个节点同样可以安全读取（覆盖交接渲染路径不因空依赖节点而 NPE）。
    var other = nodes.getLast();
    assertDoesNotThrow(() -> executionViews.detail(runId, other.id().toString()));
  }

  private void awaitTerminal(String runId) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
    while (true) {
      var status = states.runSnapshot(runId).status();
      if (!status.canCancel()) return;
      if (System.nanoTime() >= deadline) throw new AssertionError("运行未结束：" + runId);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("等待被中断");
      }
    }
  }
}
