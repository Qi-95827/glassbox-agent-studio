package dev.workbench.framework.workflow.plan;

/**
 * 有界反思回边：仅允许审查节点指向其直接上游目标节点。
 *
 * <p>回边不是普通 DAG 依赖，不会让目标节点等待审查节点；它只描述“审查不通过时重做上游”的受控闭环。
 * {@code maxRevisions} 必须显式声明并落在 1 至 5 之间，禁止无界循环。
 */
public record RevisionEdge(String fromReviewerNodeKey, String toTargetNodeKey, int maxRevisions) {
  public static final int DEFAULT_MAX_REVISIONS = 2;
  public static final int HARD_MAX_REVISIONS = 5;

  public RevisionEdge {
    if (fromReviewerNodeKey == null || fromReviewerNodeKey.isBlank())
      throw new IllegalArgumentException("反思回边缺少审查节点。");
    if (toTargetNodeKey == null || toTargetNodeKey.isBlank())
      throw new IllegalArgumentException("反思回边缺少目标节点。");
    if (maxRevisions < 1 || maxRevisions > HARD_MAX_REVISIONS)
      throw new IllegalArgumentException("反思次数必须在 1 至 " + HARD_MAX_REVISIONS + " 之间。");
  }
}
