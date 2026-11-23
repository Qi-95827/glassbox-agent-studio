package dev.workbench.framework.evaluation;

import java.util.List;
import java.util.Map;

/**
 * 一道标准化评测题。
 *
 * <p>{@code setup} 承载类别专属输入（needles、distractorMemories、workspaceFiles、calls、plans、attacks 等），
 * {@code assertions} 是确定性代码裁判逐条判定的声明，{@code judgeRubric} 仅在开启 LLM-as-a-Judge 时使用。
 */
public record EvalSample(
    String id,
    EvalCategory category,
    String sourcePack,
    String titleZh,
    String titleEn,
    String prompt,
    Map<String, Object> setup,
    List<Map<String, Object>> assertions,
    List<String> judgeRubric) {
  public EvalSample {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("评测题缺少标识。");
    if (category == null) throw new IllegalArgumentException("评测题缺少类别。");
    if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("评测题缺少输入。");
    sourcePack = sourcePack == null || sourcePack.isBlank() ? "custom" : sourcePack;
    titleZh = titleZh == null || titleZh.isBlank() ? id : titleZh;
    titleEn = titleEn == null || titleEn.isBlank() ? titleZh : titleEn;
    setup = setup == null ? Map.of() : Map.copyOf(setup);
    assertions =
        assertions == null ? List.of() : assertions.stream().map(Map::copyOf).toList();
    judgeRubric = judgeRubric == null ? List.of() : List.copyOf(judgeRubric);
  }

  /** 标题按界面语言选择，缺失时回退另一种语言。 */
  public String title(String locale) {
    boolean english =
        locale != null && locale.strip().toLowerCase(java.util.Locale.ROOT).startsWith("en");
    return english ? titleEn : titleZh;
  }
}
