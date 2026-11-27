package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.workbench.memory.knowledge.KnowledgeRagService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 知识库检索工具的装配与「证据不进模型上下文」边界。
 *
 * <p>工具结果同时承担两件事：给模型看的摘要，和给「召回透视」面板看的证据。两者必须分开——
 * 把 BM25/向量明细塞进模型上下文既浪费预算，也会诱导模型讨论打分而不是回答问题。
 */
class KnowledgeSearchToolTest {
  private final KnowledgeRagService rag = mock(KnowledgeRagService.class);

  private static KnowledgeRagService.RetrievalEvidence evidence() {
    return new KnowledgeRagService.RetrievalEvidence(
        "排序算法",
        true,
        List.of(
            new KnowledgeRagService.EvidenceItem(
                "chunk-1",
                "doc-1",
                "排序设计",
                "docs/sort.md",
                "边界处理",
                "数组越界需要先校验长度。",
                42,
                1.8,
                0.61,
                0.033,
                0.44,
                true,
                ""),
            new KnowledgeRagService.EvidenceItem(
                "chunk-2",
                "doc-1",
                "排序设计",
                "docs/sort.md",
                "性能",
                "大数据量下使用归并排序。",
                37,
                0.02,
                0.05,
                0.001,
                0.004,
                false,
                "BELOW_THRESHOLD")),
        1,
        42);
  }

  @Test
  void requiresANonBlankQueryBeforeTouchingTheIndex() {
    var spec = KnowledgeSearchTool.specification(rag, "ws-1", "zh-CN");
    assertThrows(IllegalArgumentException.class, () -> spec.validate().accept(Map.of("query", " ")));
    assertThrows(IllegalArgumentException.class, () -> spec.validate().accept(Map.of()));
    spec.validate().accept(Map.of("query", "排序"));
  }

  @Test
  void modelVisiblePayloadOnlyCarriesSelectedPassages() throws Exception {
    when(rag.retrieveWithEvidence("ws-1", "排序", 5)).thenReturn(evidence());
    var spec = KnowledgeSearchTool.specification(rag, "ws-1", "zh-CN");

    var result = spec.action().execute(Map.of("query", " 排序 "), () -> {});

    verify(rag).retrieveWithEvidence("ws-1", "排序", 5);
    // 入选的正文必须给到模型。
    assertEquals(1, result.passages().size());
    assertEquals("数组越界需要先校验长度。", result.passages().getFirst().content());
    // 未入选候选的正文、标题与裁剪原因都不得出现在模型可见载荷里。
    String serialized = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
    assertFalse(serialized.contains("大数据量下使用归并排序。"), serialized);
    assertFalse(serialized.contains("BELOW_THRESHOLD"), serialized);
    assertFalse(serialized.contains("性能"), serialized);
    assertEquals(2, result.totalCandidates());
    assertEquals(1, result.selectedCount());
    assertEquals(42, result.injectedTokens());
  }

  @Test
  void modelFacingSummaryCarriesCountsAndNoPassageBodies() throws Exception {
    when(rag.retrieveWithEvidence("ws-1", "排序", 5)).thenReturn(evidence());
    var spec = KnowledgeSearchTool.specification(rag, "ws-1", "zh-CN");
    var visible = spec.action().execute(Map.of("query", "排序"), () -> {});
    var summary = spec.summarize().apply(Map.of("query", "排序"), visible);

    assertTrue(summary.contains("1"), summary);
    // 摘要里不能出现检索正文，否则证据等同于再注入一次。
    assertFalse(summary.contains("数组越界需要先校验长度。"), summary);
    assertFalse(summary.contains("bm25"), summary);
  }

  @Test
  void englishSummariesStayEnglish() throws Exception {
    when(rag.retrieveWithEvidence("ws-1", "sort", 5)).thenReturn(evidence());
    var spec = KnowledgeSearchTool.specification(rag, "ws-1", "en-US");
    var visible = spec.action().execute(Map.of("query", "sort"), () -> {});
    var summary = spec.summarize().apply(Map.of("query", "sort"), visible);
    assertTrue(summary.startsWith("Knowledge base searched"), summary);
  }

  @Test
  void checkRunnableIsInvokedAroundTheSearch() throws Exception {
    when(rag.retrieveWithEvidence("ws-1", "排序", 5)).thenReturn(evidence());
    var spec = KnowledgeSearchTool.specification(rag, "ws-1", "zh-CN");
    var calls = new int[1];

    spec.action().execute(Map.of("query", "排序"), () -> calls[0]++);

    // 前后各检查一次：取消/暂停在检索前后都能及时生效。
    assertEquals(2, calls[0]);
  }
}
