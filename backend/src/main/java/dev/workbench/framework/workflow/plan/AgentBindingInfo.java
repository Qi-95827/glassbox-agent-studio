package dev.workbench.framework.workflow.plan;

/**
 * 计划节点的 Agent 选择记录。
 *
 * <p>内置与私有定义都保存来源和理由；私有定义额外保存固定版本摘要与组件快照，页面和执行器不重新 猜测当时的选择。
 */
public record AgentBindingInfo(
    String kind,
    String source,
    String reason,
    String name,
    String specSha256,
    String componentsJson) {}
