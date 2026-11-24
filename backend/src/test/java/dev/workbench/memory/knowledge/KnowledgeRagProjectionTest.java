package dev.workbench.memory.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.workbench.framework.memory.KnowledgeVectorIndex;
import dev.workbench.framework.memory.MemoryEmbeddingProvider;
import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.entity.KnowledgeDocumentEntity;
import dev.workbench.persistence.repository.KnowledgeChunkRepository;
import dev.workbench.persistence.repository.KnowledgeDocumentRepository;
import dev.workbench.persistence.view.KnowledgeChunkSummary;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 知识库检索不再整库加载 {@code embedding_json}。
 *
 * <p>反例对应 `P-01`：此前把整库分块的 20KB 向量全部读进堆并逐条反序列化，工作区文档一多就 OOM。
 */
class KnowledgeRagProjectionTest {
  private final KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
  private final KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
  private final OwnershipService ownership = mock(OwnershipService.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);

  private KnowledgeRagService service(List<MemoryEmbeddingProvider> embeddings) {
    return new KnowledgeRagService(
        documents,
        chunks,
        embeddings,
        List.<KnowledgeVectorIndex>of(),
        ownership,
        transactionManager);
  }

  private static KnowledgeChunkSummary summary(String id, String content) {
    var row = mock(KnowledgeChunkSummary.class);
    when(row.getId()).thenReturn(id);
    when(row.getDocumentId()).thenReturn("doc-1");
    when(row.getSectionTitle()).thenReturn("排序");
    when(row.getContent()).thenReturn(content);
    when(row.getTokenEstimate()).thenReturn(10);
    return row;
  }

  @Test
  void retrievalUsesProjectionAndNeverLoadsEveryChunk() {
    var summaries =
        List.of(summary("c-1", "排序算法需要处理空数组"), summary("c-2", "无关章节"), summary("c-3", "排序稳定性说明"));
    when(chunks.summarizeByWorkspace("ws-1")).thenReturn(summaries);
    var document = mock(KnowledgeDocumentEntity.class);
    when(document.getId()).thenReturn("doc-1");
    when(document.getTitle()).thenReturn("排序设计");
    when(document.getSourcePath()).thenReturn("docs/sort.md");
    when(documents.findByWorkspaceIdOrderByUpdatedAtDescIdAsc("ws-1"))
        .thenReturn(List.of(document));

    var hits = service(List.<MemoryEmbeddingProvider>of()).retrieve("ws-1", "排序", 5);

    assertFalse(hits.isEmpty(), "词法命中的分块必须返回");
    assertEquals("排序设计", hits.getFirst().title());
    verify(chunks, never()).findByWorkspaceIdOrderByDocumentIdAscChunkIndexAsc(any());
    verify(chunks).summarizeByWorkspace("ws-1");
  }

  @Test
  void blankQueryOrEmptyWorkspaceSkipsAllWork() {
    assertTrue(service(List.<MemoryEmbeddingProvider>of()).retrieve("ws-1", "  ", 5).isEmpty());
    when(chunks.summarizeByWorkspace("ws-empty")).thenReturn(List.of());
    assertTrue(service(List.<MemoryEmbeddingProvider>of()).retrieve("ws-empty", "排序", 5).isEmpty());

    verify(chunks, never()).findAllById(any());
  }
}
