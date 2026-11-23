package dev.workbench.framework.evaluation;

/**
 * 一次评测实验被冻结的配置快照。
 *
 * <p>批次之间是否可比由该快照决定；运行时不得读取浏览器会话重新解释开关。
 */
public record EvalExperimentConfig(
    String modelMode,
    String modelConnectionId,
    boolean enableCompaction,
    boolean enableLongTermMemory,
    boolean enableReflection,
    boolean enableMcp,
    int repeatK,
    int sampleLimit,
    Double maxCostUsd,
    String judgeModelConnectionId) {
  public static final int MAX_REPEAT = 3;
  public static final int MAX_SAMPLE_LIMIT = 50;

  public EvalExperimentConfig {
    modelMode =
        modelMode == null || modelMode.isBlank()
            ? "mock"
            : modelMode.strip().toLowerCase(java.util.Locale.ROOT);
    if ("user".equals(modelMode)) modelMode = "byok";
    if (!java.util.List.of("mock", "deepseek", "byok").contains(modelMode))
      throw new IllegalArgumentException("未知模型模式：" + modelMode);
    modelConnectionId = blankToNull(modelConnectionId);
    if (!"byok".equals(modelMode) && modelConnectionId != null)
      throw new IllegalArgumentException("只有 BYOK 模式可以绑定模型连接。");
    if (repeatK < 1 || repeatK > MAX_REPEAT)
      throw new IllegalArgumentException("重复次数应为 1 至 " + MAX_REPEAT + "。");
    if (sampleLimit < 1 || sampleLimit > MAX_SAMPLE_LIMIT)
      throw new IllegalArgumentException("单类抽样上限应为 1 至 " + MAX_SAMPLE_LIMIT + "。");
    if (maxCostUsd != null && (maxCostUsd < 0 || maxCostUsd > 10_000))
      throw new IllegalArgumentException("费用上限超出允许范围。");
    judgeModelConnectionId = blankToNull(judgeModelConnectionId);
  }

  public static EvalExperimentConfig defaults() {
    return new EvalExperimentConfig("mock", null, true, true, true, false, 1, 4, null, null);
  }

  public boolean usesByok() {
    return "byok".equals(modelMode) && modelConnectionId != null;
  }

  public boolean judgeEnabled() {
    return judgeModelConnectionId != null;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
