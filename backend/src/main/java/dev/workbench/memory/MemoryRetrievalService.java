package dev.workbench.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.MemoryStatus;
import dev.workbench.framework.memory.MemoryEmbeddingProvider;
import dev.workbench.framework.memory.MemoryMatch;
import dev.workbench.framework.memory.MemorySearchQuery;
import dev.workbench.framework.memory.MemoryVectorIndex;
import dev.workbench.persistence.entity.MemoryRecordEntity;
import dev.workbench.persistence.repository.MemoryRecordRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 三层长期记忆混合召回（BM25 + 向量余弦 + RRF）。
 *
 * <p>先按当前工作区 ACTIVE 语料实时计算 BM25 与 IDF，再与向量余弦排名按 {@code 1 / (60 + rank)}
 * 做倒数排名融合，最后叠加路径重叠、时间衰减与置信度。冷启动硬门槛 {@code max(bm25, cosine) < 0.12} 直接剔除，杜绝“正文零相关却靠时间与置信度混进
 * Top-K”的污染。 只召回 ACTIVE 记忆，CANDIDATE 永不建索引、永不注入。
 */
@Service
public class MemoryRetrievalService {
  private static final double MIN_RELEVANCE = 0.12;
  private static final int VECTOR_CANDIDATES = 20;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final MemoryRecordRepository records;
  private final List<MemoryEmbeddingProvider> embeddings;
  private final List<MemoryVectorIndex> vectorIndexes;

  @Autowired
  public MemoryRetrievalService(
      MemoryRecordRepository records,
      List<MemoryEmbeddingProvider> embeddings,
      List<MemoryVectorIndex> vectorIndexes) {
    this.records = records;
    this.embeddings = embeddings == null ? List.of() : List.copyOf(embeddings);
    this.vectorIndexes = vectorIndexes == null ? List.of() : List.copyOf(vectorIndexes);
  }

  /** 单元测试与无向量端口的旧装配保留两参构造。 */
  public MemoryRetrievalService(
      MemoryRecordRepository records, List<MemoryEmbeddingProvider> embeddings) {
    this(records, embeddings, List.of());
  }

  /**
   * 召回入口：先算查询向量，再用轻量投影做 BM25 预筛，最后只对候选集回表计算真实余弦。
   *
   * <p>此前对整库加载 {@code embedding_json} 并逐条 Jackson 反序列化，记忆体量增大后会把堆打满。 现在：投影不含 embedding；候选集 = 词法
   * Top-N ∪ 向量下推 Top-N，余弦只对这批有限 id 计算， 其余行向量分记为 0，由 BM25 与结构化权重决定排序。
   */
  public List<MemoryMatch> retrieve(String workspaceId, MemorySearchQuery query) {
    var summaries = records.summarizeByWorkspaceAndStatus(workspaceId, MemoryStatus.ACTIVE);
    if (summaries.isEmpty()) return List.of();
    Instant now = Instant.now();
    var provider = provider();
    float[] queryVector = null;
    String modelName = null;
    if (provider != null && !query.text().isBlank()) {
      // 先完成远端 Embedding，再进入任何数据库查询，避免把 HTTP 期间占用的连接拖进长事务。
      queryVector = provider.embed(query.text());
      modelName = provider.modelName();
      if (modelName == null || modelName.isBlank()) modelName = "default";
      if (queryVector == null || queryVector.length == 0) queryVector = null;
    }
    var pushed =
        queryVector == null
            ? Map.<String, Double>of()
            : pushdown(workspaceId, modelName, queryVector);
    var candidateIds = candidateIds(summaries, pushed, query.text());
    Map<String, float[]> candidateEmbeddings =
        candidateIds.isEmpty()
            ? Map.of()
            : loadCandidateEmbeddings(workspaceId, candidateIds, provider, modelName, queryVector);
    var inputs = new ArrayList<HybridRanker.Input>(summaries.size());
    for (var summary : summaries) {
      double cosine = cosineFor(summary.getId(), pushed, candidateEmbeddings, queryVector);
      inputs.add(new HybridRanker.Input(summary.getId(), summary.getContent(), cosine));
    }
    var ranked = HybridRanker.rank(inputs, query.text());
    var matches = new ArrayList<MemoryMatch>();
    boolean vectorActive = queryVector != null;
    for (var summary : summaries) {
      var score = ranked.get(summary.getId());
      if (score == null) continue;
      // 冷启动硬门槛：词法或语义至少一侧真正相关，否则时间与置信度不得单独抬分。
      // 例外：用户显式确认且未限定路径的 SEMANTIC 项目约定属于“全局常驻背景”，始终允许注入；
      // 带路径限定的约定仍必须与当前授权文件相关。
      boolean curated =
          summary.getSource() == dev.workbench.domain.MemorySource.USER_CONFIRMED
              && summary.getMemoryType() == dev.workbench.framework.memory.MemoryType.SEMANTIC
              && !hasScopedPaths(summary.getPathPatternsJson());
      if (!curated && Math.max(score.bm25(), score.vector()) < MIN_RELEVANCE) continue;
      double path = pathOverlap(summary.getPathPatternsJson(), query.pathPatterns());
      double recency = recency(summary.getUpdatedAt(), now);
      double confidence = Math.max(0, Math.min(1, summary.getConfidence()));
      double semantic = vectorActive ? 0.40 * score.bm25() + 0.60 * score.vector() : score.bm25();
      double total =
          0.45 * score.rrf() + 0.30 * semantic + 0.10 * path + 0.10 * recency + 0.05 * confidence;
      matches.add(
          new MemoryMatch(
              summary.getId(),
              summary.getMemoryType(),
              total,
              String.format(
                  Locale.ROOT,
                  "bm25=%.2f, vector=%.2f, rrf=%.2f, path=%.2f, recency=%.2f, confidence=%.2f,"
                      + " lexical=%.2f",
                  score.bm25(),
                  score.vector(),
                  score.rrf(),
                  path,
                  recency,
                  confidence,
                  score.bm25()),
              summary.getContent(),
              summary.getVersion()));
    }
    return matches.stream()
        .sorted((left, right) -> Double.compare(right.totalScore(), left.totalScore()))
        .limit(Math.min(query.limit(), 5))
        .toList();
  }

  /** 候选集 = 词法 Top-N ∪ 向量下推命中；整体规模有上界，保证不会加载整库向量。 */
  private static java.util.Set<String> candidateIds(
      List<dev.workbench.persistence.view.MemoryRecordSummary> summaries,
      Map<String, Double> pushed,
      String queryText) {
    var lexical =
        HybridRanker.rank(
            summaries.stream()
                .map(summary -> new HybridRanker.Input(summary.getId(), summary.getContent(), 0))
                .toList(),
            queryText);
    var ids = new java.util.LinkedHashSet<String>(pushed.keySet());
    lexical.entrySet().stream()
        .sorted((left, right) -> Double.compare(right.getValue().bm25(), left.getValue().bm25()))
        .limit(VECTOR_CANDIDATES * 2L)
        .forEach(entry -> ids.add(entry.getKey()));
    return ids;
  }

  /**
   * 只回表候选集读取向量；缺失或模型不一致时顺带补齐，写入 pgvector 供后续下推。
   *
   * @return 候选 id 到向量的映射，解析失败的行不参与向量打分
   */
  private Map<String, float[]> loadCandidateEmbeddings(
      String workspaceId,
      java.util.Set<String> candidateIds,
      MemoryEmbeddingProvider provider,
      String modelName,
      float[] queryVector) {
    var rows = records.findAllById(candidateIds);
    if (rows.isEmpty()) return Map.of();
    if (provider != null && queryVector != null) backfill(workspaceId, rows, provider, modelName);
    var result = new HashMap<String, float[]>(rows.size());
    for (var row : rows) {
      var vector = parseEmbedding(row.getEmbeddingJson());
      // 没有向量时不要放入 null：Map.copyOf 不接受空值，且该行本来就不参与余弦。
      if (vector != null && vector.length > 0) result.put(row.getId(), vector);
    }
    return Map.copyOf(result);
  }

  private static double cosineFor(
      String id,
      Map<String, Double> pushed,
      Map<String, float[]> candidateEmbeddings,
      float[] queryVector) {
    if (pushed.containsKey(id)) return pushed.get(id);
    if (queryVector == null) return 0;
    return cosine(candidateEmbeddings.get(id), queryVector);
  }

  private MemoryEmbeddingProvider provider() {
    for (var provider : embeddings) {
      if (provider != null && provider.isAvailable()) return provider;
    }
    return null;
  }

  /** 懒建索引：只处理 ACTIVE 行；缺失或模型不一致时批量补齐并同时写入 pgvector。 */
  private void backfill(
      String workspaceId,
      List<MemoryRecordEntity> rows,
      MemoryEmbeddingProvider provider,
      String modelName) {
    var missing =
        rows.stream()
            .filter(
                row ->
                    row.getEmbeddingJson() == null
                        || row.getEmbeddingJson().isBlank()
                        || !modelName.equals(row.getEmbeddingModel()))
            .toList();
    if (missing.isEmpty()) return;
    var texts = missing.stream().map(MemoryRecordEntity::getContent).toList();
    var vectors = provider.embedAll(texts);
    if (vectors.size() != missing.size()) return;
    for (int index = 0; index < missing.size(); index++) {
      var vector = vectors.get(index);
      if (vector == null || vector.length == 0) continue;
      var row = missing.get(index);
      row.embed(encode(vector), modelName);
      for (var indexPort : vectorIndexes)
        if (indexPort != null && indexPort.available())
          indexPort.store(workspaceId, row.getId(), modelName, vector);
    }
    records.saveAll(missing);
  }

  private Map<String, Double> pushdown(String workspaceId, String modelName, float[] query) {
    for (var index : vectorIndexes) {
      if (index == null || !index.available()) continue;
      var hits = index.nearest(workspaceId, modelName, query, VECTOR_CANDIDATES);
      if (hits.isEmpty()) continue;
      var result = new HashMap<String, Double>();
      for (var hit : hits) result.put(hit.memoryId(), hit.cosine());
      return Map.copyOf(result);
    }
    return Map.of();
  }

  /** 路径限定为空（null / 空白 / "[]"）表示全局约定。 */
  static boolean hasScopedPaths(String storedJson) {
    if (storedJson == null || storedJson.isBlank()) return false;
    String compact = storedJson.replace(" ", "").strip();
    return !compact.isEmpty() && !"[]".equals(compact);
  }

  /** 路径重叠：Jaccard；任一侧为空时为 0。 */
  static double pathOverlap(String storedJson, List<String> queried) {
    if (storedJson == null || storedJson.isBlank() || queried == null || queried.isEmpty())
      return 0;
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

  static double cosine(float[] left, float[] right) {
    if (left == null || right == null || left.length != right.length || left.length == 0) return 0;
    double dot = 0;
    double leftNorm = 0;
    double rightNorm = 0;
    for (int index = 0; index < left.length; index++) {
      dot += left[index] * right[index];
      leftNorm += left[index] * left[index];
      rightNorm += right[index] * right[index];
    }
    if (leftNorm == 0 || rightNorm == 0) return 0;
    return Math.max(0, Math.min(1, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
  }

  static String encode(float[] vector) {
    try {
      return JSON.writeValueAsString(vector);
    } catch (Exception e) {
      return null;
    }
  }

  static float[] parseEmbedding(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      float[] values = JSON.readValue(raw, float[].class);
      return values == null || values.length == 0 ? null : values;
    } catch (Exception e) {
      return null;
    }
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
