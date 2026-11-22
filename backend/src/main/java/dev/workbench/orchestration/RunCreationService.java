package dev.workbench.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.bug.BugService;
import dev.workbench.domain.*;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.planning.EntryPolicyService;
import dev.workbench.planning.EntryRouteService;
import dev.workbench.workspace.AuthorizedFileSet;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import dev.workbench.workspace.WorkspaceService;
import java.util.List;

/** 创建任务的校验、入口策略、任务/运行初始化与提交前派发编排。 */
final class RunCreationService {
  private final TaskCreationService taskCreations;
  private final WorkspaceService workspaces;
  private final BugService bugs;
  private final ObjectMapper json;
  private final EntryPolicyService entryPolicies;
  private final EntryRouteService entryRoutes;
  private final WorkspaceAuthorizationService workspaceAuthorization;
  private final WorkflowScheduler workflows;
  private final RunDispatchService dispatcher;
  private final dev.workbench.modelconnection.ModelConnectionService connections;

  RunCreationService(
      TaskCreationService taskCreations,
      WorkspaceService workspaces,
      BugService bugs,
      ObjectMapper json,
      EntryPolicyService entryPolicies,
      EntryRouteService entryRoutes,
      WorkspaceAuthorizationService workspaceAuthorization,
      WorkflowScheduler workflows,
      RunDispatchService dispatcher,
      dev.workbench.modelconnection.ModelConnectionService connections) {
    this.taskCreations = taskCreations;
    this.workspaces = workspaces;
    this.bugs = bugs;
    this.json = json;
    this.entryPolicies = entryPolicies;
    this.entryRoutes = entryRoutes;
    this.workspaceAuthorization = workspaceAuthorization;
    this.workflows = workflows;
    this.dispatcher = dispatcher;
    this.connections = connections;
  }

  /** 创建一个个人示例任务并排队；不触发真实代码工具。 */
  String create(CreateTaskRequest request) {
    Draft draft = new Draft();
    readGoal(request, draft);
    resolveWorkspace(request, draft);
    readLimits(request, draft);
    readEntry(request, draft);
    buildEntities(request, draft);
    persist(draft);
    return draft.runId;
  }

  private void readGoal(CreateTaskRequest request, Draft draft) {
    draft.locale =
        dev.workbench.framework.prompt.PromptResourceLoader.normalizeLocale(
            request.locale() == null || request.locale().isBlank()
                ? org.springframework.context.i18n.LocaleContextHolder.getLocale().toLanguageTag()
                : request.locale());
    draft.goal = request.goal() == null ? "" : request.goal().trim();
    if (draft.goal.isEmpty() || draft.goal.length() > 500) {
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("validation.task.goal"));
    }
    draft.scenario =
        Scenario.fromCode(request.scenario() == null ? "normal" : request.scenario());
    String connectionId =
        request.modelConnectionId() == null ? null : request.modelConnectionId().strip();
    String requested =
        request.modelMode() == null || request.modelMode().isBlank()
            ? null
            : request.modelMode().strip();
    if (connections == null) {
      // 兼容只构造队列边界的单元测试：没有 BYOK 服务时保持内置模式。
      draft.mode = ModelMode.fromCode(requested == null ? "mock" : requested);
      return;
    }
    if (connectionId != null && !connectionId.isEmpty()) {
      draft.modelConnectionId = connections.requireEnabledOwned(connectionId);
      draft.mode = ModelMode.USER;
    } else if (requested == null) {
      // 兼容旧客户端：未指定模型时优先使用当前用户的默认自带 Key 连接，否则回到 mock。
      var fallback = connections.defaultConnectionId();
      if (fallback.isPresent()) {
        draft.modelConnectionId = fallback.get();
        draft.mode = ModelMode.USER;
      } else draft.mode = ModelMode.MOCK;
    } else {
      draft.mode = ModelMode.fromCode(requested);
      if (draft.mode == ModelMode.USER)
        draft.modelConnectionId =
            connections
                .defaultConnectionId()
                .orElseThrow(
                    () -> new IllegalArgumentException("请先在「设置 → 模型与 API Key」配置并启用模型连接。"));
    }
  }

  private void resolveWorkspace(CreateTaskRequest request, Draft draft) {
    draft.workspaceId =
        request.workspaceId() == null || request.workspaceId().isBlank()
            ? null
            : request.workspaceId();
    draft.fileScope =
        request.fileScope() == null || request.fileScope().isBlank()
            ? null
            : request.fileScope().replace('\\', '/');
    draft.hasFileList = request.fileScopes() != null && !request.fileScopes().isEmpty();
    if (draft.hasFileList && draft.fileScope != null)
      throw new IllegalArgumentException("请使用文件列表或单文件字段，不能同时指定。");
    draft.writeEnabled = Boolean.TRUE.equals(request.writeEnabled());
    draft.memoryEnabled = Boolean.TRUE.equals(request.memoryEnabled());
    draft.bugId =
        request.bugId() == null || request.bugId().isBlank() ? null : request.bugId();
    if (draft.bugId != null
        && (draft.workspaceId == null
            || bugs.list().stream().noneMatch(b -> b.id().equals(draft.bugId))))
      throw new IllegalArgumentException("模拟 Bug 必须关联有效工作区。");
    draft.directoryScopes =
        request.directoryScopes() == null ? List.of() : request.directoryScopes();
    draft.excludedPaths =
        request.excludedPaths() == null ? List.of() : request.excludedPaths();
    draft.maxCostMicros = request.maxCostMicros();
    if (draft.mode == ModelMode.USER && draft.maxCostMicros != null)
      throw new IllegalArgumentException("自带 Key 模型使用你自己的额度，平台费用硬预算仅适用于内置模型。");
    if (!draft.directoryScopes.isEmpty() && draft.workspaceId == null)
      throw new IllegalArgumentException("目录授权必须选择工作区。");
    if (!draft.excludedPaths.isEmpty() && draft.directoryScopes.isEmpty())
      throw new IllegalArgumentException("排除规则必须配合目录授权使用。");
    if (draft.workspaceId == null) {
      if (draft.fileScope != null
          || draft.hasFileList
          || draft.writeEnabled
          || draft.memoryEnabled)
        throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("validation.task.workspaceRequired"));
      return;
    }
    authorizeWorkspace(request, draft);
  }

  private void authorizeWorkspace(CreateTaskRequest request, Draft draft) {
    List<String> requested =
        draft.hasFileList
            ? request.fileScopes()
            : draft.fileScope == null ? List.<String>of() : List.of(draft.fileScope);
    if (workspaceAuthorization != null) {
      draft.workspaceScope =
          workspaceAuthorization.prepare(
              draft.workspaceId, requested, draft.directoryScopes, draft.excludedPaths);
      draft.fileScopes = draft.workspaceScope.effectiveFiles();
    } else {
      draft.fileScopes = new AuthorizedFileSet(requested).paths();
    }
    if (draft.fileScopes.isEmpty())
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("validation.task.fileRequired"));
    try {
      if (json.writeValueAsString(draft.fileScopes).length() > 20_000)
        throw new IllegalArgumentException("授权文件路径总长度超过允许范围。");
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("授权文件路径无法处理。", e);
    }
    for (String path : draft.fileScopes) workspaces.read(draft.workspaceId, path);
    draft.fileScope = draft.fileScopes.getFirst();
  }

  private void readLimits(CreateTaskRequest request, Draft draft) {
    draft.tokenLimit = request.maxReportedTokens();
    if (draft.tokenLimit != null && (draft.tokenLimit < 1 || draft.tokenLimit > 1_000_000))
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("validation.task.maxTokens"));
    draft.identicalLimit = request.maxIdenticalTools();
    if (draft.identicalLimit != null && (draft.identicalLimit < 1 || draft.identicalLimit > 20))
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("validation.task.identicalTools"));
    draft.agentRounds = request.maxAgentRounds();
    draft.agentTools = request.maxAgentTools();
    if (draft.agentRounds != null && (draft.agentRounds < 1 || draft.agentRounds > 20))
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("validation.task.agentRounds"));
    if (draft.agentTools != null && (draft.agentTools < 0 || draft.agentTools > 20))
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("validation.task.agentTools"));
    draft.roleBudgets =
        request.roleBudgets() == null
            ? java.util.Map.<AgentRole, RoleBudget>of()
            : request.roleBudgets();
    if (draft.roleBudgets.entrySet().stream()
        .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null))
      throw new IllegalArgumentException("角色限额不能包含空角色或空配置。");
    draft.roleBudgets = java.util.Map.copyOf(draft.roleBudgets);
    draft.toolBudgets =
        request.toolBudgets() == null
            ? java.util.Map.<ToolKind, Integer>of()
            : request.toolBudgets();
    if (draft.toolBudgets.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey() == null
                    || entry.getValue() == null
                    || entry.getValue() < 0
                    || entry.getValue() > 20))
      throw new IllegalArgumentException("工具限额必须使用已知工具和 0 至 20 的整数。");
    draft.toolBudgets = java.util.Map.copyOf(draft.toolBudgets);
  }

  private void readEntry(CreateTaskRequest request, Draft draft) {
    draft.constraints = validatedItems(request.constraints(), "约束");
    draft.acceptance = validatedItems(request.acceptanceCriteria(), "验收条件");
    draft.entryKind =
        request.entryKind() == null ? TaskEntryKind.GENERAL : request.entryKind();
    draft.entryValidation =
        switch (draft.entryKind) {
          case REQUIREMENT, DESIGN, CODE, TEST ->
              entryPolicies.validate(
                  draft.entryKind,
                  draft.goal,
                  draft.workspaceId,
                  draft.writeEnabled,
                  draft.fileScopes,
                  draft.acceptance);
          default -> null;
        };
    draft.effectiveAcceptance =
        draft.entryValidation == null ? draft.acceptance : draft.entryValidation.acceptance();
    draft.routeDecision =
        entryRoutes == null
            ? null
            : entryRoutes.route(
                draft.entryKind,
                draft.workspaceId,
                draft.writeEnabled,
                draft.fileScopes,
                draft.directoryScopes,
                request.workflowTemplate());
    draft.selectedTemplate =
        request.workflowTemplate() != null
            ? request.workflowTemplate()
            : draft.routeDecision == null ? null : draft.routeDecision.templateId();
  }

  private void buildEntities(CreateTaskRequest request, Draft draft) {
    draft.taskId = dev.workbench.domain.WorkbenchIds.newId();
    draft.runId = dev.workbench.domain.WorkbenchIds.newId();
    draft.task =
        new TaskDefinition(
            java.util.UUID.fromString(draft.taskId),
            draft.goal,
            draft.scenario,
            draft.mode,
            draft.workspaceId,
            draft.fileScope,
            draft.writeEnabled,
            draft.bugId,
            draft.memoryEnabled,
            draft.constraints,
            draft.effectiveAcceptance,
            draft.entryKind,
            draft.fileScopes,
            draft.roleBudgets,
            draft.toolBudgets,
            draft.modelConnectionId,
            draft.locale);
    draft.limits =
        new RunLimits(
            request.maxRounds(),
            request.maxTools(),
            request.maxPerTool(),
            draft.tokenLimit,
            draft.identicalLimit,
            request.maxSeconds(),
            draft.agentRounds,
            draft.agentTools);
    draft.reviewPlan = Boolean.TRUE.equals(request.reviewPlan());
    WorkflowTemplate template =
        draft.selectedTemplate == null ? null : workflows.template(draft.selectedTemplate);
    if (draft.reviewPlan && template == null)
      throw new IllegalArgumentException("只有动态模板支持执行前确认。");
    if (template == null && request.nodeBudgets() != null && !request.nodeBudgets().isEmpty())
      throw new IllegalArgumentException("只有动态模板支持节点预算。");
    if (template != null) {
      template.scope().authorizationDigest(draft.task);
      if (template.scope().requiresBug() && !bugs.supports(draft.task.bugId()))
        throw new IllegalArgumentException("所选模拟 Bug 不存在或当前不受支持。");
      template = template.withNodeBudgets(request.nodeBudgets());
    }
    draft.template = template;
    boolean english = dev.workbench.framework.prompt.PromptResourceLoader.isEnglish(draft.locale);
    draft.createdMessage =
        draft.mode == ModelMode.DEEPSEEK
            ? english
                ? "Task created: AgentScope uses DeepSeek Flash and may call simulated tools."
                : "任务已创建：AgentScope 使用 DeepSeek Flash，可调用模拟工具。"
            : draft.mode == ModelMode.USER
                ? english
                    ? "Task created: using your own model connection and API key."
                    : "任务已创建：使用你自带的模型连接与 API Key。"
                : english
                    ? "Task created: AgentScope uses the simulated model and simulated tools."
                    : "任务已创建：AgentScope 使用模拟模型和模拟工具。";
  }

  private void persist(Draft draft) {
    String createdMessage;
    if (draft.template == null) {
      taskCreations.persist(
          draft.task,
          java.util.UUID.fromString(draft.runId),
          draft.limits,
          draft.createdMessage,
          null,
          false,
          draft.entryValidation,
          draft.routeDecision,
          draft.workspaceScope,
          draft.maxCostMicros);
    } else {
      boolean english = dev.workbench.framework.prompt.PromptResourceLoader.isEnglish(draft.locale);
      createdMessage =
          draft.reviewPlan
              ? english
                  ? "Workflow plan saved and awaiting user confirmation."
                  : "工作流计划已保存，等待用户确认。"
              : english
                  ? "Workflow plan saved and awaiting execution."
                  : "工作流计划已保存，等待执行。";
      taskCreations.persist(
          draft.task,
          java.util.UUID.fromString(draft.runId),
          draft.limits,
          createdMessage,
          draft.template,
          draft.reviewPlan,
          draft.entryValidation,
          draft.routeDecision,
          draft.workspaceScope,
          draft.maxCostMicros);
    }
    // 事务已提交；待确认计划仍等待用户批准，不触发派发。
    if (!draft.reviewPlan) dispatcher.dispatchAfterCommit(draft.runId);
  }

  private static List<String> validatedItems(List<String> values, String label) {
    if (values == null) return List.of();
    if (values.size() > 10) throw new IllegalArgumentException(label + "最多 10 条。");
    var result = new java.util.ArrayList<String>();
    for (String value : values) {
      if (value == null || value.isBlank() || value.strip().length() > 300)
        throw new IllegalArgumentException(label + "每条需为 1 至 300 字。");
      result.add(value.strip());
    }
    return List.copyOf(result);
  }

  /** 校验过程中的可变创建草稿，保持原有校验顺序与字段来源。 */
  private static final class Draft {
    String goal;
    String locale;
    Scenario scenario;
    ModelMode mode;
    String modelConnectionId;
    String workspaceId;
    String fileScope;
    boolean hasFileList;
    boolean writeEnabled;
    boolean memoryEnabled;
    String bugId;
    List<String> directoryScopes;
    List<String> excludedPaths;
    Long maxCostMicros;
    List<String> fileScopes = List.of();
    WorkspaceAuthorizationService.Prepared workspaceScope;
    Long tokenLimit;
    Integer identicalLimit;
    Integer agentRounds;
    Integer agentTools;
    java.util.Map<AgentRole, RoleBudget> roleBudgets;
    java.util.Map<ToolKind, Integer> toolBudgets;
    List<String> constraints;
    List<String> acceptance;
    TaskEntryKind entryKind;
    EntryPolicyService.Validation entryValidation;
    List<String> effectiveAcceptance;
    EntryRouteService.Decision routeDecision;
    String selectedTemplate;
    String taskId;
    String runId;
    TaskDefinition task;
    RunLimits limits;
    boolean reviewPlan;
    WorkflowTemplate template;
    String createdMessage;
  }
}
