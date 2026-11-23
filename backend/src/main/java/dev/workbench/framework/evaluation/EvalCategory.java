package dev.workbench.framework.evaluation;

/** 评测实验室的六大能力域；顺序固定用于雷达图与汇总排序。 */
public enum EvalCategory {
  CODING_REFLECTION("端到端代码与有界反思", "Coding & Bounded Reflection"),
  TOOL_MCP("工具调用与 MCP 治理", "Tool Use & MCP Governance"),
  CONTEXT_COMPACTION("四级上下文压缩 (NIAH)", "Four-Tier Context Compaction"),
  LONG_TERM_MEMORY("三层长期记忆与混合召回", "Layered Memory & Hybrid Recall"),
  PLANNING_MULTI_AGENT("多 Agent 规划与交接协作", "Planning & Multi-Agent Handoff"),
  SAFETY_INJECTION("安全边界与越权/注入攻防", "Safety Boundary & Injection Defense");

  private final String labelZh;
  private final String labelEn;

  EvalCategory(String labelZh, String labelEn) {
    this.labelZh = labelZh;
    this.labelEn = labelEn;
  }

  public String labelZh() {
    return labelZh;
  }

  public String labelEn() {
    return labelEn;
  }

  /** 解析外部 JSONL 中的类别；未知值拒绝，避免静默落到错误执行器。 */
  public static EvalCategory parse(String raw) {
    if (raw == null || raw.isBlank()) throw new IllegalArgumentException("评测类别不能为空。");
    try {
      return EvalCategory.valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("未知评测类别：" + raw);
    }
  }
}
