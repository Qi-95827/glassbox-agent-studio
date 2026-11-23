package dev.workbench.memory.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.WorkbenchIds;
import dev.workbench.domain.WorkbenchTime;
import dev.workbench.framework.memory.KnowledgeVectorIndex;
import dev.workbench.framework.memory.MemoryEmbeddingProvider;
import dev.workbench.memory.HybridRanker;
import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.entity.KnowledgeChunkEntity;
import dev.workbench.persistence.entity.KnowledgeDocumentEntity;
import dev.workbench.persistence.repository.KnowledgeChunkRepository;
import dev.workbench.persistence.repository.KnowledgeDocumentRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作区知识库 RAG。
 *
 * <p>文档按 SHA-256 去重；未变化不重复调用 Embedding。检索复用长期记忆同一套 BM25 + 向量余弦 +
 * RRF 融合，并强制携带 {@code workspace_id}。未配置 Embedding 时自动降级为 BM25，不阻塞索引与问答。
 */
@Service
public class KnowledgeRagService {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final double MIN_RELEVANCE = 0.10;

  private final KnowledgeDocumentRepository documents;
  private final KnowledgeChunkRepository chunks;
  private final List<MemoryEmbeddingProvider> embeddings;
  private final List<KnowledgeVectorIndex> vectorIndexes;
  private final OwnershipService ownership;
  private final DocumentChunker chunker = new DocumentChunker();

  public KnowledgeRagService(
      KnowledgeDocumentRepository documents,
      KnowledgeChunkRepository chunks,
      List<MemoryEmbeddingProvider> embeddings,
      List<KnowledgeVectorIndex> vectorIndexes,
      OwnershipService ownership) {
    this.documents = documents;
    this.chunks = chunks;
    this.embeddings = embeddings == null ? List.of() : List.copyOf(embeddings);
    this.vectorIndexes = vectorIndexes == null ? List.of() : List.copyOf(vectorIndexes);
    this.ownership = ownership;
  }

  public record IndexRequest(String title, String sourcePath, String content, String sourceType) {}

  public record DocumentView(
      String id,
      String title,
      String sourcePath,
      String sourceType,
      int chunkCount,
      String status,
      String sha256,
      String updatedAt) {}

  public record IndexResult(DocumentView document, boolean reindexed) {}

  public record RagHit(
      String chunkId,
      String documentId,
      String title,
      String sourcePath,
      String sectionTitle,
      String content,
      int tokenEstimate,
      double score,
      String breakdown) {}

  @Transactional
  public IndexResult index(String workspaceId, IndexRequest request) {
    if (request == null) throw new IllegalArgumentException("知识库文档不能为空。");
    String title = text(request.title(), "未命名文档");
    String content = request.content() == null ? "" : request.content();
    if (content.isBlank()) throw new IllegalArgumentException("知识库文档正文不能为空。");
    if (content.length() > 2_000_000) throw new IllegalArgumentException("知识库文档超过 2MB 上限。");
    String sourcePath = text(request.sourcePath(), title);
    String sourceType = text(request.sourceType(), "DOCUMENT");
    String sha = sha256(content);
    var existing =
        documents.findFirstByWorkspaceIdAndSourcePathAndContentSha256(
            workspaceId, sourcePath, sha);
    if (existing.isPresent()) return new IndexResult(view(existing.get()), false);

    // 文件已变化：先清掉同路径旧分块与旧文档，保证检索不会命中过期正文。
    for (var stale : documents.findByWorkspaceIdAndSourcePathOrderByUpdatedAtDescIdAsc(
        workspaceId, sourcePath)) {
      chunks.deleteByDocumentId(stale.getId());
      documents.delete(stale);
    }
    chunks.flush();

    var blocks = chunker.chunk(content);
    if (blocks.isEmpty()) throw new IllegalArgumentException("知识库文档没有可索引正文。");
    String now = WorkbenchTime.now();
    String documentId = WorkbenchIds.newId();
    var document =
        new KnowledgeDocumentEntity(
            documentId,
            workspaceId,
            ownership.scope().ownerScopeKey(),
            sourceType,
            sourcePath,
            title,
            sha,
            blocks.size(),
            "INDEXED",
            now,
            now);
    documents.saveAndFlush(document);
    var provider = provider();
    var vectors = embed(provider, blocks.stream().map(DocumentChunker.Chunk::content).toList());
    String model = provider == null ? null : modelName(provider);
    var rows = new ArrayList<KnowledgeChunkEntity>(blocks.size());
    for (int index = 0; index < blocks.size(); index++) {
      var block = blocks.get(index);
      var vector = vector(vectors, index);
      var row =
          new KnowledgeChunkEntity(
              WorkbenchIds.newId(),
              documentId,
              workspaceId,
              block.index(),
              block.sectionTitle(),
              block.content(),
              block.tokenEstimate(),
              encode(vector),
              vector == null ? null : model,
              now);
      rows.add(row);
    }
    chunks.saveAllAndFlush(rows);
    if (!rows.isEmpty()) storeVectors(workspaceId, model, rows, vectors);
    return new IndexResult(view(document), true);
  }

  @Transactional(readOnly = true)
  public List<DocumentView> list(String workspaceId) {
    return documents.findByWorkspaceIdOrderByUpdatedAtDescIdAsc(workspaceId).stream()
        .map(KnowledgeRagService::view)
        .toList();
  }

  @Transactional
  public void delete(String workspaceId, String documentId) {
    var document =
        documents
            .findByIdAndWorkspaceId(documentId, workspaceId)
            .orElseThrow(() -> new IllegalArgumentException("知识库文档不存在或不属于当前工作区。"));
    chunks.deleteByDocumentId(document.getId());
    documents.delete(document);
  }

  @Transactional(readOnly = true)
  public List<RagHit> retrieve(String workspaceId, String query, int limit) {
    if (query == null || query.isBlank()) return List.of();
    var rows = chunks.findByWorkspaceIdOrderByDocumentIdAscChunkIndexAsc(workspaceId);
    if (rows.isEmpty()) return List.of();
    var provider = provider();
    float[] queryVector = provider == null ? null : embedOne(provider, query);
    if (queryVector != null && queryVector.length == 0) queryVector = null;
    var pushed =
        queryVector == null
            ? Map.<String, Double>of()
            : pushdown(workspaceId, modelName(provider), queryVector);
    var inputs = new ArrayList<HybridRanker.Input>(rows.size());
    for (var row : rows) {
      double cosine =
          pushed.containsKey(row.getId())
              ? pushed.get(row.getId())
              : cosine(parse(row.getEmbeddingJson()), queryVector);
      inputs.add(new HybridRanker.Input(row.getId(), row.getContent(), cosine));
    }
    var ranked = HybridRanker.rank(inputs, query);
    var documentTitles = new HashMap<String, KnowledgeDocumentEntity>();
    for (var document : documents.findByWorkspaceIdOrderByUpdatedAtDescIdAsc(workspaceId))
      documentTitles.put(document.getId(), document);
    var hits = new ArrayList<RagHit>();
    for (var row : rows) {
      var score = ranked.get(row.getId());
      if (score == null || Math.max(score.bm25(), score.vector()) < MIN_RELEVANCE) continue;
      var document = documentTitles.get(row.getDocumentId());
      double semantic =
          queryVector == null ? score.bm25() : 0.40 * score.bm25() + 0.60 * score.vector();
      double total = 0.60 * score.rrf() + 0.40 * semantic;
      hits.add(
          new RagHit(
              row.getId(),
              row.getDocumentId(),
              document == null ? "" : document.getTitle(),
              document == null ? "" : document.getSourcePath(),
              row.getSectionTitle() == null ? "" : row.getSectionTitle(),
              row.getContent(),
              row.getTokenEstimate(),
              Math.round(total * 10_000.0) / 10_000.0,
              String.format(
                  java.util.Locale.ROOT,
                  "bm25=%.2f, vector=%.2f, rrf=%.2f",
                  score.bm25(),
                  score.vector(),
                  score.rrf())));
    }
    return hits.stream()
        .sorted((left, right) -> Double.compare(right.score(), left.score()))
        .limit(Math.max(1, Math.min(50, limit)))
        .toList();
  }

  private MemoryEmbeddingProvider provider() {
    for (var provider : embeddings) if (provider != null && provider.isAvailable()) return provider;
    return null;
  }

  private static String modelName(MemoryEmbeddingProvider provider) {
    String name = provider.modelName();
    return name == null || name.isBlank() ? "default" : name;
  }

  private static List<float[]> embed(MemoryEmbeddingProvider provider, List<String> texts) {
    if (provider == null) return List.of();
    var vectors = provider.embedAll(texts);
    return vectors.size() == texts.size() ? vectors : List.of();
  }

  private static float[] embedOne(MemoryEmbeddingProvider provider, String query) {
    try {
      return provider.embed(query);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static float[] vector(List<float[]> vectors, int index) {
    return index < vectors.size() ? vectors.get(index) : null;
  }

  private void storeVectors(
      String workspaceId,
      String model,
      List<KnowledgeChunkEntity> rows,
      List<float[]> vectors) {
    if (model == null) return;
    for (var index : vectorIndexes) {
      if (index == null || !index.available()) continue;
      for (int position = 0; position < rows.size() && position < vectors.size(); position++) {
        var vector = vectors.get(position);
        if (vector == null || vector.length == 0) continue;
        index.store(workspaceId, rows.get(position).getId(), model, vector);
      }
      return;
    }
  }

  private Map<String, Double> pushdown(String workspaceId, String model, float[] query) {
    for (var index : vectorIndexes) {
      if (index == null || !index.available()) continue;
      var hits = index.nearest(workspaceId, model, query, 50);
      if (hits.isEmpty()) continue;
      var result = new HashMap<String, Double>();
      for (var hit : hits) result.put(hit.chunkId(), hit.cosine());
      return Map.copyOf(result);
    }
    return Map.of();
  }

  private static DocumentView view(KnowledgeDocumentEntity document) {
    return new DocumentView(
        document.getId(),
        document.getTitle(),
        document.getSourcePath(),
        document.getSourceType(),
        document.getChunkCount(),
        document.getStatus(),
        document.getContentSha256(),
        document.getUpdatedAt());
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
    if (vector == null) return null;
    try {
      return JSON.writeValueAsString(vector);
    } catch (Exception e) {
      return null;
    }
  }

  static float[] parse(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      float[] values = JSON.readValue(raw, float[].class);
      return values == null || values.length == 0 ? null : values;
    } catch (Exception e) {
      return null;
    }
  }

  private static String sha256(String content) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("计算知识库文档 SHA-256 失败。", e);
    }
  }

  private static String text(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.strip();
  }
}
