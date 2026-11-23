package dev.workbench.evaluation.runner;

import dev.workbench.domain.TaskEntryKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.evaluation.EvalCaseExecution;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalCategoryRunner;
import dev.workbench.framework.evaluation.EvalExperimentConfig;
import dev.workbench.framework.evaluation.EvalSample;
import dev.workbench.framework.trace.GlassboxModels.StructuredHandoff;
import dev.workbench.framework.trace.StructuredHandoffExtractor;
import dev.workbench.framework.workflow.plan.RevisionEdge;
import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import dev.workbench.planning.PlanCandidateDraft;
import dev.workbench.planning.PlanCandidateInput;
import dev.workbench.planning.PlanCandidateValidator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 类别 5：多 Agent 规划与交接协作。
 *
 * <p>候选 DAG 由真实 {@link PlanCandidateValidator} 校验（节点键、环、写集互斥、文件子集、Agent 定义与反思回边），
 * 并以真实 {@link StructuredHandoffExtractor} 解析交接信封，计算校验一次通过率、写集零冲突与信封 PASS 率。
 */
@Component
public class PlanningMultiAgentRunner implements EvalCategoryRunner {
  private final PlanCandidateValidator validator;

  public PlanningMultiAgentRunner(PlanCandidateValidator validator) {
    this.validator = validator;
  }

  @Override
  public EvalCategory category() {
    return EvalCategory.PLANNING_MULTI_AGENT;
  }

  @Override
  public EvalCaseExecution run(
      EvalSample sample, EvalExperimentConfig config, String ownerScopeKey) {
    long started = System.nanoTime();
    var setup = sample.setup();
    var files = strings(setup.get("fileScopes"));
    if (files.isEmpty()) files = List.of("src/app.js");
    var plans = maps(setup.get("plans"));
    if (plans.isEmpty()) {
      var single = new LinkedHashMap<String, Object>();
      single.put("label", "candidate");
      single.put("nodes", setup.get("nodes"));
      single.put("revisionEdges", setup.get("revisionEdges"));
      plans = List.<Map<String, Object>>of(Map.copyOf(single));
    }
    var acceptance = strings(setup.get("acceptanceCriteria"));
    if (acceptance.isEmpty()) acceptance = List.of("交付物可核对且测试通过");
    var input =
        new PlanCandidateInput(
            sample.prompt(),
            TaskEntryKind.CODE,
            null,
            true,
            files,
            strings(setup.get("constraints")),
            acceptance,
            null,
            "BUILTIN",
            null,
            null,
            List.of(),
            false);

    int validCount = 0;
    int writeConflicts = 0;
    int totalNodes = 0;
    int parallelWidth = 0;
    var planResults = new ArrayList<Map<String, Object>>();
    for (var plan : plans) {
      var draft = draft(plan);
      var result = validator.validate(draft, input, ownerScopeKey);
      if (result.valid()) validCount++;
      writeConflicts +=
          (int)
              result.errors().stream()
                  .filter(error -> error.contains("写入文件与其他写节点重叠"))
                  .count();
      totalNodes += draft.nodes().size();
      if (result.valid()) parallelWidth = Math.max(parallelWidth, width(draft));
      var row = new LinkedHashMap<String, Object>();
      row.put("label", text(plan.get("label"), "candidate"));
      row.put("valid", result.valid());
      row.put("errors", result.errors());
      row.put("nodeCount", draft.nodes().size());
      row.put("normalizedNodes", result.nodes().size());
      row.put("candidateSha256", result.candidateSha256());
      planResults.add(Map.copyOf(row));
    }

    double handoffPassRate = 0;
    StructuredHandoff envelope = null;
    String handoffText = text(setup.get("handoffText"), "");
    if (!handoffText.isBlank()) {
      envelope = StructuredHandoffExtractor.extract(handoff(handoffText));
      handoffPassRate =
          envelope.validationChecks().stream().noneMatch(check -> "DENY".equals(check.status()))
              ? 1.0
              : 0.0;
    } else {
      handoffPassRate = 1.0;
    }

    boolean planValid = plans.size() == validCount;
    var metrics = new LinkedHashMap<String, Object>();
    metrics.put("planCount", plans.size());
    metrics.put("validCount", validCount);
    metrics.put("validationFirstPassRate", round((double) validCount / plans.size()));
    metrics.put("writeConflicts", writeConflicts);
    metrics.put("handoffPassRate", round(handoffPassRate));
    metrics.put("parallelNodeWidth", parallelWidth);
    metrics.put("totalNodes", totalNodes);
    metrics.put("planValid", planValid);

    var evidence = new LinkedHashMap<String, Object>(metrics);
    evidence.put("rawOutput", summarize(planResults));

    var trace = new LinkedHashMap<String, Object>();
    trace.put("category", category().name());
    trace.put("fileScopes", files);
    trace.put("plans", planResults);
    if (envelope != null) trace.put("handoffEnvelope", envelope);
    trace.put("measurement", "plan_candidate_validator_and_handoff_extractor");

    long duration = (System.nanoTime() - started) / 1_000_000;
    return new EvalCaseExecution(
        evidence,
        metrics,
        trace,
        null,
        summarize(planResults),
        duration,
        0,
        0,
        0);
  }

  private static WorkflowHandoff handoff(String text) {
    UUID senderAttempt = UUID.randomUUID();
    UUID receiverAttempt = UUID.randomUUID();
    return new WorkflowHandoff(
        2,
        senderAttempt,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        receiverAttempt,
        UUID.randomUUID(),
        UUID.randomUUID(),
        Instant.now(),
        UUID.randomUUID(),
        text,
        List.of(new WorkflowHandoff.ToolEvidence(UUID.randomUUID(), ToolKind.WORKSPACE_READ, 1)));
  }

  @SuppressWarnings("unchecked")
  private static PlanCandidateDraft draft(Map<String, Object> plan) {
    var nodes = new ArrayList<PlanCandidateDraft.Node>();
    Object rawNodes = plan.get("nodes");
    if (rawNodes instanceof List<?> list) {
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> map)) continue;
        var row = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet())
          if (entry.getKey() != null) row.put(String.valueOf(entry.getKey()), entry.getValue());
        nodes.add(node(row));
      }
    }
    var edges = new ArrayList<RevisionEdge>();
    Object rawEdges = plan.get("revisionEdges");
    if (rawEdges instanceof List<?> list) {
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> map)) continue;
        edges.add(
            new RevisionEdge(
                first(map.get("fromReviewerNodeKey"), map.get("from"), map.get("reviewer")),
                first(map.get("toTargetNodeKey"), map.get("to"), map.get("target")),
                integer(map.get("maxRevisions"), 2)));
      }
    }
    return new PlanCandidateDraft(
        PlanCandidateDraft.PROTOCOL_VERSION,
        List.copyOf(nodes),
        List.of(),
        List.of(),
        List.copyOf(edges));
  }

  private static PlanCandidateDraft.Node node(Map<String, Object> row) {
    var outputs = new ArrayList<PlanCandidateDraft.ExpectedOutput>();
    Object rawOutputs = row.get("expectedOutputs");
    if (rawOutputs instanceof List<?> list) {
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> map)) continue;
        String kind = text(map.get("kind"), "TEXT").toUpperCase(java.util.Locale.ROOT);
        outputs.add(
            new PlanCandidateDraft.ExpectedOutput(
                "FILE".equals(kind) ? PlanCandidateDraft.OutputKind.FILE : PlanCandidateDraft.OutputKind.TEXT,
                text(map.get("path"), null),
                Boolean.TRUE.equals(map.get("required"))));
      }
    }
    return new PlanCandidateDraft.Node(
        text(row.get("key"), "node"),
        text(row.get("title"), "评测节点"),
        role(row.get("role")),
        text(row.get("objective"), "完成评测节点目标"),
        strings(row.get("dependsOn")),
        strings(row.get("readFiles")),
        strings(row.get("writeFiles")),
        List.copyOf(outputs),
        strings(row.get("acceptanceCriteria")));
  }

  private static PlanCandidateDraft.Role role(Object value) {
    try {
      return PlanCandidateDraft.Role.valueOf(
          text(value, "ANALYZE").toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return PlanCandidateDraft.Role.ANALYZE;
    }
  }

  /** 稳定拓扑最大宽度，用于观察可并行节点数。 */
  private static int width(PlanCandidateDraft draft) {
    var indegree = new HashMap<String, Integer>();
    var children = new HashMap<String, List<String>>();
    for (var node : draft.nodes()) {
      indegree.put(node.key(), 0);
      children.put(node.key(), new ArrayList<>());
    }
    for (var node : draft.nodes()) {
      for (String dependency : node.dependsOn()) {
        if (!indegree.containsKey(dependency)) continue;
        indegree.merge(node.key(), 1, Integer::sum);
        children.get(dependency).add(node.key());
      }
    }
    var ready = new ArrayList<String>();
    for (var entry : indegree.entrySet()) if (entry.getValue() == 0) ready.add(entry.getKey());
    int maximum = 0;
    while (!ready.isEmpty()) {
      maximum = Math.max(maximum, ready.size());
      var next = new ArrayList<String>();
      for (String key : ready)
        for (String child : children.get(key))
          if (indegree.merge(child, -1, Integer::sum) == 0) next.add(child);
      ready = next;
    }
    return maximum;
  }

  private static String summarize(List<Map<String, Object>> plans) {
    return plans.stream()
        .map(plan -> plan.get("label") + "=" + plan.get("valid"))
        .reduce((left, right) -> left + "；" + right)
        .orElse("");
  }

  private static String first(Object... values) {
    for (Object value : values) if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
    return "";
  }

  private static int integer(Object value, int fallback) {
    if (value instanceof Number number) return number.intValue();
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    var result = new ArrayList<Map<String, Object>>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        var row = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet())
          if (entry.getKey() != null) row.put(String.valueOf(entry.getKey()), entry.getValue());
        result.add(Map.copyOf(row));
      }
    }
    return List.copyOf(result);
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream()
        .filter(java.util.Objects::nonNull)
        .map(String::valueOf)
        .filter(text -> !text.isBlank())
        .toList();
  }

  private static String text(Object value, String fallback) {
    return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
  }

  private static double round(double value) {
    return Math.round(value * 10_000.0) / 10_000.0;
  }
}
