package dev.workbench.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.persistence.entity.EntryRouteEntity;
import dev.workbench.persistence.repository.EntryRouteRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;
import dev.workbench.domain.TaskEntryKind;

/**
 * 任务 8 的规则路由。
 *
 * <p>规则只从已校验的任务入口、工作区授权和写入声明推导执行模板，不扩大任何权限。显式模板可以覆盖推荐，但决策记录会保留覆盖原因和候选规则。
 */
@Service
public class EntryRouteService {
  public static final int ROUTE_VERSION = 1;
  public static final String REQUIREMENT_TEMPLATE = "requirement-analysis";
  public static final String DESIGN_TEMPLATE = "design-proposal";
  public static final String CODE_TEMPLATE = "workspace-change";
  public static final String TEST_TEMPLATE = "test-evidence";
  public static final String WORKSPACE_REQUIREMENT_TEMPLATE = "requirement-workspace";
  public static final String WORKSPACE_DESIGN_TEMPLATE = "design-workspace";

  private final EntryRouteRepository routes;
  private final ObjectMapper json;

  public EntryRouteService(EntryRouteRepository routes, ObjectMapper json) {
    this.routes = routes;
    this.json = json;
  }

  public Decision route(
      TaskEntryKind requested,
      String workspaceId,
      boolean writeEnabled,
      List<String> fileScopes,
      String explicitTemplate) {
    return route(requested, workspaceId, writeEnabled, fileScopes, List.of(), explicitTemplate);
  }

  public Decision route(
      TaskEntryKind requested,
      String workspaceId,
      boolean writeEnabled,
      List<String> fileScopes,
      List<String> directoryScopes,
      String explicitTemplate) {
    TaskEntryKind kind = requested == null ? TaskEntryKind.GENERAL : requested;
    List<String> reasons = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    String rule;
    String template;
    int version = 1;
    switch (kind) {
      case REQUIREMENT, DESIGN -> {
        boolean workspace =
            workspaceId != null
                && ((fileScopes != null && !fileScopes.isEmpty())
                    || (directoryScopes != null && !directoryScopes.isEmpty()));
        rule = kind.name() + (workspace ? "_WORKSPACE_RULE" : "_TEXT_RULE");
        boolean writing = workspace && writeEnabled;
        template =
            writing
                ? CODE_TEMPLATE
                : kind == TaskEntryKind.REQUIREMENT
                    ? workspace ? WORKSPACE_REQUIREMENT_TEMPLATE : REQUIREMENT_TEMPLATE
                    : workspace ? WORKSPACE_DESIGN_TEMPLATE : DESIGN_TEMPLATE;
        reasons.add("入口为" + kind.label() + "，产物是可审查的" + kind.label() + "说明。");
        reasons.add(
            writing
                ? "已声明写入，使用授权修改模板；产物仍需通过写入证据和独立审查确认。"
                : workspace
                    ? "已选择工作区和授权文件，使用只读分析节点读取证据后再形成产物。"
                    : "未声明工作区，使用纯文本分析节点；不会声称读取过代码或执行过测试。");
        if (writing)
          warnings.add("需求/设计入口声明写入时沿用授权修改模板；不能据此声称测试或交付已经完成。");
        if (writeEnabled && !workspace)
          warnings.add("声明写入但没有工作区；创建任务会在入口校验阶段拒绝。");
      }
      case CODE -> {
        rule = "CODE_AUTHORIZED_WRITE_RULE";
        template = CODE_TEMPLATE;
        reasons.add("入口为代码，要求工作区、显式写入和至少一个现有文件。");
        reasons.add("计划必须先分析授权文件，再由写入节点修改，并由独立审查节点核对证据。");
      }
      case TEST -> {
        rule = "TEST_READ_ONLY_RULE";
        template = TEST_TEMPLATE;
        reasons.add("入口为测试，只读取授权文件并形成测试计划与证据说明。");
        reasons.add("没有实际执行记录的检查不会被表述为通过。");
      }
      case BUG -> {
        rule = "BUG_TEMPLATE_RULE";
        template = explicitTemplate;
        reasons.add("入口为模拟 Bug，必须绑定 Bug 和工作区修复授权。");
        if (explicitTemplate == null)
          warnings.add("未选择 Bug 修复模板时沿用通用执行；如需复现、修复和回归图，请选择 bug-fix。");
      }
      default -> {
        rule = "GENERAL_EXPLICIT_RULE";
        template = explicitTemplate;
        reasons.add("通用入口不自动改变执行模板。");
      }
    }
    if (explicitTemplate != null && explicitTemplate.equals(template)) {
      rule = rule + "_EXPLICIT_MATCH";
      reasons.add("用户显式选择的模板与规则推荐一致。");
    } else if (explicitTemplate != null) {
      warnings.add("用户显式模板 " + explicitTemplate + " 覆盖规则推荐的 " + template + "。");
      template = explicitTemplate;
    }
    List<Candidate> candidates =
        switch (kind) {
          case REQUIREMENT ->
              List.of(
                  new Candidate(REQUIREMENT_TEMPLATE, "纯文本需求分析"),
                  new Candidate(WORKSPACE_REQUIREMENT_TEMPLATE, "只读工作区需求分析"));
          case DESIGN ->
              List.of(
                  new Candidate(DESIGN_TEMPLATE, "纯文本文档设计"),
                  new Candidate(WORKSPACE_DESIGN_TEMPLATE, "只读工作区设计核对"));
          case CODE -> List.of(new Candidate(CODE_TEMPLATE, "授权写入与独立审查"));
          case TEST -> List.of(new Candidate(TEST_TEMPLATE, "测试证据与覆盖说明"));
          case BUG -> List.of(new Candidate("bug-fix", "模拟 Bug 复现、修复与回归"));
          default -> List.of(new Candidate(null, "沿用调用方选择或通用执行"));
        };
    return new Decision(
        ROUTE_VERSION, rule, kind.name(), kind.name(), template, template == null ? null : version,
        reasons, warnings, candidates);
  }

  /** 保存实际生效的规则、模板和解释；与任务创建同一事务。 */
  public void save(String taskId, Decision decision) {
    try {
      String explanation =
          json.writeValueAsString(
              new java.util.LinkedHashMap<String, Object>() {
                {
                  put("rule", decision.ruleCode());
                  put("version", decision.version());
                  put("requestedKind", decision.requestedKind());
                  put("routedKind", decision.routedKind());
                  put("reasons", decision.reasons());
                  put("warnings", decision.warnings());
                  put("candidates", decision.candidates());
                  put("templateId", decision.templateId());
                }
              });
      routes.save(
          new EntryRouteEntity(
              taskId,
              decision.ruleCode(),
              decision.requestedKind(),
              decision.routedKind(),
              decision.templateId(),
              decision.templateVersion(),
              explanation,
              sha(explanation),
              Instant.now().toString()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("入口路由说明无法保存。", e);
    }
  }

  public java.util.Map<String, Object> find(String taskId) {
    var row = routes.findById(taskId).orElse(null);
    if (row == null) return null;
    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("TASK_ID", row.getTaskId());
    result.put("RULE_CODE", row.getRuleCode());
    result.put("REQUESTED_KIND", row.getRequestedKind());
    result.put("ROUTED_KIND", row.getRoutedKind());
    result.put("TEMPLATE_ID", row.getTemplateId());
    result.put("TEMPLATE_VERSION", row.getTemplateVersion());
    result.put("EXPLANATION_JSON", row.getExplanationJson());
    result.put("DECISION_SHA256", row.getDecisionSha256());
    result.put("CREATED_AT", row.getCreatedAt());
    return result;
  }

  private static String sha(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public record Candidate(String templateId, String label) {}

  public record Decision(
      int version,
      String ruleCode,
      String requestedKind,
      String routedKind,
      String templateId,
      Integer templateVersion,
      List<String> reasons,
      List<String> warnings,
      List<Candidate> candidates) {
    public Decision {
      reasons = List.copyOf(reasons);
      warnings = List.copyOf(warnings);
      candidates = List.copyOf(candidates);
    }
  }
}
