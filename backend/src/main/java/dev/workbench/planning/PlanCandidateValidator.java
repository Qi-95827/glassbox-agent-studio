package dev.workbench.planning;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.definition.AgentDefinition;
import dev.workbench.framework.agent.definition.AgentDefinitionCatalog;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.agent.definition.AgentDefinitionRegistry;
import dev.workbench.framework.workflow.plan.AgentBinding;
import dev.workbench.framework.workflow.plan.BindingComponent;
import dev.workbench.privateagent.PrivateAgentCatalog;
import dev.workbench.workspace.AuthorizedFileSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 候选计划的归一化与安全校验。
 *
 * <p>模型输出先经过这里，才可能成为可审阅候选。校验覆盖协议版本、节点键与依赖、文件子集、 角色映射的定义版本、互斥写范围和独立审查；任一条不满足就返回具体原因，不自动修补图。
 */
@Component
public class PlanCandidateValidator {
  private static final int MAX_NODES = 8;
  private static final int MAX_ACCEPTANCE = 10;
  private static final int MAX_ITEM_LENGTH = 300;
  private static final int MAX_OUTPUTS = 5;

  private final AgentDefinitionCatalog definitions;
  private final PrivateAgentCatalog privateAgents;

  @Autowired
  public PlanCandidateValidator(
      AgentDefinitionCatalog definitions, PrivateAgentCatalog privateAgents) {
    this.definitions = definitions;
    this.privateAgents = privateAgents;
  }

  /** 只含内置定义的兼容构造，供旧单元验证使用。 */
  public PlanCandidateValidator(AgentDefinitionCatalog definitions) {
    this(definitions, null);
  }

  /** 只含内置定义的兼容构造，供旧单元验证使用。 */
  public PlanCandidateValidator(AgentDefinitionRegistry definitions) {
    this(new AgentDefinitionCatalog(definitions), null);
  }

  /** 通过校验的图；节点已按稳定拓扑顺序排列，文件与依赖已排序去重。 */
  public record Result(
      boolean valid,
      List<String> errors,
      PlanCandidateDraft normalized,
      String candidateSha256,
      List<ResolvedNode> nodes) {
    public Result {
      errors = List.copyOf(errors);
      nodes = List.copyOf(nodes);
    }

    static Result invalid(List<String> errors) {
      return new Result(false, errors, null, null, List.of());
    }
  }

  public record ResolvedNode(
      PlanCandidateDraft.Node node, AgentDefinitionRef definition, AgentBinding binding) {}

  public Result validate(PlanCandidateDraft draft, PlanCandidateInput input) {
    return validate(draft, input, "LOCAL", null);
  }

  public Result validate(PlanCandidateDraft draft, PlanCandidateInput input, String ownerScopeKey) {
    return validate(draft, input, ownerScopeKey, null);
  }

  public Result validate(
      PlanCandidateDraft draft,
      PlanCandidateInput input,
      String ownerScopeKey,
      PlanningAgentSnapshot planning) {
    var errors = new ArrayList<String>();
    if (draft == null) return Result.invalid(List.of("模型没有返回候选计划。"));
    if (draft.schemaVersion() != PlanCandidateDraft.PROTOCOL_VERSION)
      errors.add("候选协议版本必须是 " + PlanCandidateDraft.PROTOCOL_VERSION + "。");
    if (draft.nodes().isEmpty()) errors.add("候选计划至少需要一个节点。");
    if (draft.nodes().size() > MAX_NODES) {
      // 超容量时立即拒绝，避免继续遍历异常大的模型输出。
      errors.add("候选计划最多 " + MAX_NODES + " 个节点。");
      return Result.invalid(errors);
    }
    limit(errors, draft.assumptions(), "假设");
    limit(errors, draft.openQuestions(), "待确认事项");

    var byKey = new LinkedHashMap<String, PlanCandidateDraft.Node>();
    for (int index = 0; index < draft.nodes().size(); index++) {
      var node = draft.nodes().get(index);
      if (node == null) {
        errors.add("第 " + (index + 1) + " 个节点为空。");
        continue;
      }
      String label =
          node.key() == null || node.key().isBlank() ? "第 " + (index + 1) + " 个节点" : node.key();
      if (node.key() == null || !node.key().matches("[a-z][a-z0-9-]{0,31}"))
        errors.add(label + "：节点键必须小写字母开头，只含小写字母、数字和连字符，最长 32 位。");
      else if (byKey.putIfAbsent(node.key(), node) != null) errors.add(label + "：节点键重复。");
      if (node.title() == null || node.title().isBlank() || node.title().strip().length() > 80)
        errors.add(label + "：节点标题为空或超过 80 字。");
      if (node.role() == null) errors.add(label + "：节点角色无效。");
      if (node.objective() == null
          || node.objective().isBlank()
          || node.objective().strip().length() > 500) errors.add(label + "：节点目标为空或超过 500 字。");
      if (node.acceptanceCriteria().isEmpty()) errors.add(label + "：至少需要一条节点验收条件。");
      if (node.acceptanceCriteria().size() > MAX_ACCEPTANCE)
        errors.add(label + "：节点验收条件最多 " + MAX_ACCEPTANCE + " 条。");
      if (node.dependsOn().size() > MAX_NODES) errors.add(label + "：直接依赖数量超出计划容量。");
      if (node.readFiles().size() > 20 || node.writeFiles().size() > 20)
        errors.add(label + "：文件范围超出计划容量。");
      for (String item : node.acceptanceCriteria())
        if (item == null || item.isBlank() || item.strip().length() > MAX_ITEM_LENGTH)
          errors.add(label + "：验收条件为空或超过 " + MAX_ITEM_LENGTH + " 字。");
    }

    validateDependencies(byKey, errors);
    List<String> order = topological(byKey, errors);
    validateFiles(byKey, input, errors);
    var selection = selectAgents(byKey, input, ownerScopeKey, planning, errors);
    validateRolesAndOutputs(byKey, errors, selection);
    validateReviewCoverage(byKey, errors, input.readOnly());

    if (!errors.isEmpty()) return Result.invalid(errors);

    var normalizedNodes = new ArrayList<PlanCandidateDraft.Node>();
    var resolved = new ArrayList<ResolvedNode>();
    for (String key : order) {
      var raw = byKey.get(key);
      var binding = selection.bindings().get(key);
      boolean privateAgent = binding != null;
      var node =
          new PlanCandidateDraft.Node(
              raw.key(),
              raw.title().strip(),
              raw.role(),
              raw.objective().strip(),
              raw.dependsOn().stream().sorted().distinct().toList(),
              raw.readFiles().stream()
                  .map(AuthorizedFileSet::normalize)
                  .sorted()
                  .distinct()
                  .toList(),
              raw.writeFiles().stream()
                  .map(AuthorizedFileSet::normalize)
                  .sorted()
                  .distinct()
                  .toList(),
              raw.expectedOutputs().stream()
                  .map(
                      output ->
                          new PlanCandidateDraft.ExpectedOutput(
                              output.kind(),
                              output.kind() == PlanCandidateDraft.OutputKind.FILE
                                  ? AuthorizedFileSet.normalize(output.path())
                                  : null,
                              output.required()))
                  .toList(),
              raw.acceptanceCriteria().stream().map(String::strip).distinct().toList(),
              // 推荐保留模型提出的短引用，便于页面显示；手动指定由服务端绑定，不伪造模型选择。
              "RECOMMEND".equals(input.agentMode()) ? raw.agentRef() : null);
      normalizedNodes.add(node);
      resolved.add(
          new ResolvedNode(
              node,
              binding == null ? definitionFor(node.role()) : selection.definitionRef(key),
              binding));
    }
    var normalized =
        new PlanCandidateDraft(
            PlanCandidateDraft.PROTOCOL_VERSION,
            List.copyOf(normalizedNodes),
            draft.assumptions().stream().map(String::strip).distinct().toList(),
            draft.openQuestions().stream().map(String::strip).distinct().toList());
    return new Result(true, List.of(), normalized, sha(normalized, input, resolved), List.copyOf(resolved));
  }

  private void validateDependencies(
      Map<String, PlanCandidateDraft.Node> byKey, List<String> errors) {
    for (var node : byKey.values()) {
      if (new HashSet<>(node.dependsOn()).size() != node.dependsOn().size())
        errors.add(node.key() + "：依赖不能重复。");
      for (String dependency : node.dependsOn()) {
        if (dependency == null || dependency.isBlank()) {
          errors.add(node.key() + "：依赖键为空。");
          continue;
        }
        if (dependency.equals(node.key())) errors.add(node.key() + "：节点不能依赖自身。");
        else if (!byKey.containsKey(dependency)) errors.add(node.key() + "：依赖节点不存在：" + dependency);
      }
    }
  }

  /** 稳定拓扑顺序：同层按模型给出的先后排列，保证同一候选每次得到相同顺序。 */
  private static List<String> topological(
      Map<String, PlanCandidateDraft.Node> byKey, List<String> errors) {
    var indegree = new LinkedHashMap<String, Integer>();
    var dependents = new LinkedHashMap<String, List<String>>();
    for (String key : byKey.keySet()) {
      indegree.put(key, 0);
      dependents.put(key, new ArrayList<>());
    }
    for (var node : byKey.values()) {
      for (String dependency : node.dependsOn()) {
        if (!byKey.containsKey(dependency) || dependency.equals(node.key())) continue;
        indegree.merge(node.key(), 1, Integer::sum);
        dependents.get(dependency).add(node.key());
      }
    }
    var ready = new ArrayList<String>();
    for (String key : byKey.keySet()) if (indegree.get(key) == 0) ready.add(key);
    var order = new ArrayList<String>();
    while (!ready.isEmpty()) {
      String key = ready.removeFirst();
      order.add(key);
      for (String dependent : dependents.get(key)) {
        if (indegree.merge(dependent, -1, Integer::sum) == 0) ready.add(dependent);
      }
    }
    if (order.size() != byKey.size()) errors.add("候选计划依赖存在环。");
    return order;
  }

  private void validateFiles(
      Map<String, PlanCandidateDraft.Node> byKey, PlanCandidateInput input, List<String> errors) {
    var authorized = new LinkedHashSet<>(input.authorizedFiles());
    var writers = new LinkedHashMap<String, String>();
    for (var node : byKey.values()) {
      var read = normalized(node.key(), "读取文件", node.readFiles(), errors);
      var write = normalized(node.key(), "写入文件", node.writeFiles(), errors);
      for (String path : read)
        if (!authorized.contains(path)) errors.add(node.key() + "：读取文件不在任务授权范围：" + path);
      for (String path : write) {
        if (!read.contains(path)) errors.add(node.key() + "：写入文件必须同时属于该节点读取范围：" + path);
        String previous = writers.putIfAbsent(path, node.key());
        if (previous != null)
          errors.add(node.key() + "：写入文件与其他写节点重叠：" + path + "（已由 " + previous + " 写入）。");
      }
      if (node.role() == PlanCandidateDraft.Role.REVIEW && (!read.isEmpty() || !write.isEmpty()))
        errors.add(node.key() + "：审查节点不能申请文件读写。");
      if (node.role() == PlanCandidateDraft.Role.ANALYZE && !write.isEmpty())
        errors.add(node.key() + "：分析节点不能写入文件。");
      if (node.role() == PlanCandidateDraft.Role.IMPLEMENT && write.isEmpty())
        errors.add(node.key() + "：开发节点必须至少写入一个授权文件。");
    }
  }

  private void validateRolesAndOutputs(
      Map<String, PlanCandidateDraft.Node> byKey, List<String> errors, Selection selection) {
    for (var node : byKey.values()) {
      if (node.role() == null) continue;
      var reference = selection.refFor(node);
      AgentDefinition definition;
      try {
        definition = definitions.resolve(reference);
      } catch (IllegalArgumentException e) {
        errors.add(node.key() + "：受信 Agent 定义不可用：" + reference);
        continue;
      }
      if (definition.permissionProfile() != expectedProfile(node.role()))
        errors.add(node.key() + "：角色映射的 Agent 定义权限不一致。");
      boolean writes = definition.tools().contains(ToolKind.WORKSPACE_WRITE);
      if (node.role() == PlanCandidateDraft.Role.IMPLEMENT && !writes)
        errors.add(node.key() + "：开发角色映射的定义没有写入工具。");
      if (node.role() != PlanCandidateDraft.Role.IMPLEMENT && writes)
        errors.add(node.key() + "：分析或审查角色映射的定义不能包含写入工具。");

      if (node.expectedOutputs().isEmpty()) {
        errors.add(node.key() + "：至少需要一个预期产出。");
        continue;
      }
      if (node.expectedOutputs().size() > MAX_OUTPUTS)
        errors.add(node.key() + "：预期产出最多 " + MAX_OUTPUTS + " 项。");
      var seen = new HashSet<String>();
      var outputFiles = new HashSet<String>();
      boolean requiredFile = false;
      boolean requiredText = false;
      List<String> writePaths;
      try {
        writePaths = node.writeFiles().stream().map(AuthorizedFileSet::normalize).toList();
      } catch (IllegalArgumentException e) {
        // 写入路径本身的错误已在文件校验中报告，这里不再重复制造异常。
        writePaths = List.of();
      }
      for (var output : node.expectedOutputs()) {
        if (output == null || output.kind() == null) {
          errors.add(node.key() + "：预期产出缺少类型。");
          continue;
        }
        if (output.kind() == PlanCandidateDraft.OutputKind.TEXT) {
          if (output.path() != null && !output.path().isBlank())
            errors.add(node.key() + "：文本产出不能带文件路径。");
          if (!seen.add("TEXT")) errors.add(node.key() + "：文本产出重复。");
          if (output.required()) requiredText = true;
          continue;
        }
        String path;
        try {
          path = AuthorizedFileSet.normalize(output.path());
        } catch (IllegalArgumentException e) {
          errors.add(node.key() + "：文件产出路径无效。");
          continue;
        }
        if (path == null || !writePaths.contains(path))
          errors.add(node.key() + "：文件产出必须属于该节点写入范围：" + path);
        if (path != null && !seen.add("FILE:" + path)) errors.add(node.key() + "：文件产出重复：" + path);
        if (path != null) outputFiles.add(path);
        if (output.required()) requiredFile = true;
      }
      if (node.role() == PlanCandidateDraft.Role.IMPLEMENT) {
        // 必需文件必须以 required=true 明确声明，避免把可选产出误当成可交付成果。
        if (!requiredFile) errors.add(node.key() + "：开发节点必须声明至少一个必需的文件产出。");
        for (String path : writePaths)
          if (!outputFiles.contains(path)) errors.add(node.key() + "：写入文件缺少对应的预期产出：" + path);
      } else if (!requiredText) {
        errors.add(node.key() + "：分析或审查节点必须声明至少一个必需的文本产出。");
      }
    }
  }

  /** 每个写节点都必须有直接审查；且至少一个最终审查节点直接依赖全部写节点。 */
  private static void validateReviewCoverage(
      Map<String, PlanCandidateDraft.Node> byKey, List<String> errors, boolean readOnly) {
    var writers =
        byKey.values().stream()
            .filter(node -> node.role() == PlanCandidateDraft.Role.IMPLEMENT)
            .map(PlanCandidateDraft.Node::key)
            .toList();
    if (readOnly) {
      // 只读计划不要求开发与审查闭环，也不能包含写入节点。
      if (!writers.isEmpty()) errors.add("只读计划不能包含写入节点。");
      return;
    }
    if (writers.isEmpty()) {
      errors.add("候选计划至少需要一个开发节点。");
      return;
    }
    var readers =
        byKey.values().stream()
            .filter(node -> node.role() == PlanCandidateDraft.Role.REVIEW)
            .toList();
    for (String writer : writers) {
      boolean covered = readers.stream().anyMatch(review -> review.dependsOn().contains(writer));
      if (!covered) errors.add(writer + "：没有直接依赖它的独立审查节点。");
    }
    boolean finalReview =
        readers.stream().anyMatch(review -> review.dependsOn().containsAll(writers));
    if (!finalReview) errors.add("缺少直接依赖全部开发节点的最终审查节点。");
  }

  private static List<String> normalized(
      String key, String field, List<String> paths, List<String> errors) {
    var result = new ArrayList<String>();
    for (String raw : paths) {
      try {
        result.add(AuthorizedFileSet.normalize(raw));
      } catch (IllegalArgumentException e) {
        errors.add(key + "：" + field + "路径无效：" + raw);
      }
    }
    if (new HashSet<>(result).size() != result.size()) errors.add(key + "：" + field + "不能重复。");
    return List.copyOf(result);
  }

  private static void limit(List<String> errors, List<String> items, String name) {
    if (items.size() > MAX_ACCEPTANCE) errors.add(name + "最多 " + MAX_ACCEPTANCE + " 条。");
    for (String item : items)
      if (item == null || item.isBlank() || item.strip().length() > MAX_ITEM_LENGTH)
        errors.add(name + "条目为空或超过 " + MAX_ITEM_LENGTH + " 字。");
  }

  private static AgentDefinitionRef definitionFor(PlanCandidateDraft.Role role) {
    return switch (role) {
      case ANALYZE -> new AgentDefinitionRef("workspace-analysis", 1);
      case IMPLEMENT -> new AgentDefinitionRef("workspace-edit", 1);
      case REVIEW -> new AgentDefinitionRef("workspace-change-review", 1);
    };
  }

  private static AgentRole expectedProfile(PlanCandidateDraft.Role role) {
    return switch (role) {
      case ANALYZE -> AgentRole.ANALYST;
      case IMPLEMENT -> AgentRole.CODER;
      case REVIEW -> AgentRole.REVIEWER;
    };
  }

  /**
   * 候选摘要覆盖协议、输入快照、排序后的节点、依赖、文件、产出、验收条件及角色映射的定义版本。
   *
   * <p>定义摘要包含技能内容，因此同名定义的技能变化也会使旧候选过期。
   */
  /** 选择记录：模型只提出短引用，服务端解析并校验归属、发布状态和节点能力。 */
  private record Selection(
      Map<String, AgentDefinitionRef> refs, Map<String, AgentBinding> bindings) {
    AgentDefinitionRef refFor(PlanCandidateDraft.Node node) {
      return refs.getOrDefault(node.key(), definitionFor(node.role()));
    }

    AgentDefinitionRef definitionRef(String key) {
      return refs.get(key);
    }
  }

  private Selection selectAgents(
      Map<String, PlanCandidateDraft.Node> byKey,
      PlanCandidateInput input,
      String ownerScopeKey,
      PlanningAgentSnapshot planning,
      List<String> errors) {
    var refs = new LinkedHashMap<String, AgentDefinitionRef>();
    var bindings = new LinkedHashMap<String, AgentBinding>();
    if (planning != null) errors.addAll(planning.errors());
    if ("BUILTIN".equals(input.agentMode())) {
      for (var node : byKey.values())
        if (node.agentRef() != null)
          errors.add(node.key() + "：当前任务只允许内置模板，模型不能自行选择私有 Agent。");
      return new Selection(refs, bindings);
    }
    var privateAgents = this.privateAgents;
    if (privateAgents == null) {
      errors.add("私有 Agent 目录不可用，请重新选择。");
      return new Selection(refs, bindings);
    }
    if ("MANUAL".equals(input.agentMode())) {
      AgentDefinitionRef manual;
      String pinnedName;
      String pinnedSpec;
      try {
        if (planning != null) {
          var choice = planning.manual();
          if (choice == null) return new Selection(refs, bindings);
          manual = new AgentDefinitionRef(choice.id(), choice.version());
          pinnedName = choice.name();
          pinnedSpec = choice.specSha256();
        } else {
          manual =
              privateAgents.manualReference(
                  ownerScopeKey, input.manualAgentId(), input.manualAgentVersion());
          var version = privateAgents.version(manual);
          pinnedName = version.getName();
          pinnedSpec = version.getSpecSha256();
        }
      } catch (RuntimeException e) {
        errors.add(e.getMessage());
        return new Selection(refs, bindings);
      }
      AgentRole expected = AgentRole.valueOf(privateAgents.version(manual).getPermissionRole());
      PlanCandidateDraft.Node target = null;
      for (var node : byKey.values()) {
        if (expected == AgentRole.ANALYST
            && node.role() == PlanCandidateDraft.Role.ANALYZE
            && node.writeFiles().isEmpty()) {
          target = node;
          break;
        }
        if (expected == AgentRole.REVIEWER
            && node.role() == PlanCandidateDraft.Role.REVIEW
            && node.readFiles().isEmpty()) {
          target = node;
          break;
        }
      }
      if (target == null) {
        errors.add("计划中没有与指定私有 Agent 权限类别匹配的节点，请重新选择。");
        return new Selection(refs, bindings);
      }
      if (target.agentRef() != null)
        errors.add(target.key() + "：模型不能覆盖用户指定的私有 Agent。");
      acceptPrivate(
          target,
          manual,
          "MANUAL",
          "用户在创建计划时指定私有 Agent「" + pinnedName + "」v" + manual.version() + "。",
          pinnedSpec,
          ownerScopeKey,
          privateAgents,
          refs,
          bindings,
          errors);
      return new Selection(refs, bindings);
    }
    if (planning != null) {
      for (var node : byKey.values()) {
        if (node.agentRef() == null) continue;
        var alias = planning.aliases().get(node.agentRef());
        if (alias == null) {
          errors.add(node.key() + "：模型选择的 Agent 引用不在发起规划时的候选中。");
          continue;
        }
        acceptPrivate(
            node,
            new AgentDefinitionRef(alias.id(), alias.version()),
            "RECOMMENDED",
            "规划器从本次勾选的候选 Agent 中推荐「" + alias.name() + "」v" + alias.version() + "。",
            alias.specSha256(),
            ownerScopeKey,
            privateAgents,
            refs,
            bindings,
            errors);
      }
      return new Selection(refs, bindings);
    }
    List<PrivateAgentCatalog.Recommendation> recommendations;
    try {
      recommendations =
          privateAgents.recommendations(ownerScopeKey, input.candidateAgentIds());
    } catch (RuntimeException e) {
      errors.add(e.getMessage());
      return new Selection(refs, bindings);
    }
    if (recommendations.isEmpty()) {
      errors.add("本次勾选的私有 Agent 没有可用的已发布推荐版本，请重新选择。");
      return new Selection(refs, bindings);
    }
    var shortRefs = new LinkedHashMap<String, PrivateAgentCatalog.Recommendation>();
    for (int index = 0; index < recommendations.size(); index++)
      shortRefs.put("P" + (index + 1), recommendations.get(index));
    for (var node : byKey.values()) {
      if (node.agentRef() == null) continue;
      var recommendation = shortRefs.get(node.agentRef());
      if (recommendation == null) {
        errors.add(node.key() + "：模型选择的 Agent 引用不在本次勾选的候选中。");
        continue;
      }
      acceptPrivate(
          node,
          recommendation.ref(),
          "RECOMMENDED",
          "规划器从本次勾选的候选 Agent 中推荐「" + recommendation.name() + "」v"
              + recommendation.ref().version() + "。",
          recommendation.specSha256(),
          ownerScopeKey,
          privateAgents,
          refs,
          bindings,
          errors);
    }
    return new Selection(refs, bindings);
  }

  private void acceptPrivate(
      PlanCandidateDraft.Node node,
      AgentDefinitionRef ref,
      String source,
      String reason,
      String expectedSpecSha256,
      String ownerScopeKey,
      PrivateAgentCatalog privateAgents,
      Map<String, AgentDefinitionRef> refs,
      Map<String, AgentBinding> bindings,
      List<String> errors) {
    AgentRole expected = expectedProfile(node.role());
    boolean reads = !node.readFiles().isEmpty();
    try {
      privateAgents.requirePlanUse(ownerScopeKey, ref, expected, reads, node.readFiles());
      var version = privateAgents.version(ref);
      if (expectedSpecSha256 != null && !expectedSpecSha256.equals(version.getSpecSha256()))
        throw new IllegalStateException("私有 Agent 固定版本的摘要与规划时不一致，请重新选择。");
      var components = privateAgents.components(version);
      var skills =
          components.skills().stream()
              .map(
                  skill ->
                      new BindingComponent(
                          skill.id(), skill.version(), skill.name(), "SKILL", skill.sha256()))
              .toList();
      var tools =
          components.tools().stream()
              .map(
                  tool ->
                      new BindingComponent(
                          tool.id(),
                          tool.version(),
                          tool.name(),
                          tool.executorKind(),
                          tool.sha256()))
              .toList();
      refs.put(node.key(), ref);
      bindings.put(
          node.key(),
          new AgentBinding(
              ref.id(),
              ref.version(),
              source,
              reason,
              version.getName(),
              version.getSpecSha256(),
              version.getModelStrategy(),
              version.getModelMode(),
              skills,
              tools));
    } catch (RuntimeException e) {
      errors.add(node.key() + "：" + e.getMessage());
    }
  }

  private String sha(
      PlanCandidateDraft normalized, PlanCandidateInput input, List<ResolvedNode> resolved) {
    var digest =
        new CanonicalDigest("plan-candidate-v1")
            .field(normalized.schemaVersion())
            .field(input.sha256())
            .field(normalized.nodes().size());
    var bindings = new LinkedHashMap<String, AgentBinding>();
    for (var item : resolved) if (item.binding() != null) bindings.put(item.node().key(), item.binding());
    for (var node : normalized.nodes()) {
      var reference = definitionFor(node.role());
      var binding = bindings.get(node.key());
      digest
          .field(node.key())
          .field(node.title())
          .field(node.role().name())
          .field(node.objective())
          .field(binding == null ? reference.id() : binding.definitionId())
          .field(binding == null ? reference.version() : binding.definitionVersion())
          .field(binding == null ? definitions.fingerprint(reference) : binding.specSha256())
          .field(binding == null ? "" : binding.source())
          .field(binding == null ? "" : binding.reason())
          .field(binding == null ? "INHERIT_TASK" : binding.modelStrategy())
          .field(binding == null ? "" : binding.modelMode())
          .field(binding == null ? "" : Integer.toString(binding.skills().size()));
      if (binding != null)
        for (var skill : binding.skills())
          digest.field(skill.id()).field(skill.version()).field(skill.sha256());
      digest.field(binding == null ? "" : Integer.toString(binding.tools().size()));
      if (binding != null)
        for (var tool : binding.tools())
          digest.field(tool.id()).field(tool.version()).field(tool.sha256());
      digest.field(node.dependsOn().size());
      for (String dependency : node.dependsOn()) digest.field(dependency);
      digest.field(node.readFiles().size());
      for (String path : node.readFiles()) digest.field(path);
      digest.field(node.writeFiles().size());
      for (String path : node.writeFiles()) digest.field(path);
      digest.field(node.expectedOutputs().size());
      for (var output : node.expectedOutputs())
        digest
            .field(output.kind().name())
            .field(output.path() == null ? "" : output.path())
            .field(output.required() ? "required" : "optional");
      digest.field(node.acceptanceCriteria().size());
      for (String item : node.acceptanceCriteria()) digest.field(item);
    }
    digest.field(normalized.assumptions().size());
    for (String item : normalized.assumptions()) digest.field(item);
    digest.field(normalized.openQuestions().size());
    for (String item : normalized.openQuestions()) digest.field(item);
    return digest.hex();
  }
}
