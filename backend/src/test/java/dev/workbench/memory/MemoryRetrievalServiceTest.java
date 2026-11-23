package dev.workbench.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.MemorySource;
import dev.workbench.domain.MemoryStatus;
import dev.workbench.framework.memory.MemorySearchQuery;
import dev.workbench.framework.memory.MemoryType;
import dev.workbench.persistence.entity.MemoryRecordEntity;
import dev.workbench.persistence.repository.MemoryRecordRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 混合评分：词法优先、路径加分、低分不注入、Top-K 限制。 */
class MemoryRetrievalServiceTest {
  @Test
  void ranksLexicalAndPathOverlapAboveUnrelatedMemory() {
    var repository = mock(MemoryRecordRepository.class);
    var hit =
        row(
            "mem-hit",
            "排序页面应使用原生 JavaScript，并保留 sort.js 的单步状态。",
            "[\"sort.js\"]",
            0.95);
    var unrelated =
        row("mem-other", "部署环境使用蓝绿发布。", "[\"deploy.yml\"]", 0.95);
    when(repository.findByWorkspaceIdAndStatusOrderByUpdatedAtDescIdAsc(
            "ws", MemoryStatus.ACTIVE))
        .thenReturn(List.of(hit, unrelated));
    var service = new MemoryRetrievalService(repository, List.of());
    var matches =
        service.retrieve(
            "ws",
            new MemorySearchQuery("原生 JavaScript 排序 sort.js", List.of("sort.js"), 5));
    assertEquals(1, matches.size());
    assertEquals("mem-hit", matches.getFirst().memoryId());
    assertTrue(matches.getFirst().totalScore() > 0.7);
    assertTrue(matches.getFirst().matchedReason().contains("lexical="));
    assertTrue(matches.getFirst().matchedReason().contains("path="));
  }

  @Test
  void embeddingSpiCanImproveLexicalMatchWithoutChangingWeightsShape() {
    var repository = mock(MemoryRecordRepository.class);
    var row = row("mem-embed", "前端状态机与单步演示", "[]", 0.8);
    when(repository.findByWorkspaceIdAndStatusOrderByUpdatedAtDescIdAsc(
            "ws", MemoryStatus.ACTIVE))
        .thenReturn(List.of(row));
    var provider =
        new dev.workbench.framework.memory.MemoryEmbeddingProvider() {
          @Override
          public boolean isAvailable() {
            return true;
          }

          @Override
          public float[] embed(String text) {
            return text == null ? new float[0] : new float[] {1, text.length()};
          }
        };
    var service = new MemoryRetrievalService(repository, List.of(provider));
    var matches = service.retrieve("ws", new MemorySearchQuery("前端单步", List.of(), 5));
    assertEquals(1, matches.size());
    assertTrue(matches.getFirst().totalScore() > 0);
  }

  @Test
  void unrelatedMemoryIsDroppedByColdStartRelevanceGate() {
    var repository = mock(MemoryRecordRepository.class);
    var unrelated =
        agentRow("mem-unrelated", "部署环境使用蓝绿发布与只读副本。", "[]", 1.0);
    when(repository.findByWorkspaceIdAndStatusOrderByUpdatedAtDescIdAsc(
            "ws", MemoryStatus.ACTIVE))
        .thenReturn(List.of(unrelated));
    var service = new MemoryRetrievalService(repository, List.of());
    var matches = service.retrieve("ws", new MemorySearchQuery("前端单步状态机", List.of(), 5));
    // 时间新、置信度高也不能单独越过 0.12 相关性硬门槛。
    assertTrue(matches.isEmpty());
  }

  @Test
  void vectorIndexPushdownFeedsRrfBreakdown() {
    var repository = mock(MemoryRecordRepository.class);
    var row = row("mem-vector", "前端状态机与单步演示", "[]", 0.9);
    when(repository.findByWorkspaceIdAndStatusOrderByUpdatedAtDescIdAsc(
            "ws", MemoryStatus.ACTIVE))
        .thenReturn(List.of(row));
    var provider =
        new dev.workbench.framework.memory.MemoryEmbeddingProvider() {
          @Override
          public boolean isAvailable() {
            return true;
          }

          @Override
          public String modelName() {
            return "test-embed";
          }

          @Override
          public float[] embed(String text) {
            return new float[] {1, 1};
          }
        };
    var index =
        new dev.workbench.framework.memory.MemoryVectorIndex() {
          @Override
          public boolean available() {
            return true;
          }

          @Override
          public void store(
              String workspaceId, String memoryId, String modelName, float[] embedding) {}

          @Override
          public List<VectorHit> nearest(
              String workspaceId, String modelName, float[] query, int limit) {
            return List.of(new VectorHit("mem-vector", 0.95));
          }
        };
    var service = new MemoryRetrievalService(repository, List.of(provider), List.of(index));
    var matches = service.retrieve("ws", new MemorySearchQuery("前端单步", List.of(), 5));
    assertEquals(1, matches.size());
    assertTrue(matches.getFirst().matchedReason().contains("vector=0.95"));
    assertTrue(matches.getFirst().matchedReason().contains("rrf="));
    assertTrue(matches.getFirst().totalScore() > 0.7);
  }

  private static MemoryRecordEntity agentRow(
      String id, String content, String paths, double confidence) {
    String now = Instant.now().toString();
    return new MemoryRecordEntity(
        id,
        "ws",
        MemoryCategory.PROJECT_CONVENTION,
        content,
        MemorySource.RUN_EXTRACTION,
        MemoryStatus.ACTIVE,
        1,
        MemoryType.EPISODIC,
        "run-source",
        paths,
        confidence,
        0,
        null,
        now,
        now);
  }

  private static MemoryRecordEntity row(
      String id, String content, String paths, double confidence) {
    String now = Instant.now().toString();
    return new MemoryRecordEntity(
        id,
        "ws",
        MemoryCategory.PROJECT_CONVENTION,
        content,
        MemorySource.USER_CONFIRMED,
        MemoryStatus.ACTIVE,
        1,
        MemoryType.SEMANTIC,
        null,
        paths,
        confidence,
        0,
        null,
        now,
        now);
  }
}
