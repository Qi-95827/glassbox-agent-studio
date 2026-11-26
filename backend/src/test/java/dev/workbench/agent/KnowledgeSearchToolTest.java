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
  void returnsStructuredEvidenceForTheXRayPanel() throws Exception {
    when(rag.retrieveWithEvidence("ws-1", "排序", 5)).thenReturn(evidence());
    var spec = KnowledgeSearchTool.specification(rag, "ws-1", "zh-CN");

    var result = spec.action().execute(Map.of("query", " 排序 "), () -> {});

    verify(rag).retrieveWithEvidence("ws-1", "排序", 5);
    assertEquals(2, result.items().size());
    // 服务端固定上限，模型不能通过参数放大注入量。
    verify(rag).retrieveWithEvidence("ws-1", "排序", 5);
    var dropped = result.items().get(1);
    assertFalse(dropped.selected());
    assertEquals("BELOW_THRESHOLD", dropped.droppedReason());
  }

  @Test
  void modelFacingSummaryCarriesCountsAndNoPassageBodies() {
    var spec = KnowledgeSearchTool.specification(rag, "ws-1", "zh-CN");
    var summary = spec.summarize().apply(Map.of("query", "排序"), evidence());

    assertTrue(summary.contains("1"), summary);
    // 摘要里不能出现检索正文，否则证据等同于再注入一次。
    assertFalse(summary.contains("数组越界需要先校验长度。"), summary);
    assertFalse(summary.contains("bm25"), summary);
  }

  @Test
  void englishSummariesStayEnglish() {
    var spec = KnowledgeSearchTool.specification(rag, "ws-1", "en-US");
    var summary = spec.summarize().apply(Map.of("query", "sort"), evidence());
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
