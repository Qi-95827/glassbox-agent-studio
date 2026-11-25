package dev.workbench.planning;

import dev.workbench.domain.TaskEntryKind;
import dev.workbench.workspace.AuthorizedFileSet;
import java.util.List;
import java.util.Objects;

/**
 * 用户已确认的规划输入快照。
 *
 * <p>只包含任务目标、约束、验收条件、交付物和工作区文件白名单；不包含模型凭据、命令、 工具权限或审批结果。文件白名单在生成候选时冻结，确认运行时仍必须按当前权限重新核对。
 *
 * <p>目录范围（{@code directoryScopes}）在整个工作区 {@code "."} 时由服务端展开为有效文件集合，展开结果
 * 写回 {@code fileScopes} 并随候选一起冻结；排除规则一并保存，确认运行时重新核对。
 */
public record PlanCandidateInput(
    String goal,
    TaskEntryKind entryKind,
    String workspaceId,
    boolean writeEnabled,
    List<String> fileScopes,
    List<String> constraints,
    List<String> acceptanceCriteria,
    String deliverable,
    String agentMode,
    String manualAgentId,
    Integer manualAgentVersion,
    List<String> candidateAgentIds,
    boolean readOnly,
    String modelConnectionId,
    String locale,
    List<String> directoryScopes,
    List<String> excludedPaths) {
  /** 兼容未选择自带 Key 连接、也未记录语言的旧候选与客户端。 */
  public PlanCandidateInput(
      String goal,
      TaskEntryKind entryKind,
      String workspaceId,
      boolean writeEnabled,
      List<String> fileScopes,
      List<String> constraints,
      List<String> acceptanceCriteria,
      String deliverable,
      String agentMode,
      String manualAgentId,
      Integer manualAgentVersion,
      List<String> candidateAgentIds,
      boolean readOnly,
      String modelConnectionId) {
    this(
        goal,
        entryKind,
        workspaceId,
        writeEnabled,
        fileScopes,
        constraints,
        acceptanceCriteria,
        deliverable,
        agentMode,
        manualAgentId,
        manualAgentVersion,
        candidateAgentIds,
        readOnly,
        modelConnectionId,
        null,
        List.of(),
        List.of());
  }

  /** 兼容未选择自带 Key 连接的旧候选与客户端。 */
  public PlanCandidateInput(
      String goal,
      TaskEntryKind entryKind,
      String workspaceId,
      boolean writeEnabled,
      List<String> fileScopes,
      List<String> constraints,
      List<String> acceptanceCriteria,
      String deliverable,
      String agentMode,
      String manualAgentId,
      Integer manualAgentVersion,
      List<String> candidateAgentIds,
      boolean readOnly) {
    this(
        goal,
        entryKind,
        workspaceId,
        writeEnabled,
        fileScopes,
        constraints,
        acceptanceCriteria,
        deliverable,
        agentMode,
        manualAgentId,
        manualAgentVersion,
        candidateAgentIds,
        readOnly,
        null);
  }

  private static final int MAX_ITEMS = 10;
  private static final int MAX_ITEM_LENGTH = 300;
  private static final int MAX_DIRECTORIES = 20;
  private static final int MAX_EXCLUSIONS = 20;

  /** 兼容旧候选与旧客户端：未指定 Agent 时沿用内置定义映射。 */
  public PlanCandidateInput(
      String goal,
      TaskEntryKind entryKind,
      String workspaceId,
      boolean writeEnabled,
      List<String> fileScopes,
      List<String> constraints,
      List<String> acceptanceCriteria,
      String deliverable) {
    this(
        goal,
        entryKind,
        workspaceId,
        writeEnabled,
        fileScopes,
        constraints,
        acceptanceCriteria,
        deliverable,
        "BUILTIN",
        null,
        null,
        List.of(),
        false);
  }

  public PlanCandidateInput {
    goal = required(goal, 500, "任务目标");
    entryKind = Objects.requireNonNull(entryKind, "任务入口不能为空");
    workspaceId = blankToNull(workspaceId);
    if (entryKind != TaskEntryKind.CODE) throw new IllegalArgumentException("本期只为代码入口生成执行计划草稿。");
    if (readOnly && writeEnabled) throw new IllegalArgumentException("只读计划不能同时申请写入授权。");
    if (!readOnly && !writeEnabled) throw new IllegalArgumentException("生成执行计划需要用户确认的写入授权。");
    directoryScopes = directories(directoryScopes, workspaceId);
    excludedPaths = exclusions(excludedPaths);
    fileScopes =
        fileScopes == null || fileScopes.isEmpty()
            ? List.of()
            // 目录展开结果允许超过手工选择上限；没有目录授权时仍按显式文件上限校验。
            : directoryScopes.isEmpty()
                ? new AuthorizedFileSet(fileScopes).paths()
                : AuthorizedFileSet.normalizeScope(fileScopes);
    if (workspaceId == null && fileScopes.isEmpty())
      throw new IllegalArgumentException("独立交付需要先确认至少一个交付文件。");
    constraints = items(constraints, MAX_ITEMS, "约束", false);
    acceptanceCriteria = items(acceptanceCriteria, MAX_ITEMS, "验收条件", true);
    deliverable = optional(deliverable, 200, "交付物");
    agentMode =
        agentMode == null || agentMode.isBlank()
            ? "BUILTIN"
            : agentMode.strip().toUpperCase(java.util.Locale.ROOT);
    if (!List.of("BUILTIN", "MANUAL", "RECOMMEND").contains(agentMode))
      throw new IllegalArgumentException("Agent 选择方式无效。");
    manualAgentId = blankToNull(manualAgentId);
    if ("MANUAL".equals(agentMode) && manualAgentId == null)
      throw new IllegalArgumentException("指定私有 Agent 时缺少 Agent 标识。");
    if (manualAgentVersion != null && manualAgentVersion < 1)
      throw new IllegalArgumentException("Agent 版本无效。");
    candidateAgentIds =
        candidateAgentIds == null
            ? List.of()
            : candidateAgentIds.stream()
                .map(item -> required(item, 36, "候选 Agent"))
                .distinct()
                .toList();
    if ("RECOMMEND".equals(agentMode) && candidateAgentIds.isEmpty())
      throw new IllegalArgumentException("允许规划器推荐时至少勾选一个私有 Agent。");
    if (candidateAgentIds.size() > 8) throw new IllegalArgumentException("候选 Agent 最多 8 个。");
    modelConnectionId =
        modelConnectionId == null || modelConnectionId.isBlank()
            ? null
            : modelConnectionId.strip();
    if (modelConnectionId != null && !modelConnectionId.matches("[A-Za-z0-9-]{1,36}"))
      throw new IllegalArgumentException("模型连接标识无效。");
    locale =
        locale == null || locale.isBlank()
            ? null
            : locale.strip().toLowerCase(java.util.Locale.ROOT).startsWith("en")
                ? "en-US"
                : "zh-CN";
  }

  /** 请求未显式指定任务语言时，由控制器从 Accept-Language 注入。 */
  public PlanCandidateInput withLocale(String value) {
    String normalized =
        value == null || value.isBlank()
            ? null
            : value.strip().toLowerCase(java.util.Locale.ROOT).startsWith("en")
                ? "en-US"
                : "zh-CN";
    if (java.util.Objects.equals(locale, normalized)) return this;
    return new PlanCandidateInput(
        goal,
        entryKind,
        workspaceId,
        writeEnabled,
        fileScopes,
        constraints,
        acceptanceCriteria,
        deliverable,
        agentMode,
        manualAgentId,
        manualAgentVersion,
        candidateAgentIds,
        readOnly,
        modelConnectionId,
        normalized,
        directoryScopes,
        excludedPaths);
  }

  /** 已确认的授权快照；模型节点只能引用其中的文件。 */
  public List<String> authorizedFiles() {
    return fileScopes;
  }

  /** 冻结本次规划实际使用的连接；显式选择与默认回退走同一条路径。 */
  public PlanCandidateInput withModelConnection(String connectionId) {
    if (java.util.Objects.equals(modelConnectionId, connectionId)) return this;
    return new PlanCandidateInput(
        goal,
        entryKind,
        workspaceId,
        writeEnabled,
        fileScopes,
        constraints,
        acceptanceCriteria,
        deliverable,
        agentMode,
        manualAgentId,
        manualAgentVersion,
        candidateAgentIds,
        readOnly,
        connectionId,
        locale,
        directoryScopes,
        excludedPaths);
  }

  /** 目录展开后的服务端快照：保留目录与排除规则，同时冻结有效文件集合。 */
  public PlanCandidateInput withExpandedScope(
      List<String> directories, List<String> exclusions, List<String> effectiveFiles) {
    return new PlanCandidateInput(
        goal,
        entryKind,
        workspaceId,
        writeEnabled,
        effectiveFiles,
        constraints,
        acceptanceCriteria,
        deliverable,
        agentMode,
        manualAgentId,
        manualAgentVersion,
        candidateAgentIds,
        readOnly,
        modelConnectionId,
        locale,
        directories,
        exclusions);
  }

  /** 输入摘要进入候选摘要；同一内容的重复请求得到同一指纹。 */
  public String sha256() {
    var digest =
        new CanonicalDigest("plan-input-v1")
            .field(goal)
            .field(entryKind.name())
            .field(workspaceId == null ? "" : workspaceId)
            .field(writeEnabled ? "write" : "read")
            .field(fileScopes.size());
    for (String path : fileScopes) digest.field(path);
    // 仅在声明目录授权时扩展摘要，历史候选的输入指纹保持完全不变。
    if (!directoryScopes.isEmpty()) {
      digest.field("directory-scopes-v1").field(directoryScopes.size());
      for (String directory : directoryScopes) digest.field(directory);
      digest.field(excludedPaths.size());
      for (String path : excludedPaths) digest.field(path);
    }
    digest.field(constraints.size());
    for (String item : constraints) digest.field(item);
    digest.field(acceptanceCriteria.size());
    for (String item : acceptanceCriteria) digest.field(item);
    digest.field(deliverable == null ? "" : deliverable);
    if (!"BUILTIN".equals(agentMode) || readOnly) {
      digest
          .field(agentMode)
          .field(manualAgentId == null ? "" : manualAgentId)
          .field(manualAgentVersion == null ? "" : Integer.toString(manualAgentVersion))
          .field(candidateAgentIds.size());
      for (String id : candidateAgentIds) digest.field(id);
      digest.field(readOnly ? "read-only" : "write");
    }
    // 未选择自带 Key 时保持历史输入摘要不变，旧候选仍可读取和校验。
    if (modelConnectionId != null) digest.field(modelConnectionId);
    return digest.hex();
  }

  /** 目录授权规范化：允许 {@code "."} 表示整个工作区，最多 20 个范围项。 */
  private static List<String> directories(List<String> values, String workspaceId) {
    if (values == null || values.isEmpty()) return List.of();
    if (values.size() > MAX_DIRECTORIES)
      throw new IllegalArgumentException("目录范围最多 " + MAX_DIRECTORIES + " 条。");
    if (workspaceId == null) throw new IllegalArgumentException("目录授权必须选择工作区。");
    var result = new java.util.ArrayList<String>();
    for (String value : values) {
      String normalized = AuthorizedFileSet.normalizeDirectory(value);
      if (!result.contains(normalized)) result.add(normalized);
    }
    return List.copyOf(result);
  }

  private static List<String> exclusions(List<String> values) {
    if (values == null || values.isEmpty()) return List.of();
    if (values.size() > MAX_EXCLUSIONS)
      throw new IllegalArgumentException("排除路径最多 " + MAX_EXCLUSIONS + " 条。");
    var result = new java.util.ArrayList<String>();
    for (String value : values) {
      String normalized = AuthorizedFileSet.normalize(value);
      if (!result.contains(normalized)) result.add(normalized);
    }
    return List.copyOf(result);
  }

  private static String required(String value, int maximum, String name) {
    if (value == null || value.isBlank() || value.strip().length() > maximum)
      throw new IllegalArgumentException(name + "为空或超过 " + maximum + " 字。");
    return value.strip();
  }

  private static String optional(String value, int maximum, String name) {
    if (value == null || value.isBlank()) return null;
    if (value.strip().length() > maximum)
      throw new IllegalArgumentException(name + "超过 " + maximum + " 字。");
    return value.strip();
  }

  private static List<String> items(
      List<String> values, int maximum, String name, boolean required) {
    if (values == null) values = List.of();
    if (values.size() > maximum) throw new IllegalArgumentException(name + "最多 " + maximum + " 条。");
    if (required && values.isEmpty()) throw new IllegalArgumentException("请至少填写一条" + name + "。");
    return values.stream()
        .map(item -> required(item, MAX_ITEM_LENGTH, name + "条目"))
        .distinct()
        .toList();
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) return null;
    if (value.strip().length() > 36) throw new IllegalArgumentException("工作区标识无效。");
    return value.strip();
  }
}
