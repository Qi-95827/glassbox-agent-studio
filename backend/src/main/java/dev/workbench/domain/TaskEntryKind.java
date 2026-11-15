package dev.workbench.domain;

/** 用户选择的任务起点；不代表已经生成相应产物或获得额外权限。 */
public enum TaskEntryKind {
  GENERAL("通用目标", "厘清目标、已知条件和需要验证的问题。"),
  REQUIREMENT("需求", "梳理用户目标、约束和验收条件，列出缺失信息；不要声称设计已经完成。"),
  DESIGN("概要设计", "核对已有设计、模块边界及依赖，指出实现前仍需确认的事项。"),
  CODE("代码", "先结合授权代码与现有证据分析实现，不能扩大文件权限。"),
  TEST("测试", "区分测试计划、已运行测试与实际结果，没有执行的测试不能声称通过。"),
  BUG("Bug", "区分问题描述、复现证据、可能原因与回归结果，不能凭描述声称已修复。");

  private final String label;
  private final String guidance;

  TaskEntryKind(String label, String guidance) {
    this.label = label;
    this.guidance = guidance;
  }

  public String label() {
    return label;
  }

  public String guidance() {
    return guidance;
  }
}
