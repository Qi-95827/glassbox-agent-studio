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
}
