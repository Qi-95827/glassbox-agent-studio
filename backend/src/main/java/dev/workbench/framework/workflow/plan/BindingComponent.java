package dev.workbench.framework.workflow.plan;

/** 固定版本的 Skill 或 Tool 展示项。 */
public record BindingComponent(String id, int version, String name, String kind, String sha256) {}
