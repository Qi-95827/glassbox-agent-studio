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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 工作区知识库 RAG。
 *
 * <p>文档按 SHA-256 去重；未变化不重复调用 Embedding。检索复用长期记忆同一套 BM25 + 向量余弦 + RRF 融合，并强制携带 {@code
 * workspace_id}。未配置 Embedding 时自动降级为 BM25，不阻塞索引与问答。
 */
@Service
public class KnowledgeRagService {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final double MIN_RELEVANCE = 0.10;

  /** 词法预筛候选上限：只有这批分块需要回表读取 embedding 并参与向量重排。 */
  private static final int CANDIDATE_LIMIT = 80;

  private final KnowledgeDocumentRepository documents;
  private final KnowledgeChunkRepository chunks;
  private final List<MemoryEmbeddingProvider> embeddings;
  private final List<KnowledgeVectorIndex> vectorIndexes;
  private final OwnershipService ownership;
  private final TransactionTemplate transactionTemplate;
  private final DocumentChunker chunker = new DocumentChunker();

  public KnowledgeRagService(
      KnowledgeDocumentRepository documents,
      KnowledgeChunkRepository chunks,
      List<MemoryEmbeddingProvider> embeddings,
      List<KnowledgeVectorIndex> vectorIndexes,
      OwnershipService ownership,
      org.springframework.transaction.PlatformTransactionManager transactionManager) {
    this.documents = documents;
    this.chunks = chunks;
    this.embeddings = embeddings == null ? List.of() : List.copyOf(embeddings);
    this.vectorIndexes = vectorIndexes == null ? List.of() : List.copyOf(vectorIndexes);
    this.ownership = ownership;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
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

  /**
   * 索引一篇文档。
   *
   * <p>顺序被刻意拆成三段：先做只读去重判断，再在**事务外**完成远端 Embedding（网络可能耗时数秒， 不能占着数据库连接与事务），最后用短事务写入文档、分块与向量列。这样既不会把
   * HTTP 拖进长事务， 也保证「旧分块清理 + 新分块写入」仍在同一事务内原子完成。
   */
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
        documents.findFirstByWorkspaceIdAndSourcePathAndContentSha256(workspaceId, sourcePath, sha);
    if (existing.isPresent()) return new IndexResult(view(existing.get()), false);

    var blocks = chunker.chunk(content);
    if (blocks.isEmpty()) throw new IllegalArgumentException("知识库文档没有可索引正文。");
    var provider = provider();
    var vectors = embed(provider, blocks.stream().map(DocumentChunker.Chunk::content).toList());
    String model = provider == null ? null : modelName(provider);
    String ownerScopeKey = ownership.scope().ownerScopeKey();
    return transactionTemplate.execute(
        status -> {
          // 文件已变化：先清掉同路径旧分块与旧文档，保证检索不会命中过期正文。
          for (var stale :
              documents.findByWorkspaceIdAndSourcePathOrderByUpdatedAtDescIdAsc(
                  workspaceId, sourcePath)) {
            chunks.deleteByDocumentId(stale.getId());
            documents.delete(stale);
          }
          chunks.flush();
          String now = WorkbenchTime.now();
          String documentId = WorkbenchIds.newId();
          var document =
              new KnowledgeDocumentEntity(
                  documentId,
                  workspaceId,
                  ownerScopeKey,
                  sourceType,
                  sourcePath,
                  title,
                  sha,
                  blocks.size(),
                  "INDEXED",
                  now,
                  now);
          documents.saveAndFlush(document);
          var rows = new ArrayList<KnowledgeChunkEntity>(blocks.size());
          for (int index = 0; index < blocks.size(); index++) {
            var block = blocks.get(index);
            var vector = vector(vectors, index);
            rows.add(
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
                    now));
          }
          chunks.saveAllAndFlush(rows);
          if (!rows.isEmpty()) storeVectors(workspaceId, model, rows, vectors);
          return new IndexResult(view(document), true);
        });
  }

  @Transactional(readOnly = true)
  public List<DocumentView> list(String workspaceId) {
    return documents.findByWorkspaceIdOrderByUpdatedAtDescIdAsc(workspaceId).stream()
        .map(KnowledgeRagService::view)
        .toList();
  }

  /** 分块预览窗口：只回传前若干字符，避免把整篇分块正文推到浏览器。 */
  private static final int CHUNK_PREVIEW_CHARS = 400;

  /**
   * 单篇文档的分块边界预览。
   *
   * @param index 分块在文档内的顺序号。
   * @param charCount 该分块原始字符数；与 {@code preview} 长度不同即表示被预览截断。
   */
  public record ChunkView(
      int index,
      String sectionTitle,
      int tokenEstimate,
      int charCount,
      String preview,
      boolean previewTruncated) {}

  /** 工作区切片透视：读取某篇知识库文档被切出的分块边界与预览。 */
  @Transactional(readOnly = true)
  public List<ChunkView> chunks(String workspaceId, String documentId) {
    documents
        .findByIdAndWorkspaceId(documentId, workspaceId)
        .orElseThrow(() -> new IllegalArgumentException("知识库文档不存在或不属于当前工作区。"));
    return chunks.findByDocumentIdOrderByChunkIndexAsc(documentId).stream()
        .map(
            row -> {
              String content = row.getContent() == null ? "" : row.getContent();
              boolean truncated = content.length() > CHUNK_PREVIEW_CHARS;
              return new ChunkView(
                  row.getChunkIndex(),
                  row.getSectionTitle() == null ? "" : row.getSectionTitle(),
                  row.getTokenEstimate(),
                  content.length(),
                  truncated ? content.substring(0, CHUNK_PREVIEW_CHARS) : content,
                  truncated);
            })
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

  /**
   * 检索入口：先算查询向量再查库，且不再整库加载 {@code embedding_json}。
   *
   * <p>此前把整库分块的 20KB 向量全部读进堆并逐条 Jackson 反序列化，工作区一大就 OOM。现在：
   *
   * <ul>
   *   <li>投影只取编号、正文与标题，BM25 仍在全量语料上计算，保证词法相关性不降级；
   *   <li>向量分只对「词法 Top-N ∪ 向量下推 Top-N」这有限个候选按需回表计算，其余记为 0；
   *   <li>PostgreSQL 下若 pgvector 下推可用，则完全不需要回表读取 embedding。
   * </ul>
   */
  public List<RagHit> retrieve(String workspaceId, String query, int limit) {
    var evidence = retrieveWithEvidence(workspaceId, query, limit);
    var hits = new ArrayList<RagHit>();
    for (var item : evidence.items()) {
      if (!item.selected()) continue;
      hits.add(
          new RagHit(
              item.chunkId(),
              item.documentId(),
              item.title(),
              item.sourcePath(),
              item.sectionTitle(),
              item.content(),
              item.tokenEstimate(),
              item.score(),
              String.format(
                  java.util.Locale.ROOT,
                  "bm25=%.2f, vector=%.2f, rrf=%.2f",
                  item.bm25(),
                  item.vector(),
                  item.rrf())));
    }
    return List.copyOf(hits);
  }

  /** 透明召回证据：单次检索的每个候选及其 BM25 / 向量 / RRF 得分与入选/裁剪原因。 */
  public record RetrievalEvidence(
      String query,
      boolean vectorEnabled,
      List<EvidenceItem> items,
      int selectedCount,
      int injectedTokens) {}

  /**
   * 单个候选分块的召回证据。
   *
   * @param selected 是否最终注入 Prompt。
   * @param droppedReason 未入选原因：{@code BELOW_THRESHOLD} / {@code OVER_LIMIT}；入选时为空串。
   */
  public record EvidenceItem(
      String chunkId,
      String documentId,
      String title,
      String sourcePath,
      String sectionTitle,
      String content,
      int tokenEstimate,
      double bm25,
      double vector,
      double rrf,
      double score,
      boolean selected,
      String droppedReason) {}

  /**
   * 带证据的混合检索。
   *
   * <p>与 {@link #retrieve(String, String, int)} 共用同一套打分与阈值：入选集合、排序与截断
   * 完全一致，额外只多保留了「没进来的那些以及为什么没进来」。前端因此可以画真实漏斗，
   * 而不是根据最终结果反推过程。
   */
  public RetrievalEvidence retrieveWithEvidence(String workspaceId, String query, int limit) {
    if (query == null || query.isBlank()) return new RetrievalEvidence("", false, List.of(), 0, 0);
    var summaries = chunks.summarizeByWorkspace(workspaceId);
    if (summaries.isEmpty()) return new RetrievalEvidence(query, false, List.of(), 0, 0);
    var provider = provider();
    float[] queryVector = provider == null ? null : embedOne(provider, query);
    if (queryVector != null && queryVector.length == 0) queryVector = null;
    var pushed =
        queryVector == null
            ? Map.<String, Double>of()
            : pushdown(workspaceId, modelName(provider), queryVector);
    var candidateIds = candidateIds(summaries, pushed, query);
    Map<String, float[]> candidateEmbeddings =
        candidateIds.isEmpty() ? Map.of() : loadCandidateEmbeddings(candidateIds, queryVector);
    var inputs = new ArrayList<HybridRanker.Input>(summaries.size());
    for (var summary : summaries) {
      double cosine = cosineFor(summary.getId(), pushed, candidateEmbeddings, queryVector);
      inputs.add(new HybridRanker.Input(summary.getId(), summary.getContent(), cosine));
    }
    var ranked = HybridRanker.rank(inputs, query);
    var documentTitles = new HashMap<String, KnowledgeDocumentEntity>();
    for (var document : documents.findByWorkspaceIdOrderByUpdatedAtDescIdAsc(workspaceId))
      documentTitles.put(document.getId(), document);

    int cap = Math.max(1, Math.min(50, limit));
    var scored = new ArrayList<ScoredChunk>();
    for (var summary : summaries) {
      var score = ranked.get(summary.getId());
      if (score == null) continue;
      var document = documentTitles.get(summary.getDocumentId());
      double semantic =
          queryVector == null ? score.bm25() : 0.40 * score.bm25() + 0.60 * score.vector();
      double total = 0.60 * score.rrf() + 0.40 * semantic;
      scored.add(
          new ScoredChunk(
              summary,
              document,
              score.bm25(),
              score.vector(),
              score.rrf(),
              Math.round(total * 10_000.0) / 10_000.0,
              Math.max(score.bm25(), score.vector())));
    }
    scored.sort((left, right) -> Double.compare(right.score(), left.score()));
    int injectedTokens = 0;
    int selected = 0;
    var items = new ArrayList<EvidenceItem>(scored.size());
    for (var row : scored) {
      boolean aboveThreshold = row.relevance() >= MIN_RELEVANCE;
      boolean withinLimit = aboveThreshold && selected < cap;
      String reason = !aboveThreshold ? "BELOW_THRESHOLD" : withinLimit ? "" : "OVER_LIMIT";
      if (withinLimit) {
        selected++;
        injectedTokens += row.summary().getTokenEstimate();
      }
      var document = row.document();
      items.add(
          new EvidenceItem(
              row.summary().getId(),
              row.summary().getDocumentId(),
              document == null ? "" : document.getTitle(),
              document == null ? "" : document.getSourcePath(),
              row.summary().getSectionTitle() == null ? "" : row.summary().getSectionTitle(),
              row.summary().getContent(),
              row.summary().getTokenEstimate(),
              row.bm25(),
              row.vector(),
              row.rrf(),
              row.score(),
              withinLimit,
              reason));
    }
    return new RetrievalEvidence(
        query, queryVector != null, List.copyOf(items), selected, injectedTokens);
  }

  /** 打分中间态：排序后再决定谁真的进入 Prompt。 */
  private record ScoredChunk(
      dev.workbench.persistence.view.KnowledgeChunkSummary summary,
      KnowledgeDocumentEntity document,
      double bm25,
      double vector,
      double rrf,
      double score,
      double relevance) {}

  /** 候选集 = 词法 Top-N ∪ 向量下推命中；规模有上界，避免整库向量反序列化。 */
  private static java.util.Set<String> candidateIds(
      List<dev.workbench.persistence.view.KnowledgeChunkSummary> summaries,
      Map<String, Double> pushed,
      String query) {
    var lexical =
        HybridRanker.rank(
            summaries.stream()
                .map(summary -> new HybridRanker.Input(summary.getId(), summary.getContent(), 0))
                .toList(),
            query);
    var ids = new java.util.LinkedHashSet<String>(pushed.keySet());
    lexical.entrySet().stream()
        .sorted((left, right) -> Double.compare(right.getValue().bm25(), left.getValue().bm25()))
        .limit(CANDIDATE_LIMIT)
        .forEach(entry -> ids.add(entry.getKey()));
    return ids;
  }

  /** 只为候选集回表读取 embedding；pgvector 下推已给出余弦的候选无需读取。 */
  private Map<String, float[]> loadCandidateEmbeddings(
      java.util.Set<String> candidateIds, float[] queryVector) {
    if (queryVector == null) return Map.of();
    var rows = chunks.findAllById(candidateIds);
    var result = new HashMap<String, float[]>(rows.size());
    for (var row : rows) {
      var vector = parse(row.getEmbeddingJson());
      // 没有向量的分块不参与余弦：Map.copyOf 不接受空值。
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
      String workspaceId, String model, List<KnowledgeChunkEntity> rows, List<float[]> vectors) {
    if (model == null) return;
    var batch = new java.util.LinkedHashMap<String, float[]>();
    for (int position = 0; position < rows.size() && position < vectors.size(); position++) {
      var vector = vectors.get(position);
      if (vector == null || vector.length == 0) continue;
      batch.put(rows.get(position).getId(), vector);
    }
    for (var index : vectorIndexes) {
      if (index == null || !index.available()) continue;
      index.storeAll(workspaceId, model, batch);
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
              MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("计算知识库文档 SHA-256 失败。", e);
    }
  }

  private static String text(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.strip();
  }
}
