package dev.workbench.privateagent;

import java.util.List;

/** 私有 Skill、工具和 Agent 的公开 DTO；记录字段名即 HTTP JSON 字段名。 */
public final class PrivateComponentModels {
  private PrivateComponentModels() {}

  public record SkillRequest(
      String name,
      String summary,
      String usageNotes,
      String inputContract,
      String outputContract,
      String contentMd) {}

  public record ToolRequest(
      String name, String description, String executorKind, List<String> fileExtensions) {}

  public record ComponentRef(String id, Integer version) {}

  public record AgentRequest(
      String name,
      String description,
      String responsibility,
      String outputRequirements,
      String permissionRole,
      Boolean recommendable,
      String modelMode,
      List<ComponentRef> skills,
      List<ComponentRef> tools,
      String modelStrategy) {
    /** 兼容旧客户端：未填写时继承任务模型策略。 */
    public AgentRequest(
        String name,
        String description,
        String responsibility,
        String outputRequirements,
        String permissionRole,
        Boolean recommendable,
        String modelMode,
        List<ComponentRef> skills,
        List<ComponentRef> tools) {
      this(
          name,
          description,
          responsibility,
          outputRequirements,
          permissionRole,
          recommendable,
          modelMode,
          skills,
          tools,
          "INHERIT_TASK");
    }
  }

  public record SkillView(
      String id,
      String name,
      String summary,
      String usageNotes,
      String inputContract,
      String outputContract,
      String contentMd,
      String status,
      int currentVersion,
      boolean draftChanged,
      String archivedAt,
      String updatedAt) {}

  public record ToolView(
      String id,
      String name,
      String description,
      String executorKind,
      String executorLabel,
      List<String> fileExtensions,
      String status,
      int currentVersion,
      boolean draftChanged,
      String archivedAt,
      String updatedAt) {}

  public record PinnedSkill(
      String id,
      int version,
      String name,
      String sha256,
      String guidance,
      String usageNotes,
      String inputContract,
      String outputContract) {}

  public record PinnedTool(
      String id,
      int version,
      String name,
      String executorKind,
      String toolName,
      List<String> fileExtensions,
      String sha256) {}

  public record AgentView(
      String id,
      String name,
      String description,
      String responsibility,
      String outputRequirements,
      String permissionRole,
      boolean recommendable,
      String modelMode,
      String modelStrategy,
      List<ComponentRef> skills,
      List<ComponentRef> tools,
      String status,
      int currentVersion,
      boolean draftChanged,
      String archivedAt,
      String updatedAt,
      String definitionRef,
      String specSha256) {}

  public record AgentVersionView(
      int version,
      String name,
      String description,
      String permissionRole,
      boolean recommendable,
      String modelMode,
      String modelStrategy,
      List<PinnedSkill> skills,
      List<PinnedTool> tools,
      String specSha256,
      String createdAt) {}

  public record Draft(
      String name,
      String description,
      String responsibility,
      String outputRequirements,
      String permissionRole,
      boolean recommendable,
      String modelMode,
      String modelStrategy,
      List<String> openQuestions,
      List<ComponentRef> suggestedSkills,
      List<ComponentRef> suggestedTools) {
    public Draft {
      openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
      suggestedSkills = suggestedSkills == null ? List.of() : List.copyOf(suggestedSkills);
      suggestedTools = suggestedTools == null ? List.of() : List.copyOf(suggestedTools);
    }

    /** 兼容旧调用，未指定时继承任务模型策略且不预选组件。 */
    public Draft(
        String name,
        String description,
        String responsibility,
        String outputRequirements,
        String permissionRole,
        boolean recommendable,
        String modelMode,
        List<String> openQuestions) {
      this(
          name,
          description,
          responsibility,
          outputRequirements,
          permissionRole,
          recommendable,
          modelMode,
          "INHERIT_TASK",
          openQuestions,
          List.of(),
          List.of());
    }

    public Draft(
        String name,
        String description,
        String responsibility,
        String outputRequirements,
        String permissionRole,
        boolean recommendable,
        String modelMode,
        String modelStrategy,
        List<String> openQuestions) {
      this(
          name,
          description,
          responsibility,
          outputRequirements,
          permissionRole,
          recommendable,
          modelMode,
          modelStrategy,
          openQuestions,
          List.of(),
          List.of());
    }
  }

  /** 模型只返回可见组件代号，由服务端解析成当前账号的稳定 ID 和发布版本。 */
  public record ModelDraft(
      String name,
      String description,
      String responsibility,
      String outputRequirements,
      String permissionRole,
      Boolean recommendable,
      String modelStrategy,
      String modelMode,
      List<String> openQuestions,
      List<String> skillRefs,
      List<String> toolRefs) {}
}
