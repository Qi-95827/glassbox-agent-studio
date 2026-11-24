package dev.workbench.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import dev.workbench.domain.MemorySource;
import dev.workbench.domain.MemoryStatus;
import dev.workbench.framework.memory.MemoryEmbeddingProvider;
import dev.workbench.framework.memory.MemorySearchQuery;
import dev.workbench.framework.memory.MemoryType;
import dev.workbench.persistence.entity.MemoryRecordEntity;
import dev.workbench.persistence.repository.MemoryRecordRepository;
import dev.workbench.persistence.view.MemoryRecordSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 记忆召回不再整库加载 {@code embedding_json}。
 *
 * <p>反例对应 `P-01`：此前 {@code findByWorkspaceIdAndStatus...} 会把每条 20KB 的向量读进堆并逐条 Jackson 反序列化，记忆量一多就
 * OOM。
 */
class MemoryRetrievalProjectionTest {
  private final MemoryRecordRepository records = mock(MemoryRecordRepository.class);

  private static MemoryRecordSummary summary(String id, String content) {
    var row = mock(MemoryRecordSummary.class);
    when(row.getId()).thenReturn(id);
    when(row.getContent()).thenReturn(content);
    when(row.getPathPatternsJson()).thenReturn("[]");
    when(row.getUpdatedAt()).thenReturn(java.time.Instant.now().toString());
    when(row.getConfidence()).thenReturn(0.9);
    when(row.getVersion()).thenReturn(1);
    when(row.getMemoryType()).thenReturn(MemoryType.SEMANTIC);
    when(row.getSource()).thenReturn(MemorySource.USER_CONFIRMED);
    return row;
  }

  @Test
  void retrievalUsesLightweightProjectionAndBoundedCandidates() {
    var summaries =
        List.of(
            summary("m-1", "排序函数需要处理空数组边界"),
            summary("m-2", "无关的记忆内容"),
            summary("m-3", "排序函数还应保持稳定性"));
    when(records.summarizeByWorkspaceAndStatus("ws-1", MemoryStatus.ACTIVE)).thenReturn(summaries);
    when(records.findAllById(any()))
        .thenAnswer(
            invocation -> {
              Iterable<String> ids = invocation.getArgument(0);
              var rows = new java.util.ArrayList<MemoryRecordEntity>();
              for (String id : ids) {
                var row =
                    new MemoryRecordEntity(
                        id,
                        "ws-1",
                        dev.workbench.domain.MemoryCategory.PROJECT_CONVENTION,
                        "排序函数" + id,
                        MemorySource.USER_CONFIRMED,
                        MemoryStatus.ACTIVE,
                        1,
                        java.time.Instant.now().toString(),
                        java.time.Instant.now().toString());
                rows.add(row);
              }
              return rows;
            });

    var service = new MemoryRetrievalService(records, List.<MemoryEmbeddingProvider>of());
    var matches = service.retrieve("ws-1", new MemorySearchQuery("排序函数", List.of(), 5));

    assertFalse(matches.isEmpty(), "词法命中的记忆必须返回");
    verify(records, never()).findByWorkspaceIdAndStatusOrderByUpdatedAtDescIdAsc(any(), any());
    verify(records, never()).findByWorkspaceIdOrderByUpdatedAtDescIdAsc(any());
    verify(records).summarizeByWorkspaceAndStatus("ws-1", MemoryStatus.ACTIVE);
  }

  @Test
  void emptyWorkspaceNeverTouchesCandidateQuery() {
    when(records.summarizeByWorkspaceAndStatus(eq("ws-empty"), eq(MemoryStatus.ACTIVE)))
        .thenReturn(List.of());

    var service = new MemoryRetrievalService(records, List.<MemoryEmbeddingProvider>of());
    assertTrue(service.retrieve("ws-empty", new MemorySearchQuery("任意", List.of(), 5)).isEmpty());
    verify(records, never()).findAllById(any());
  }
}
