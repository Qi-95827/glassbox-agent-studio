package dev.workbench.orchestration;

/**
 * 新建运行的标准内置工作流推导（收口包 G-2 的唯一事实来源）。
 *
 * <p>背景：历史直线执行路径（{@code DirectRunExecutor}）已彻底下线，任何新建运行都必须物化为标准
 * {@code WorkflowPlan}，否则玻璃盒、反思、断点与节点视图都无法覆盖。因此「未显式指定模板」不再等于「没有计划」，
 * 而是按任务的授权形态推导出唯一确定的内置模板。
 *
 * <p>推导顺序（与产品契约一致，不可随意调整）：
 *
 * <ol>
 *   <li>绑定模拟 Bug → {@code bug-fix}（复现、分析、修复、回归与独立审查）；
 *   <li>有工作区且显式授权写入 → {@code workspace-change}；
 *   <li>有工作区但只读 → {@code workspace-analysis}；
 *   <li>无工作区的纯文本任务 → {@code general-analysis-review}（ANALYST → REVIEWER）。
 * </ol>
 */
final class StandardWorkflowRouting {
  static final String BUG_FIX = "bug-fix";
  static final String WORKSPACE_CHANGE = "workspace-change";
  static final String WORKSPACE_ANALYSIS = "workspace-analysis";
  static final String GENERAL_ANALYSIS_REVIEW = "general-analysis-review";

  private StandardWorkflowRouting() {}

  /**
   * 返回该任务形态必须使用的标准模板标识。
   *
   * @param bugId 绑定的模拟 Bug 标识；非空即视为 Bug 修复任务
   * @param workspaceId 已授权工作区标识；为空表示纯文本任务
   * @param writeEnabled 是否显式授权写入
   */
  static String templateFor(String bugId, String workspaceId, boolean writeEnabled) {
    if (bugId != null && !bugId.isBlank()) return BUG_FIX;
    if (workspaceId != null && !workspaceId.isBlank())
      return writeEnabled ? WORKSPACE_CHANGE : WORKSPACE_ANALYSIS;
    return GENERAL_ANALYSIS_REVIEW;
  }
}
