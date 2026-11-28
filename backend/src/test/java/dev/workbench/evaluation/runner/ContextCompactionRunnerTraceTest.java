package dev.workbench.evaluation.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.workbench.control.MultiTierContextCompactor;
import dev.workbench.framework.evaluation.EvalAnswerModel;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalExperimentConfig;
import dev.workbench.framework.evaluation.EvalSample;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 压缩评测 Runner 必须如实上报 {@code compactionApplied}，否则 Eval Lab v2 的压缩 A/B 对照永远只能落
 * {@code INSUFFICIENT_EVIDENCE}。
 *
 * <p>{@code compactionApplied} 的语义与 {@code CodingReflectionRunner} 严格对齐：代表本 Runner 真实下推了
 * {@code enableCompaction} 开关的控制逻辑，与请求值本身是 true 还是 false 无关。
 */
class ContextCompactionRunnerTraceTest {
  private final ContextCompactionRunner runner =
      new ContextCompactionRunner(
          new MultiTierContextCompactor(), mock(EvalAnswerModel.class));

  @Test
  void miniCaseReportsCompactionAppliedForBothSwitchValues() {
    var enabled = runner.run(caseSample(), config(true), "LOCAL");
    assertEquals(
        Boolean.TRUE, enabled.trace().get("compactionApplied"), "开启压缩的题必须上报已应用");
    assertEquals(Boolean.TRUE, enabled.trace().get("compactionEnabled"));

    var disabled = runner.run(caseSample(), config(false), "LOCAL");
    assertEquals(
        Boolean.TRUE,
        disabled.trace().get("compactionApplied"),
        "关闭压缩同样是真实下推了开关控制，必须上报已应用而不是缺失");
    assertEquals(Boolean.FALSE, disabled.trace().get("compactionEnabled"));
  }

  /**
   * 反例：问题正文本身就超过供应商预留窗口，压缩后仍触发 {@code CONTEXT_LIMIT} 提前返回。
   * 提前返回之前必须先写入 {@code compactionApplied}，否则失败题会因缺少生效证据被判成「证据不足」。
   */
  @Test
  void agentCaseKeepsCompactionAppliedEvenWhenContextLimitAbortsEarly() {
    var execution = runner.run(oversizedAgentSample(), config(false), "LOCAL");

    assertEquals(
        "CONTEXT_LIMIT",
        execution.metrics().get("errorCode"),
        "夹具必须真实触发 CONTEXT_LIMIT 提前返回：" + execution.metrics());
    assertEquals(
        Boolean.TRUE,
        execution.trace().get("compactionApplied"),
        "提前返回前必须留存真实生效证据：" + execution.trace());
    assertEquals(Boolean.FALSE, execution.trace().get("compactionEnabled"));
    assertTrue(
        execution.trace().containsKey("compactionTier"), "既有口径不得因新增字段而丢失");
  }

  private static EvalSample caseSample() {
    return new EvalSample(
        "compaction-mini-1",
        EvalCategory.CONTEXT_COMPACTION,
        "mini",
        "长上下文埋针",
        "Needle in a long context",
        "请在长上下文中找回全部关键约束。",
        Map.of(
            "targetUtilization", 0.65,
            "haystackRounds", 6,
            "toolBlobChars", 1_200,
            "needles", List.of("针-甲", "针-乙"),
            "needlePlacement", "checkpoint"),
        List.of(),
        List.of());
  }

  private static EvalSample oversizedAgentSample() {
    String hugeQuestion = "请依据文档回答最终选择题，并复述全部约束。".repeat(6_000);
    return new EvalSample(
        "compaction-agent-1",
        EvalCategory.CONTEXT_COMPACTION,
        "agent",
        "超窗问题",
        "Oversized question",
        hugeQuestion,
        Map.of(
            "hubPackMode", "agent_eval_v2",
            "documentContext", "文档正文",
            "choices", List.of("选项一", "选项二"),
            "referenceAnswer", "A",
            "targetUtilization", 0.68),
        List.of(),
        List.of());
  }

  private static EvalExperimentConfig config(boolean enableCompaction) {
    return new EvalExperimentConfig(
        "mock", null, enableCompaction, false, false, false, 1, 5, null, null);
  }
}
