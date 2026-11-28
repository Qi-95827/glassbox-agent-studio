package dev.workbench.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** BM25：停用词、IDF 专名加权、英文词干、中文双字与 RRF 融合。 */
class HybridRankerTest {
  @Test
  void stopwordsDoNotCreateLexicalHits() {
    var tokens = HybridRanker.tokens("What is the did go to the support group?");
    assertFalse(tokens.contains("what"));
    assertFalse(tokens.contains("the"));
    assertFalse(tokens.contains("did"));
    assertFalse(tokens.contains("go"));
    assertTrue(tokens.contains("support"));
    assertTrue(tokens.contains("group"));
  }

  @Test
  void rareProperNounOutranksFrequentSpeakerName() {
    var corpus =
        List.of(
            new HybridRanker.Input(
                "evidence",
                "8 May 2023 Caroline: I went to a LGBTQ support group yesterday and it was powerful.",
                0),
            new HybridRanker.Input(
                "small-talk",
                "Caroline: Hey Mel! Good to see you! How have you been? What's up with you?",
                0),
            new HybridRanker.Input(
                "career",
                "Caroline: I'm keen on counseling or working in mental health and support work.",
                0));
    var ranked = HybridRanker.rank(corpus, "When did Caroline go to the LGBTQ support group?");
    assertTrue(ranked.get("evidence").bm25() > ranked.get("small-talk").bm25());
    assertTrue(ranked.get("evidence").bm25() > 0.8);
  }

  @Test
  void stemsEnglishPluralsAndGerunds() {
    assertEquals("counsel", HybridRanker.stem("counseling"));
    assertEquals("agency", HybridRanker.stem("agencies"));
    assertEquals("agency", HybridRanker.stem("agency"));
  }

  @Test
  void chineseBigramsMatchWithoutWhitespace() {
    var corpus =
        List.of(
            new HybridRanker.Input("a", "前端状态机与单步演示使用原生 JavaScript", 0),
            new HybridRanker.Input("b", "部署环境采用蓝绿发布", 0));
    var ranked = HybridRanker.rank(corpus, "前端单步");
    assertTrue(ranked.get("a").bm25() > 0);
    assertEquals(0, ranked.get("b").bm25(), 0.0001);
  }

  @Test
  void rrfFusesVectorRankIntoWinner() {
    var corpus =
        List.of(
            new HybridRanker.Input("lexical", "sort.js 原生排序", 0),
            new HybridRanker.Input("semantic", "前端单步状态机", 0.9));
    var ranked = HybridRanker.rank(corpus, "排序 sort.js");
    assertTrue(ranked.get("lexical").bm25() > 0);
    assertTrue(ranked.get("semantic").vector() > 0.8);
    assertTrue(ranked.get("semantic").rrf() > 0);
  }

  /**
   * 词频反例：两篇文档词项集合完全相同、长度相同，唯一差别是目标关键词出现次数（5 次 vs 1 次）。
   *
   * <p>若实现按文档去重后计算 TF（恒定 1），两篇文档得分会完全相等，排序失去"关键词越密集越相关"的语义。
   */
  @Test
  void termFrequencyRanksRepeatedKeywordAboveSingleOccurrence() {
    var corpus =
        List.of(
            new HybridRanker.Input(
                "repeated", "gateway gateway gateway gateway gateway timeout on upstream", 0),
            new HybridRanker.Input(
                "single", "gateway timeout timeout timeout timeout timeout on upstream", 0));
    var ranked = HybridRanker.rank(corpus, "gateway");
    assertTrue(
        ranked.get("repeated").bm25() > ranked.get("single").bm25(),
        "同长文档中关键词出现 5 次的 BM25 必须严格高于出现 1 次："
            + ranked.get("repeated").bm25()
            + " vs "
            + ranked.get("single").bm25());
    assertTrue(ranked.get("single").bm25() > 0);
  }

  /**
   * 中英混排反例：单个连续词段里同时含英文与汉字时，必须按字符脚本拆开发射。
   *
   * <p>此前整段一旦含汉字就整体按 2-gram 切分，{@code "SpringBoot项目报错"} 会被切成
   * {@code "sp"}/{@code "pr"}/{@code "ri"}/{@code "t项"} 等碎片，{@code "springboot"} 永远匹配不上。
   */
  @Test
  void mixedScriptTokensSplitIntoAsciiAndCjkSegments() {
    var tokens = HybridRanker.tokens("SpringBoot项目报错");
    assertTrue(tokens.contains("springboot"), "ASCII 词段必须整体保留：" + tokens);
    assertTrue(tokens.contains("项目"), "CJK 词段必须按双字切分：" + tokens);
    assertFalse(tokens.contains("sp"), "不得出现 ASCII 碎片：" + tokens);
    assertFalse(tokens.contains("pr"), "不得出现 ASCII 碎片：" + tokens);
    assertFalse(tokens.contains("ri"), "不得出现 ASCII 碎片：" + tokens);
    assertFalse(tokens.contains("t项"), "不得把英文与汉字混切：" + tokens);

    var corpus =
        List.of(
            new HybridRanker.Input("mixed", "SpringBoot项目报错需要排查", 0),
            new HybridRanker.Input("other", "部署环境采用蓝绿发布", 0));
    var ranked = HybridRanker.rank(corpus, "springboot");
    assertTrue(ranked.get("mixed").bm25() > 0, "英文关键词必须命中中英混排文档");
    assertEquals(0, ranked.get("other").bm25(), 0.0001);
  }

  @Test
  void mixedScriptKeepsAsciiSearchTermsIntact() {
    var tokens = HybridRanker.tokens("ToolGateway网关超时");
    assertTrue(tokens.contains("toolgateway"), tokens.toString());
    assertTrue(tokens.contains("网关"), tokens.toString());
    assertTrue(tokens.contains("超时"), tokens.toString());
  }
}
