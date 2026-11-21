package dev.workbench.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.planning.PlanCandidateModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地规则规划器，用于没有模型密钥时的可重复验收。
 *
 * <p>只根据已确认输入生成保守的读写节点和审查节点，不调用外部模型，也不读取私有正文。输出仍要 经过同一套服务端校验；它不能授予工具、文件或审批权限。
 */
@Component
@ConditionalOnProperty(name = "workbench.planning.generator", havingValue = "local")
public class LocalPlanCandidateModel implements PlanCandidateModel {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Override
  public Reply propose(String instructions, String input) {
    try {
      var node = JSON.readTree(input);
      boolean readOnly = node.path("readOnly").asBoolean(false);
      var files = new ArrayList<String>();
      for (var item : node.path("authorizedFiles")) files.add(item.asText());
      var candidates = new ArrayList<JsonNode>();
      for (var item : node.path("candidateAgents")) candidates.add(item);
      var nodes = new ArrayList<Map<String, Object>>();
      if (readOnly) {
        String analyzeRef = candidateRef(candidates, "ANALYST");
        String reviewRef = candidateRef(candidates, "REVIEWER");
        nodes.add(
            node(
                "inspect",
                "授权文件检查",
                "ANALYZE",
                analyzeRef,
                "读取授权文件并列出实际范围、观察和未验证事项。",
                List.of(),
                files,
                List.of(),
                List.of(Map.of("kind", "TEXT", "required", true)),
                List.of("只引用实际的工具结果", "列出未验证事项")));
        nodes.add(
            node(
                "summary",
                "汇总检查结论",
                "REVIEW",
                reviewRef,
                "核对上游检查结论、证据范围和缺失事项。",
                List.of("inspect"),
                List.of(),
                List.of(),
                List.of(Map.of("kind", "TEXT", "required", true)),
                List.of("交接正文包含检查结论")));
      } else {
        String analyzeRef = candidateRef(candidates, "ANALYST");
        if (analyzeRef != null) {
          nodes.add(
              node(
                  "inspect",
                  "需求与现状分析",
                  "ANALYZE",
                  analyzeRef,
                  "结合授权文件分析实现范围和风险。",
                  List.of(),
                  files,
                  List.of(),
                  List.of(Map.of("kind", "TEXT", "required", true)),
                  List.of("说明分析依据和未验证事项")));
        }
        String target = files.isEmpty() ? null : files.getFirst();
        if (target == null) throw new IllegalStateException("本地规划器需要至少一个授权文件。");
        nodes.add(
            node(
                "implement",
                "实现交付",
                "IMPLEMENT",
                null,
                "在授权范围内实现任务目标。",
                analyzeRef == null ? List.of() : List.of("inspect"),
                files,
                List.of(target),
                List.of(Map.of("kind", "FILE", "path", target, "required", true)),
                List.of("只修改授权文件", "保留写入回执")));
        nodes.add(
            node(
                "review",
                "交付审查",
                "REVIEW",
                null,
                "核对实现、文件范围和未验证事项。",
                List.of("implement"),
                List.of(),
                List.of(),
                List.of(Map.of("kind", "TEXT", "required", true)),
                List.of("给出独立审查意见")));
      }
      var plan = new LinkedHashMap<String, Object>();
      plan.put("schemaVersion", 1);
      plan.put("nodes", nodes);
      plan.put("assumptions", List.of("由本地规则规划器生成，未调用真实模型。"));
      plan.put("openQuestions", List.of());
      return new Reply(JSON.writeValueAsString(plan), "local-rule-plan", null, null);
    } catch (Exception e) {
      throw new IllegalStateException("本地规则规划失败。", e);
    }
  }

  private static String candidateRef(List<JsonNode> candidates, String role) {
    for (var candidate : candidates)
      if (role.equals(candidate.path("role").asText())) return candidate.path("ref").asText();
    return null;
  }

  private static Map<String, Object> node(
      String key,
      String title,
      String role,
      String agentRef,
      String objective,
      List<String> dependsOn,
      List<String> readFiles,
      List<String> writeFiles,
      List<Map<String, Object>> outputs,
      List<String> acceptance) {
    var node = new LinkedHashMap<String, Object>();
    node.put("key", key);
    node.put("title", title);
    node.put("role", role);
    node.put("objective", objective);
    node.put("dependsOn", dependsOn);
    node.put("readFiles", readFiles);
    node.put("writeFiles", writeFiles);
    node.put("expectedOutputs", outputs);
    node.put("acceptanceCriteria", acceptance);
    if (agentRef != null) node.put("agentRef", agentRef);
    return node;
  }
}
