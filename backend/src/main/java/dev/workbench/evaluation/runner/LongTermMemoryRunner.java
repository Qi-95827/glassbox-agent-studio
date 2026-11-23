package dev.workbench.evaluation.runner;

import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.MemorySource;
import dev.workbench.domain.MemoryStatus;
import dev.workbench.framework.evaluation.EvalCaseExecution;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalCategoryRunner;
import dev.workbench.framework.evaluation.EvalExperimentConfig;
import dev.workbench.framework.evaluation.EvalSample;
import dev.workbench.framework.memory.MemorySearchQuery;
import dev.workbench.framework.memory.MemoryType;
import dev.workbench.memory.MemoryRetrievalService;
import dev.workbench.persistence.entity.MemoryRecordEntity;
import dev.workbench.persistence.repository.MemoryRecordRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 类别 4：三层长期记忆与混合召回。
 *
 * <p>为每题隔离一个临时工作区命名空间，真实写入 ACTIVE 目标记忆、干扰记忆与 CANDIDATE 候选记忆，
 * 再调用 {@link MemoryRetrievalService} 计算 Recall@K / Precision@K / MRR 与候选零泄露。执行完立即删除本题目数据。
 */
@Component
public class LongTermMemoryRunner implements EvalCategoryRunner {
  private final MemoryRetrievalService retrieval;
  private final MemoryRecordRepository records;

  public LongTermMemoryRunner(
      MemoryRetrievalService retrieval, MemoryRecordRepository records) {
    this.retrieval = retrieval;
    this.records = records;
  }

  @Override
  public EvalCategory category() {
    return EvalCategory.LONG_TERM_MEMORY;
  }

  @Override
  public EvalCaseExecution run(
      EvalSample sample, EvalExperimentConfig config, String ownerScopeKey) {
    long started = System.nanoTime();
    var setup = sample.setup();
    String workspaceId = UUID.randomUUID().toString();
    var targets = memories(setup.get("targetMemories"));
    var distractors = memories(setup.get("distractorMemories"));
    var candidates = memories(setup.get("candidateMemories"));
    if (targets.isEmpty())
      throw new IllegalArgumentException("长期记忆评测题缺少 targetMemories。");
    int topK = Math.max(1, Math.min(5, (int) number(setup.get("topK"), 3)));
    String queryText = text(setup.get("queryText"), sample.prompt());
    var queryPaths = strings(setup.get("queryPaths"));
    if (queryPaths.isEmpty()) queryPaths = List.of("src/auth.js");

    var inserted = new ArrayList<String>();
    var targetIds = new ArrayList<String>();
    var candidateIds = new ArrayList<String>();
    try {
      for (var memory : targets) {
        String id = insert(workspaceId, memory, MemoryStatus.ACTIVE, null);
        inserted.add(id);
        targetIds.add(id);
      }
      for (var memory : distractors) inserted.add(insert(workspaceId, memory, MemoryStatus.ACTIVE, null));
      for (var memory : candidates) {
        String id = insert(workspaceId, memory, MemoryStatus.CANDIDATE, "eval-candidate-source");
        inserted.add(id);
        candidateIds.add(id);
      }

      var matches =
          retrieval.retrieve(workspaceId, new MemorySearchQuery(queryText, queryPaths, topK));
      var retrievedIds = matches.stream().map(match -> match.memoryId()).toList();
      long targetHits = retrievedIds.stream().filter(targetIds::contains).count();
      int candidateLeaks =
          (int) retrievedIds.stream().filter(candidateIds::contains).count();
      double recall = (double) targetHits / targetIds.size();
      double precision = retrievedIds.isEmpty() ? 0 : (double) targetHits / retrievedIds.size();
      int firstTargetRank = 0;
      for (int index = 0; index < retrievedIds.size(); index++) {
        if (targetIds.contains(retrievedIds.get(index))) {
          firstTargetRank = index + 1;
          break;
        }
      }
      double mrr = firstTargetRank == 0 ? 0 : 1.0 / firstTargetRank;
      var recalledContents = matches.stream().map(match -> match.content()).toList();

      var metrics = new LinkedHashMap<String, Object>();
      metrics.put("targetCount", targetIds.size());
      metrics.put("candidateCount", candidateIds.size());
      metrics.put("retrievedCount", retrievedIds.size());
      metrics.put("recallAtK", round(recall));
      metrics.put("precisionAtK", round(precision));
      metrics.put("mrr", round(mrr));
      metrics.put("candidateLeaked", candidateLeaks > 0);
      metrics.put("candidateLeakCount", candidateLeaks);
      metrics.put("topK", topK);

      var evidence = new LinkedHashMap<String, Object>(metrics);
      evidence.put("recalledContents", recalledContents);
      evidence.put("rawOutput", String.join("\n", recalledContents));

      var trace = new LinkedHashMap<String, Object>();
      trace.put("category", category().name());
      trace.put("workspaceId", workspaceId);
      trace.put("queryText", queryText);
      trace.put("queryPaths", queryPaths);
      trace.put("targets", targets.size());
      trace.put("distractors", distractors.size());
      trace.put("candidates", candidates.size());
      trace.put("matches", matches);

      long duration = (System.nanoTime() - started) / 1_000_000;
      return new EvalCaseExecution(
          evidence,
          metrics,
          trace,
          null,
          String.join("\n", recalledContents),
          duration,
          0,
          0,
          0);
    } finally {
      if (!inserted.isEmpty()) records.deleteAllById(inserted);
    }
  }

  private String insert(
      String workspaceId,
      Map<String, Object> memory,
      MemoryStatus status,
      String sourceRunId) {
    String content = text(memory.get("content"), "");
    if (content.isBlank()) throw new IllegalArgumentException("记忆内容不能为空。");
    if (content.length() > 300) content = content.substring(0, 299) + "…";
    MemoryType type = memoryType(memory.get("type"));
    String pathsJson = json(strings(memory.get("pathPatterns")));
    double confidence = number(memory.get("confidence"), 1.0);
    String now = Instant.now().toString();
    var row =
        new MemoryRecordEntity(
            dev.workbench.domain.WorkbenchIds.newId(),
            workspaceId,
            categoryOf(memory.get("category")),
            content,
            sourceRunId == null ? MemorySource.USER_CONFIRMED : MemorySource.RUN_EXTRACTION,
            status,
            1,
            type,
            sourceRunId,
            pathsJson,
            confidence,
            0,
            null,
            now,
            now);
    return records.saveAndFlush(row).getId();
  }

  private static String json(List<String> paths) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .writeValueAsString(paths == null ? List.of() : paths);
    } catch (Exception e) {
      return "[]";
    }
  }

  private static MemoryType memoryType(Object value) {
    try {
      return MemoryType.valueOf(text(value, "SEMANTIC").toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return MemoryType.SEMANTIC;
    }
  }

  private static MemoryCategory categoryOf(Object value) {
    try {
      return MemoryCategory.valueOf(text(value, "PROJECT_CONVENTION").toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return MemoryCategory.PROJECT_CONVENTION;
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> memories(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    var result = new ArrayList<Map<String, Object>>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        var row = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet())
          if (entry.getKey() != null) row.put(String.valueOf(entry.getKey()), entry.getValue());
        result.add(Map.copyOf(row));
      }
    }
    return List.copyOf(result);
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream()
        .filter(java.util.Objects::nonNull)
        .map(String::valueOf)
        .filter(text -> !text.isBlank())
        .toList();
  }

  private static double number(Object value, double fallback) {
    if (value instanceof Number number) return number.doubleValue();
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private static String text(Object value, String fallback) {
    return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
  }

  private static double round(double value) {
    return Math.round(value * 10_000.0) / 10_000.0;
  }
}
