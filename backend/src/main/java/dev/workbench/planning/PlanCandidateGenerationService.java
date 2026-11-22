package dev.workbench.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ExecutionScope;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.framework.planning.PlanCandidateModel;
import dev.workbench.persistence.entity.PlanCandidateEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 候选生成：保存生成请求、异步调用模型、自修复式校验与结算，以及生成中状态的收口（取消、启动中断、停机）。
 *
 * <p>生成请求先持久化再异步调用模型，页面轮询状态。候选只做建议：服务端校验通过才进入 READY，任何状态都不会创建任务、运行、节点、工作区或工具授权。取消与结算都使用状态条件更新，晚到的模型响应不能覆盖用户取消或新输入造成的过期。
 */
class PlanCandidateGenerationService {
  private static final String INSTRUCTIONS =
      dev.workbench.framework.prompt.PromptResourceLoader.load("planning/plan-candidate.md");

  private final PlanCandidateStoreService store;
  private final PlanCandidateWorkspaceService workspaces;
  private final PlanCandidateValidator validator;
  private final PlanCandidateModel model;
  private final ExecutionScopeResolver scopes;
  private final dev.workbench.ownership.OwnershipService ownership;
  private final dev.workbench.privateagent.PrivateAgentCatalog privateAgents;
  private final dev.workbench.modelconnection.ModelConnectionService connections;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final ExecutorService executor;

  /** 排队或生成中的候选任务；取消时同时中断队列中的任务，避免无谓的模型调用。 */
  private final Map<String, Future<?>> pending = new ConcurrentHashMap<>();

  PlanCandidateGenerationService(
      PlanCandidateStoreService store,
      PlanCandidateWorkspaceService workspaces,
      PlanCandidateValidator validator,
      PlanCandidateModel model,
      ExecutionScopeResolver scopes,
      dev.workbench.ownership.OwnershipService ownership,
      dev.workbench.privateagent.PrivateAgentCatalog privateAgents,
      dev.workbench.modelconnection.ModelConnectionService connections,
      ObjectMapper json,
      TransactionTemplate tx,
      ExecutorService executor) {
    this.store = store;
    this.workspaces = workspaces;
    this.validator = validator;
    this.model = model;
    this.scopes = scopes;
    this.ownership = ownership;
    this.privateAgents = privateAgents;
    this.connections = connections;
    this.json = json;
    this.tx = tx;
    this.executor = executor;
  }

  /**
   * 保存一次生成请求并异步调用模型。
   *
   * <p>同一归属下相同请求标识与相同输入返回原候选；内容不同则冲突。声明了前一候选时沿用其组，让同组旧候选在提交新输入后过期。
   */
  synchronized PlanCandidateService.CandidateView generate(
      PlanCandidateService.GenerateRequest request) {
    Objects.requireNonNull(request, "生成请求不能为空");
    PlanCandidateInput input = Objects.requireNonNull(request.input(), "任务输入不能为空");
    ExecutionScope scope = scopes.current();
    if (input.workspaceId() != null) ownership.requireWorkspace(input.workspaceId());
    String planningConnection = connections == null ? null : input.modelConnectionId();
    if (planningConnection != null)
      planningConnection = connections.requireEnabledOwned(planningConnection);
    else if (connections != null)
      planningConnection = connections.defaultConnectionId().orElse(null);
    if (planningConnection != null && input.modelConnectionId() == null)
      input = input.withModelConnection(planningConnection);
    String requestId = requestId(request.requestId());
    String inputSha = input.sha256();
    var existing = store.findByRequestId(scope.ownerScopeKey(), requestId);
    if (existing.isPresent()) {
      var row = existing.get();
      if (!row.getInputSha256().equals(inputSha))
        throw new PlanCandidateConflictException("请求标识已用于不同的任务输入，请换一个请求标识或读取原候选。");
      return store.view(row);
    }
    PlanCandidateWorkspaceService.WorkspaceSnapshot snapshot =
        input.workspaceId() == null
            ? PlanCandidateWorkspaceService.WorkspaceSnapshot.EMPTY
            : workspaces.snapshot(input);
    PlanCandidateEntity previous = previous(scope, request.previousCandidateId());
    String groupId = resolveGroup(scope, request.draftKey(), previous, input);
    String inputJson = store.write(input);
    // 在发起模型调用前冻结短引用、精确版本和摘要；等待期间发布新版本不影响同一响应。
    PlanningAgentSnapshot planning = planningAgents(input, scope.ownerScopeKey());
    PlanCandidateService.CandidateView created =
        tx.execute(
            status ->
                createRow(
                    scope,
                    groupId,
                    requestId,
                    inputSha,
                    inputJson,
                    store.write(planning),
                    snapshot,
                    previous));
    submit(created.id(), input);
    return created;
  }

  /** 声明了前一候选时沿用其组；草稿分组不一致时拒绝，避免旧候选跨草稿过期。 */
  private static String resolveGroup(
      ExecutionScope scope,
      String draftKeyValue,
      PlanCandidateEntity previous,
      PlanCandidateInput input) {
    String draftKey = draftKey(draftKeyValue);
    String draftGroup = draftKey == null ? null : draftKeyGroup(scope, draftKey);
    if (previous != null && draftGroup != null && !previous.getGroupId().equals(draftGroup))
      throw new IllegalArgumentException("候选分组与上一份候选不一致，请重新打开草稿后再生成。");
    return previous == null
        ? (draftGroup == null ? inputGroup(scope, input) : draftGroup)
        : previous.getGroupId();
  }

  private PlanCandidateService.CandidateView createRow(
      ExecutionScope scope,
      String groupId,
      String requestId,
      String inputSha,
      String inputJson,
      String planningJson,
      PlanCandidateWorkspaceService.WorkspaceSnapshot snapshot,
      PlanCandidateEntity previous) {
    int version = store.latestInputVersion(groupId) + 1;
    String id = UUID.randomUUID().toString();
    var row =
        new PlanCandidateEntity(
            id,
            scope.ownerScopeKey(),
            scope.actorKey(),
            groupId,
            version,
            requestId,
            previous == null ? null : previous.getId(),
            PlanCandidateDraft.PROTOCOL_VERSION,
            inputSha,
            inputJson,
            snapshot.fingerprint(),
            snapshot.fileVersionsJson(),
            PlanCandidateStatus.GENERATING.name(),
            Instant.now().toString());
    row.setAgentAliasesJson(planningJson);
    store.save(row);
    // 新输入版本提交后，同组仍可确认的旧候选立即过期；失败与取消保留原状态供追溯。
    store.markGroupStale(groupId, id);
    return store.view(row);
  }

  /** 提交线程池；拒绝时立即按条件结算为失败并释放内存引用。 */
  private void submit(String id, PlanCandidateInput input) {
    var task =
        new FutureTask<Void>(
            () -> {
              try {
                propose(id, input);
              } finally {
                pending.remove(id);
              }
              return null;
            });
    pending.put(id, task);
    try {
      executor.execute(task);
    } catch (RuntimeException e) {
      pending.remove(id);
      store.settle(
          id,
          PlanCandidateStatus.FAILED,
          null,
          null,
          null,
          List.of("执行队列已满，候选未开始生成；同组旧候选已因新输入版本变为 STALE，可直接重试。"),
          null);
      throw new IllegalStateException("规划执行队列已满，候选已标记失败，请稍后重试。", e);
    }
  }

  /** 取消只对生成中的候选生效；已结算候选保持原状态。 */
  PlanCandidateService.CandidateView cancel(String id) {
    ExecutionScope scope = scopes.current();
    var row = store.requireById(id, scope.ownerScopeKey());
    int changed = store.cancelGeneration(id);
    if (changed == 0) {
      if (row.getStatus().equals(PlanCandidateStatus.GENERATING.name()))
        throw new IllegalStateException("候选状态已变化，请刷新后重试。");
      if (row.getStatus().equals(PlanCandidateStatus.CANCELLED.name())) return store.view(row);
      throw new IllegalStateException("候选已经结算，不能取消。");
    }
    // 取消成功时同时中断仍在排队或执行中的任务；模型适配应尽快结束，晚到结果仍由条件结算拒绝。
    var task = pending.remove(id);
    if (task != null) task.cancel(true);
    return store.view(store.findById(id).orElseThrow(() -> new org.springframework.dao.EmptyResultDataAccessException(1)));
  }

  /** 上一个进程遗留的生成中候选在启动时转为中断，不伪造成功。 */
  void interruptOrphans() {
    store.interruptOrphans();
  }

  void shutdown() {
    pending.values().forEach(task -> task.cancel(true));
    pending.clear();
    executor.shutdownNow();
  }

  private void propose(String id, PlanCandidateInput input) {
    if (Thread.currentThread().isInterrupted()) return;
    // 排队期间被取消或已结算时不再调用模型；状态以数据库为准，内存标志只作快速拒绝。
    var row = store.findById(id).orElse(null);
    if (row == null || !PlanCandidateStatus.GENERATING.name().equals(row.getStatus())) return;
    PlanCandidateModel.Reply reply = null;
    try {
      PlanningAgentSnapshot planning = store.readPlanning(row.getAgentAliasesJson());
      reply =
          model.propose(
              INSTRUCTIONS, planningInput(input, planning), input.modelConnectionId());
      if (reply.text() == null || reply.text().length() > 200_000)
        throw new IllegalStateException("模型返回内容为空或超出计划容量。");
      PlanCandidateDraft draft =
          json.readerFor(PlanCandidateDraft.class)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readValue(reply.text());
      var result = validator.validate(draft, input, row.getOwnerScopeKey(), planning);
      store.settle(
          id,
          result.valid() ? PlanCandidateStatus.READY : PlanCandidateStatus.INVALID,
          result.valid() ? result.candidateSha256() : null,
          store.write(result.valid() ? result.normalized() : draft),
          result.valid() ? bindingJson(result.nodes()) : null,
          result.errors(),
          reply);
    } catch (JsonProcessingException e) {
      store.settle(
          id,
          PlanCandidateStatus.FAILED,
          null,
          null,
          null,
          List.of("模型返回的计划不是可解析的 JSON。可以重试或改用固定模板。"),
          reply);
    } catch (RuntimeException e) {
      String reason = e.getMessage() == null ? "计划生成失败。" : e.getMessage();
      if (reason.length() > 500) reason = reason.substring(0, 499) + "…";
      store.settle(id, PlanCandidateStatus.FAILED, null, null, null, List.of(reason), reply);
    }
  }

  private String bindingJson(List<PlanCandidateValidator.ResolvedNode> nodes) {
    var value = new LinkedHashMap<String, Object>();
    for (var node : nodes)
      value.put(
          node.node().key(),
          node.binding() == null
              ? Map.of(
                  "source",
                  "BUILTIN",
                  "reason",
                  "未指定私有 Agent，沿用角色对应的内置定义。",
                  "definitionId",
                  node.definition().id(),
                  "definitionVersion",
                  node.definition().version(),
                  "name",
                  node.definition().id(),
                  "specSha256",
                  "",
                  "modelStrategy",
                  "INHERIT_TASK",
                  "modelMode",
                  "")
              : node.binding());
    return store.write(value);
  }

  private PlanCandidateEntity previous(ExecutionScope scope, String id) {
    if (id == null || id.isBlank()) return null;
    return store
        .findById(id, scope.ownerScopeKey())
        .orElseThrow(() -> new IllegalArgumentException("上一份候选不存在或不属于当前执行归属。"));
  }

  private PlanningAgentSnapshot planningAgents(PlanCandidateInput input, String ownerScopeKey) {
    var aliases = new LinkedHashMap<String, PlanningAgentSnapshot.Alias>();
    var errors = new ArrayList<String>();
    PlanningAgentSnapshot.ManualChoice manual = null;
    try {
      if ("MANUAL".equals(input.agentMode())) {
        var ref =
            privateAgents.manualReference(
                ownerScopeKey, input.manualAgentId(), input.manualAgentVersion());
        var version = privateAgents.version(ref);
        manual =
            new PlanningAgentSnapshot.ManualChoice(
                ref.id(), ref.version(), version.getName(), version.getSpecSha256());
      } else if ("RECOMMEND".equals(input.agentMode())) {
        var recommendations =
            privateAgents.recommendations(ownerScopeKey, input.candidateAgentIds());
        if (recommendations.isEmpty())
          errors.add("本次勾选的私有 Agent 没有可用的已发布推荐版本，请重新选择。");
        for (int index = 0; index < recommendations.size(); index++) {
          var recommendation = recommendations.get(index);
          aliases.put(
              "P" + (index + 1),
              new PlanningAgentSnapshot.Alias(
                  "P" + (index + 1),
                  recommendation.ref().id(),
                  recommendation.ref().version(),
                  recommendation.name(),
                  recommendation.permissionRole(),
                  recommendation.description(),
                  recommendation.tools(),
                  recommendation.specSha256()));
        }
      }
    } catch (RuntimeException e) {
      errors.add(e.getMessage() == null ? "私有 Agent 选择无效。" : e.getMessage());
    }
    return new PlanningAgentSnapshot(aliases, manual, errors);
  }

  private String planningInput(PlanCandidateInput input, PlanningAgentSnapshot planning) {
    var value = new LinkedHashMap<String, Object>();
    value.put("goal", input.goal());
    value.put("entryKind", input.entryKind().name());
    value.put("deliverable", input.deliverable());
    value.put("constraints", input.constraints());
    value.put("acceptanceCriteria", input.acceptanceCriteria());
    value.put("authorizedFiles", input.fileScopes());
    value.put("readOnly", input.readOnly());
    value.put("agentMode", input.agentMode());
    if (planning != null && !planning.aliases().isEmpty()) {
      // 只发送发起规划时冻结的短引用、版本和可见摘要，不发送私有正文或组件 ID。
      var candidates = new ArrayList<Map<String, Object>>();
      for (var alias : planning.aliases().values()) {
        candidates.add(
            Map.of(
                "ref", alias.alias(),
                "version", alias.version(),
                "name", alias.name(),
                "role", alias.permissionRole(),
                "description", alias.description(),
                "tools", alias.tools()));
      }
      value.put("candidateAgents", candidates);
      value.put(
          "agentSelectionRule",
          "Only choose a candidateAgents ref when it fits the node role and read-only capability; return it as node.agentRef.");
    }
    if (planning != null && !planning.errors().isEmpty())
      value.put("agentSelectionErrors", planning.errors());
    return store.write(value);
  }

  /**
   * 草稿分组：同一草稿的输入编辑和重新生成共用一个组，旧候选会随新版本过期。
   *
   * <p>没有草稿标识时退回输入指纹，只保证完全相同输入的重复提交归入同组。
   */
  private static String draftKeyGroup(ExecutionScope scope, String draftKey) {
    return new CanonicalDigest("plan-group-v2")
        .field(scope.ownerScopeKey())
        .field("draft")
        .field(draftKey)
        .hex()
        .substring(0, 32);
  }

  private static String inputGroup(ExecutionScope scope, PlanCandidateInput input) {
    var digest = new CanonicalDigest("plan-group-v1").field(scope.ownerScopeKey());
    digest
        .field(input.entryKind().name())
        .field(input.workspaceId() == null ? "" : input.workspaceId());
    digest.field(input.goal());
    digest.field(input.fileScopes().size());
    for (String path : input.fileScopes()) digest.field(path);
    return digest.hex().substring(0, 32);
  }

  private static String draftKey(String value) {
    if (value == null || value.isBlank()) return null;
    if (!value.matches("[A-Za-z0-9_-]{8,80}"))
      throw new IllegalArgumentException("草稿标识需要 8 至 80 位字母、数字、下划线或连字符。");
    return value;
  }

  private static String requestId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{8,80}"))
      throw new IllegalArgumentException("请求标识需要 8 至 80 位字母、数字、下划线或连字符。");
    return value;
  }
}
