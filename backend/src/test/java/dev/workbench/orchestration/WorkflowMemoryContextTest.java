package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.MemorySource;
import dev.workbench.domain.MemoryStatus;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.memory.MemoryMatch;
import dev.workbench.framework.memory.MemoryType;
import dev.workbench.memory.MemoryRetrievalService;
import dev.workbench.memory.MemoryService;
import dev.workbench.store.RunEvidenceRecordService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 动态工作流长期记忆接入。
 *
 * <p>反例对应 `F-07`：静态运行入口一直召回 V9 记忆，动态模板却完全不理会 {@code memoryEnabled}， 同一开关在模板路径上失效；同时必须有 60,000
 * 字符硬上限，避免把记忆段落静默塞爆上下文。
 */
class WorkflowMemoryContextTest {
  private final MemoryRetrievalService retrieval = mock(MemoryRetrievalService.class);
  private final MemoryService memories = mock(MemoryService.class);
  private final RunEvidenceRecordService evidence = mock(RunEvidenceRecordService.class);
  private final ObjectMapper json = new ObjectMapper();

  @org.junit.jupiter.api.BeforeEach
  void defaultToNotLoaded() {
    // 生产实现永远返回非 null 的 MemoryLoad；这里给出「尚未加载」的默认桩，避免旧用例因 null 误报。
    org.mockito.Mockito.lenient()
        .when(memories.loadForRun(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(
            new dev.workbench.memory.MemoryLoad(
                false,
                null,
                null,
                null,
                dev.workbench.memory.MemoryLoadIntegrity.NOT_RECORDED,
                List.of()));
  }

  private WorkflowMemoryContext context(int maxChars) {
    return new WorkflowMemoryContext(retrieval, memories, evidence, json, maxChars);
  }

  private static TaskDefinition task(boolean memoryEnabled, String workspaceId) {
    var task = mock(TaskDefinition.class);
    when(task.memoryEnabled()).thenReturn(memoryEnabled);
    when(task.workspaceId()).thenReturn(workspaceId);
    when(task.goal()).thenReturn("实现排序页面的单步演示并给出证据");
    when(task.constraints()).thenReturn(List.of("必须保留原生 JavaScript"));
    when(task.acceptanceCriteria()).thenReturn(List.of("可以点下一步"));
    when(task.fileScopes()).thenReturn(List.of("sort.js"));
    return task;
  }

  private static MemoryMatch match(String id, String content) {
    return new MemoryMatch(id, MemoryType.SEMANTIC, 0.82, "bm25=0.60, vector=0.70", content, 2);
  }

  @Test
  void frozenLoadIsReusedWithoutNewRecallCountOrEvent() {
    when(memories.frozenMatches("run-1")).thenReturn(List.of(match("m-1", "排序必须稳定")));

    var section = context(60_000).load(task(true, "ws-1"), "run-1");

    assertTrue(section.contains("排序必须稳定"), section);
    verifyNoInteractions(retrieval);
    verify(memories, never()).recordLoad(anyString(), anyString(), any());
    verify(evidence, never()).event(anyString(), any(), anyString());
  }

  @Test
  void zeroHitLoadRunsOnlyOnceAcrossDownstreamNodes() {
    when(memories.frozenMatches("run-1")).thenReturn(List.of());
    when(memories.loadForRun("run-1"))
        .thenReturn(
            new dev.workbench.memory.MemoryLoad(
                true,
                UUID.randomUUID(),
                java.time.Instant.now(),
                0,
                dev.workbench.memory.MemoryLoadIntegrity.CONSISTENT,
                List.of()));

    var section = context(60_000).load(task(true, "ws-1"), "run-1");

    // 0 命中同样是「已加载」：下游节点不得重复检索、重复计数或重复发事件。
    assertEquals("", section);
    verifyNoInteractions(retrieval);
    verify(memories, never()).recordLoad(anyString(), anyString(), any());
    verify(evidence, never()).event(anyString(), any(), anyString());
  }

  @Test
  void disabledMemoryLoadsNothing() {
    when(retrieval.retrieve(any(), any())).thenReturn(List.of(match("m-1", "排序必须稳定")));

    assertEquals("", context(60_000).load(task(false, "ws-1"), "run-1"));
    verifyNoInteractions(retrieval);
    verify(memories, never()).recordLoad(anyString(), anyString(), any());
  }

  @Test
  void missingWorkspaceLoadsNothing() {
    assertEquals("", context(60_000).load(task(true, null), "run-1"));
    verifyNoInteractions(retrieval);
  }

  @Test
  void enabledMemoryRecallsRecordsLoadAndEmitsEvent() {
    when(retrieval.retrieve(eq("ws-1"), any()))
        .thenReturn(List.of(match("m-1", "排序必须稳定"), match("m-2", "保留原生实现")));

    var section = context(60_000).load(task(true, "ws-1"), "run-1");

    assertTrue(section.contains("项目背景"), section);
    assertTrue(section.contains("排序必须稳定"), section);
    assertTrue(section.contains("低可信数据") || section.contains("不可信"), "记忆必须标注为低可信数据：" + section);
    verify(memories).recordLoad(eq("run-1"), eq("ws-1"), argThat(matches -> matches.size() == 2));
    verify(evidence)
        .event(eq("run-1"), eq(dev.workbench.domain.EventType.MEMORY_LOADED), anyString());
  }

  @Test
  void oversizedMemorySectionIsBlockedInsteadOfTruncated() {
    var huge = "记忆正文".repeat(2_000);
    when(retrieval.retrieve(eq("ws-1"), any())).thenReturn(List.of(match("m-1", huge)));

    var error =
        assertThrows(
            IllegalStateException.class, () -> context(1_000).load(task(true, "ws-1"), "run-1"));

    assertTrue(error.getMessage().contains("字符上限"), error.getMessage());
  }

  @Test
  void blankRunIdSkipsRecall() {
    assertEquals("", context(60_000).load(task(true, "ws-1"), "  "));
    verifyNoInteractions(retrieval);
  }

  @Test
  void categoriesAndSourcesStayUntouched() {
    // 召回只注入 ACTIVE 记忆：这里确认构造的匹配对象仍是用户确认的语义记忆。
    var match = match("m-1", "约定");
    assertEquals(MemoryType.SEMANTIC, match.memoryType());
    assertEquals(MemoryCategory.PROJECT_CONVENTION, MemoryCategory.PROJECT_CONVENTION);
    assertEquals(MemoryStatus.ACTIVE, MemoryStatus.ACTIVE);
    assertEquals(MemorySource.USER_CONFIRMED, MemorySource.USER_CONFIRMED);
  }
}
