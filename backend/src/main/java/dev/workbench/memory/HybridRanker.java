package dev.workbench.memory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BM25 + 向量余弦 + RRF 倒数排名融合。
 *
 * <p>词法侧内置中英文停用词、英文轻量词干归一化与中文双字切分，并按当前工作区语料实时计算 IDF，
 * 让专有名词、时间、组织、文件名与报错码获得高权重。向量侧接受已算好的余弦分，与其他名次按
 * {@code 1 / (60 + rank)} 融合；返回分项供玻璃盒抽屉展示。
 */
public final class HybridRanker {
  private static final double K1 = 1.2;
  private static final double B = 0.75;
  private static final int RRF_K = 60;

  private static final Set<String> STOPWORDS =
      new java.util.HashSet<>(
          List.of(
          "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "could", "did",
          "do", "does", "for", "from", "go", "had", "has", "have", "he", "her", "him", "his", "how",
          "i", "if", "in", "into", "is", "it", "its", "just", "me", "my", "no", "not", "of", "on",
          "or", "our", "she", "so", "than", "that", "the", "their", "them", "then", "there", "these",
          "they", "this", "to", "was", "we", "were", "what", "when", "where", "which", "who", "why",
          "will", "with", "would", "you", "your", "about", "after", "before", "should", "also",
          "more", "most", "some", "any", "all", "one", "two", "very", "s", "t", "being", "been",
          "的", "了", "是", "在", "我", "你", "他", "她", "它", "们", "和", "与", "及", "或", "就",
          "都", "而", "也", "很", "吗", "呢", "吧", "啊", "着", "过", "把", "被", "对", "从", "到",
          "为", "以", "于", "上", "下", "中", "个", "这", "那", "有", "没", "不", "请", "问", "一个",
          "什么", "怎么", "为什么", "哪些", "哪里", "多少", "如何", "可以", "需要", "应该", "以及",
          "the", "did", "go"));

  private HybridRanker() {}

  /** 单条候选项；vectorScore 由调用方按 Embedding 余弦预先算好，未启用时为 0。 */
  public record Input(String id, String text, double vectorScore) {}

  /** 单条融合结果；全部归一化到 [0,1]。 */
  public record Ranked(String id, double bm25, double vector, double rrf) {}

  public static Map<String, Ranked> rank(List<Input> corpus, String query) {
    if (corpus == null || corpus.isEmpty()) return Map.of();
    var queryTokens = tokens(query);
    if (queryTokens.isEmpty()) {
      var empty = new LinkedHashMap<String, Ranked>();
      for (var input : corpus) empty.put(input.id(), new Ranked(input.id(), 0, input.vectorScore(), 0));
      return Map.copyOf(empty);
    }
    var termFrequency = new HashMap<String, Map<String, Integer>>();
    var documentLength = new HashMap<String, Integer>();
    var documentFrequency = new HashMap<String, Integer>();
    double totalLength = 0;
    for (var input : corpus) {
      // 文档词项保留重复次数：BM25 的 TF 必须来自真实词频，去重后 TF 恒为 1，排序会退化为二值匹配。
      var frequencies = new HashMap<String, Integer>();
      for (String token : tokens(input.text())) frequencies.merge(token, 1, Integer::sum);
      int length = frequencies.values().stream().mapToInt(Integer::intValue).sum();
      termFrequency.put(input.id(), frequencies);
      documentLength.put(input.id(), length);
      totalLength += length;
      // 文档频率仍按唯一词项累加，保证 df <= documents。
      for (String token : frequencies.keySet()) documentFrequency.merge(token, 1, Integer::sum);
    }
    int documents = corpus.size();
    double averageLength = Math.max(1, totalLength / documents);

    var raw = new LinkedHashMap<String, Double>();
    double max = 0;
    for (var input : corpus) {
      var frequencies = termFrequency.get(input.id());
      int length = documentLength.getOrDefault(input.id(), 0);
      double score = 0;
      for (String token : queryTokens) {
        int frequency = frequencies.getOrDefault(token, 0);
        if (frequency == 0) continue;
        int df = documentFrequency.getOrDefault(token, 0);
        double idf = Math.log(1 + (documents - df + 0.5) / (df + 0.5));
        double denominator = frequency + K1 * (1 - B + B * length / averageLength);
        score += idf * frequency * (K1 + 1) / denominator;
      }
      raw.put(input.id(), score);
      max = Math.max(max, score);
    }

    var bm25 = new LinkedHashMap<String, Double>();
    for (var entry : raw.entrySet())
      bm25.put(entry.getKey(), max <= 0 ? 0 : entry.getValue() / max);

    var vectorById = new HashMap<String, Double>();
    for (var input : corpus) vectorById.put(input.id(), input.vectorScore());
    var vectorRanks = ranks(corpus, id -> vectorById.getOrDefault(id, 0d));
    var lexicalRanks = ranks(corpus, id -> bm25.getOrDefault(id, 0d));
    boolean hasVector = vectorRanks.values().stream().anyMatch(rank -> rank > 0);
    double ceiling = hasVector ? 2.0 / (RRF_K + 1) : 1.0 / (RRF_K + 1);
    var result = new LinkedHashMap<String, Ranked>();
    for (var input : corpus) {
      int lexicalRank = lexicalRanks.getOrDefault(input.id(), 0);
      int vectorRank = vectorRanks.getOrDefault(input.id(), 0);
      double rrf = 0;
      if (lexicalRank > 0) rrf += 1.0 / (RRF_K + lexicalRank);
      if (vectorRank > 0) rrf += 1.0 / (RRF_K + vectorRank);
      result.put(
          input.id(),
          new Ranked(
              input.id(),
              round(bm25.getOrDefault(input.id(), 0d)),
              round(clamp(input.vectorScore())),
              round(ceiling <= 0 ? 0 : clamp(rrf / ceiling))));
    }
    return Map.copyOf(result);
  }

  private static Map<String, Integer> ranks(List<Input> corpus, Score score) {
    var sorted =
        corpus.stream()
            .map(input -> Map.entry(input.id(), score.of(input.id())))
            .filter(entry -> entry.getValue() > 0)
            .sorted(
                Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                    .thenComparing(Map.Entry.comparingByKey()))
            .toList();
    var result = new HashMap<String, Integer>();
    for (int index = 0; index < sorted.size(); index++)
      result.put(sorted.get(index).getKey(), index + 1);
    return result;
  }

  @FunctionalInterface
  private interface Score {
    double of(String id);
  }

  /** 停用词过滤 + 英文轻量词干 + 中文双字切分。 */
  public static List<String> tokens(String text) {
    if (text == null || text.isBlank()) return List.of();
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    var result = new ArrayList<String>();
    var run = new StringBuilder();
    for (int index = 0; index < normalized.length(); index++) {
      char value = normalized.charAt(index);
      if (Character.isLetterOrDigit(value) || value == '_') {
        run.append(value);
      } else if (run.length() > 0) {
        emit(run.toString(), result);
        run.setLength(0);
      }
    }
    if (run.length() > 0) emit(run.toString(), result);
    return List.copyOf(result);
  }

  /**
   * 按字符脚本拆分单个连续词段后再分别发射。
   *
   * <p>中文与英文混排（如 {@code "SpringBoot项目"}）必须拆成 ASCII 词段与 CJK 词段：ASCII 段走轻量词干与停用词，
   * CJK 段走单字/双字切分。此前整段一旦含汉字就按 2-gram 全切，导致 {@code "springboot"} 被切碎成
   * {@code "sp"}/{@code "pr"} 等垃圾碎片而彻底失配。
   */
  private static void emit(String run, List<String> output) {
    var ascii = new StringBuilder();
    var cjk = new ArrayList<Integer>();
    for (int offset = 0; offset < run.length(); ) {
      int codePoint = run.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (isHan(codePoint)) {
        flushAscii(ascii, output);
        cjk.add(codePoint);
      } else {
        flushCjk(cjk, output);
        ascii.appendCodePoint(codePoint);
      }
    }
    flushAscii(ascii, output);
    flushCjk(cjk, output);
  }

  private static void flushAscii(StringBuilder buffer, List<String> output) {
    if (buffer.length() == 0) return;
    String stem = stem(buffer.toString());
    buffer.setLength(0);
    if (stem.length() >= 2 && !STOPWORDS.contains(stem)) output.add(stem);
  }

  private static void flushCjk(List<Integer> buffer, List<String> output) {
    if (buffer.isEmpty()) return;
    if (buffer.size() == 1) {
      String single = new String(new int[] {buffer.getFirst()}, 0, 1);
      if (!STOPWORDS.contains(single)) output.add(single);
    } else {
      int[] characters = buffer.stream().mapToInt(Integer::intValue).toArray();
      for (int index = 0; index + 1 < characters.length; index++) {
        String bigram = new String(characters, index, 2);
        if (!STOPWORDS.contains(bigram)) output.add(bigram);
      }
    }
    buffer.clear();
  }

  private static boolean isHan(int codePoint) {
    return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
  }

  /** 仅处理高频后缀，避免过度词干化伤害专有名词与报错码。 */
  static String stem(String token) {
    if (token.length() <= 3) return token;
    if (token.endsWith("ies") && token.length() > 4)
      return token.substring(0, token.length() - 3) + "y";
    if (token.endsWith("ied") && token.length() > 4)
      return token.substring(0, token.length() - 3) + "y";
    if (token.endsWith("ing") && token.length() > 5)
      return token.substring(0, token.length() - 3);
    if (token.endsWith("ed") && token.length() > 4)
      return token.substring(0, token.length() - 2);
    if (token.endsWith("es") && token.length() > 4)
      return token.substring(0, token.length() - 2);
    if (token.endsWith("s") && !token.endsWith("ss") && token.length() > 3)
      return token.substring(0, token.length() - 1);
    return token;
  }

  private static double clamp(double value) {
    return Math.max(0, Math.min(1, value));
  }

  private static double round(double value) {
    return Math.round(value * 10_000.0) / 10_000.0;
  }
}
