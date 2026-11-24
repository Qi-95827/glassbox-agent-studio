package dev.workbench.infrastructure.model;

/**
 * 模型上下文窗口推断。
 *
 * <p>AgentScope 的 {@code Model#getContextWindowSize()} 默认返回 {@code 0}，而四级压缩以「窗口 &gt; 0」为
 * 前置条件：不设置窗口就等于永久关闭压缩，长会话会把供应商额度顶满甚至触发 400。这里按模型名给出保守 上限，自定义端点使用可配置默认值，而不是退回 0。
 *
 * <p>覆盖顺序：系统属性 {@code workbench.model.context-window} &gt; 已知模型族 &gt; {@code
 * workbench.model.default-context-window}（默认 128000）。取值只影响压缩水位，不影响计费。
 */
final class ModelContextWindows {
  private static final int DEFAULT_WINDOW = 128_000;
  private static final String OVERRIDE_PROPERTY = "workbench.model.context-window";
  private static final String DEFAULT_PROPERTY = "workbench.model.default-context-window";

  private ModelContextWindows() {}

  /** 解析当前模型可用的上下文窗口；永不返回 0。 */
  static int forModel(String modelName) {
    Integer override = Integer.getInteger(OVERRIDE_PROPERTY);
    if (override != null && override > 0) return override;
    String name = modelName == null ? "" : modelName.strip().toLowerCase(java.util.Locale.ROOT);
    if (name.startsWith("claude")) return 200_000;
    if (name.startsWith("gpt-4.1") || name.startsWith("gpt-5")) return 200_000;
    if (name.startsWith("gpt") || name.startsWith("o1") || name.startsWith("o3")) return 128_000;
    if (name.startsWith("deepseek")) return 128_000;
    if (name.startsWith("qwen") || name.startsWith("qwq")) return 128_000;
    if (name.startsWith("glm")) return 128_000;
    if (name.startsWith("kimi") || name.startsWith("moonshot")) return 128_000;
    if (name.startsWith("doubao")) return 128_000;
    Integer configured = Integer.getInteger(DEFAULT_PROPERTY);
    return configured != null && configured > 0 ? configured : DEFAULT_WINDOW;
  }
}
