package dev.workbench.memory;

import dev.workbench.domain.MemoryStatus;
import dev.workbench.framework.memory.MemoryEmbeddingProvider;
import dev.workbench.framework.memory.MemoryMatch;
import dev.workbench.framework.memory.MemorySearchQuery;
import dev.workbench.persistence.entity.MemoryRecordEntity;
import dev.workbench.persistence.repository.MemoryRecordRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 三层长期记忆混合召回。
 *
 * <p>综合得分 = 0.45·Lexical + 0.30·PathOverlap + 0.15·RecencyDecay + 0.10·Confidence；
 * 若注入 Embedding SPI，则把词法分量的一部分平滑替换为余弦相似度。只召回 ACTIVE 记忆，
 * 低于 0.25 的候选不注入。
 */
@Service
public class MemoryRetrievalService {
  private static final double MIN_SCORE = 0.25;

  private final MemoryRecordRepository records;
  private final List<MemoryEmbeddingProvider> embeddings;

  public MemoryRetrievalService(
      MemoryRecordRepository records, List<MemoryEmbeddingProvider> embeddings) {
    this.records = records;
    this.embeddings = embeddings == null ? List.of() : List.copyOf(embeddings);
  }

  public List<MemoryMatch> retrieve(String workspaceId, MemorySearchQuery query) {
    var matches = new ArrayList<MemoryMatch>();
    Instant now = Instant.now();
    for (var row : records.findByWorkspaceIdAndStatusOrderByUpdatedAtDescIdAsc(
        workspaceId, MemoryStatus.ACTIVE)) {
      double lexical = lexical(row.getContent(), query.text());
      double cosine = cosine(row.getContent(), query.text());
      if (cosine > 0) lexical = 0.80 * lexical + 0.20 * cosine;
      double path = pathOverlap(row.getPathPatternsJson(), query.pathPatterns());
      double recency = recency(row.getUpdatedAt(), now);
      double confidence = Math.max(0, Math.min(1, row.getConfidence()));
      double total = 0.45 * lexical + 0.30 * path + 0.15 * recency + 0.10 * confidence;
      if (total < MIN_SCORE) continue;
      matches.add(
          new MemoryMatch(
              row.getId(),
              row.getMemoryType(),
              total,
              String.format(
                  Locale.ROOT,
                  "lexical=%.2f, path=%.2f, recency=%.2f, confidence=%.2f",
                  lexical,
                  path,
                  recency,
                  confidence),
              row.getContent(),
              row.getVersion()));
    }
    return matches.stream()
        .sorted((left, right) -> Double.compare(right.totalScore(), left.totalScore()))
        .limit(Math.min(query.limit(), 5))
        .toList();
  }

  /** 词法：完全包含得 1；否则按查询词命中的比例计分。 */
  static double lexical(String content, String query) {
    String text = normalize(content);
    String needle = normalize(query);
    if (needle.isBlank()) return 0;
    if (text.contains(needle)) return 1;
    var tokens = tokens(needle);
    if (tokens.isEmpty()) return 0;
    int hit = 0;
    for (String token : tokens) if (text.contains(token)) hit++;
    return Math.min(1, (double) hit / tokens.size());
  }

  /** 路径重叠：Jaccard；任一侧为空时为 0。 */
  static double pathOverlap(String storedJson, List<String> queried) {
    if (storedJson == null || storedJson.isBlank() || queried == null || queried.isEmpty()) return 0;
    var stored = parsePaths(storedJson);
    if (stored.isEmpty()) return 0;
    var left = new LinkedHashSet<>(stored);
    var right = new LinkedHashSet<>(queried);
    var union = new LinkedHashSet<>(left);
    union.addAll(right);
    left.retainAll(right);
    return union.isEmpty() ? 0 : (double) left.size() / union.size();
  }

  static double recency(String updatedAt, Instant now) {
    try {
      long days = Math.max(0, Duration.between(Instant.parse(updatedAt), now).toDays());
      return 1.0 / (1.0 + days / 30.0);
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private double cosine(String content, String query) {
    if (query.isBlank()) return 0;
    for (var provider : embeddings) {
      if (provider == null || !provider.isAvailable()) continue;
      try {
        var left = provider.embed(content);
        var right = provider.embed(query);
        if (left == null || right == null || left.length != right.length || left.length == 0)
          continue;
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
          dot += left[index] * right[index];
          leftNorm += left[index] * left[index];
          rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) continue;
        return Math.max(0, Math.min(1, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
      } catch (RuntimeException ignored) {
        // 单个 SPI 失败不影响零依赖混合评分。
      }
    }
    return 0;
  }

  private static String normalize(String text) {
    return text == null
        ? ""
        : java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .strip();
  }

  private static Set<String> tokens(String text) {
    var result = new LinkedHashSet<String>();
    for (String token : text.split("[^\\p{L}\\p{N}]+"))
      if (!token.isBlank() && token.length() >= 2) result.add(token);
    return result;
  }

  private static List<String> parsePaths(String raw) {
    try {
      return java.util.Arrays.asList(
          new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, String[].class));
    } catch (Exception e) {
      return List.of();
    }
  }
}
