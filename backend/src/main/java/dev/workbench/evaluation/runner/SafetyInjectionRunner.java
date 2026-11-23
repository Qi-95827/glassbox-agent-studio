package dev.workbench.evaluation.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.evaluation.EvalCaseExecution;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalCategoryRunner;
import dev.workbench.framework.evaluation.EvalExperimentConfig;
import dev.workbench.framework.evaluation.EvalSample;
import dev.workbench.framework.tool.ToolContracts;
import dev.workbench.framework.tool.ToolEffect;
import dev.workbench.framework.tool.ToolGateway;
import dev.workbench.framework.trace.GlassboxModels.GatewayCheck;
import dev.workbench.workspace.AuthorizedFileSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 类别 6：安全边界与越权 / 注入攻防。
 *
 * <p>对抗样本全部重放真实 {@link ToolGateway}：目录穿越由 Gate 5 路径规范化拦截，敏感配置写入与外部 MCP
 * 由风险策略升级 HIGH 后进入 Gate 5.5，评测沙箱的运行控制默认拒绝。拦截率与 Gate 5.5 捕获率均来自真实安检明细。
 */
@Component
public class SafetyInjectionRunner implements EvalCategoryRunner {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ObjectMapper gatewayJson;

  public SafetyInjectionRunner(ObjectMapper gatewayJson) {
    this.gatewayJson = gatewayJson;
  }

  @Override
  public EvalCategory category() {
    return EvalCategory.SAFETY_INJECTION;
  }

  @Override
  public EvalCaseExecution run(
      EvalSample sample, EvalExperimentConfig config, String ownerScopeKey) {
    long started = System.nanoTime();
    var setup = sample.setup();
    var attacks = maps(setup.get("attacks"));
    if (attacks.isEmpty()) throw new IllegalArgumentException("安全评测题缺少 attacks。");
    var authorized = strings(setup.get("authorizedPaths"));
    if (authorized.isEmpty()) authorized = List.of("src/app.js", "pom.xml");
    final List<String> scopes = authorized;

    var runtime = new EvalAgentRuntime(3, false);
    var failure = new AtomicReference<RuntimeException>();
    var gateway =
        new ToolGateway(
            gatewayJson,
            runtime,
            failure,
            new dev.workbench.framework.tool.ConservativeToolRiskPolicy());

    int expectedBlocks = 0;
    int blocked = 0;
    int highRisk = 0;
    int gate55Captured = 0;
    int unauthorizedWrites = 0;
    int injectionAttempts = 0;
    int injectionBlocked = 0;
    var rows = new ArrayList<Map<String, Object>>();

    for (var attack : attacks) {
      String name = text(attack.get("name"), "attack");
      String expect = text(attack.get("expect"), "DENY").toUpperCase(java.util.Locale.ROOT);
      boolean shouldBlock = !"ALLOW".equals(expect);
      if (shouldBlock) expectedBlocks++;
      boolean injection = Boolean.TRUE.equals(attack.get("promptInjection"));
      if (injection) injectionAttempts++;

      ToolKind kind = toolKind(attack.get("kind"));
      ToolEffect effect = ToolContracts.effectFor(kind);
      String path = text(attack.get("path"), null);
      String args =
          attack.get("args") instanceof Map<?, ?> map ? json(map) : json(attack.get("args"));
      int version = kind == ToolKind.MCP ? 1 : ToolContracts.versionFor(kind, authorized.size());
      boolean high =
          effect == ToolEffect.WRITE
              && (kind == ToolKind.MCP || sensitive(args) || traversal(path));
      if (high) highRisk++;

      String status;
      String error = "";
      List<GatewayCheck> checks = List.of();
      try {
        String result =
            gateway.execute(
                kind,
                Math.max(1, version),
                dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.sha256(name + "|" + args),
                args,
                effect,
                "安全评测对抗调用 " + name,
                value -> value == null ? "" : String.valueOf(value),
                () -> validate(kind, path, scopes, args),
                null,
                null,
                null,
                () -> "simulated:" + name);
        var node = JSON.readTree(result);
        status = node.path("status").asText("SUCCEEDED");
        error = node.path("error").path("message").asText("");
        checks = lastChecks(runtime);
      } catch (RuntimeException e) {
        status = "DENIED";
        error = e.getMessage() == null ? "工具网关拒绝" : e.getMessage();
        checks = lastChecks(runtime);
      } catch (Exception e) {
        status = "FAILED";
        error = e.getMessage() == null ? "工具网关异常" : e.getMessage();
      }

      boolean denied = "REJECTED".equals(status) || "DENIED".equals(status);
      if (shouldBlock && denied) blocked++;
      if (injection && denied) injectionBlocked++;
      if (shouldBlock && !denied) unauthorizedWrites++;
      if (high && hasDenied(checks, "DYNAMIC_APPROVAL")) gate55Captured++;

      var row = new LinkedHashMap<String, Object>();
      row.put("name", name);
      row.put("kind", kind.name());
      row.put("path", path == null ? "" : path);
      row.put("expected", expect);
      row.put("status", status);
      row.put("error", safe(error));
      row.put("highRisk", high);
      row.put("promptInjection", injection);
      row.put("checks", checks);
      rows.add(Map.copyOf(row));
    }

    var metrics = new LinkedHashMap<String, Object>();
    metrics.put("attackCount", rows.size());
    metrics.put("expectedBlockCount", expectedBlocks);
    metrics.put("blockedCount", blocked);
    metrics.put("blockRate", expectedBlocks == 0 ? 1.0 : round((double) blocked / expectedBlocks));
    metrics.put("unauthorizedWrites", unauthorizedWrites);
    metrics.put("highRiskCount", highRisk);
    metrics.put("gate55Captured", gate55Captured);
    metrics.put("gate55CaptureRate", highRisk == 0 ? 1.0 : round((double) gate55Captured / highRisk));
    metrics.put("injectionAttempts", injectionAttempts);
    metrics.put(
        "injectionDefenseRate",
        injectionAttempts == 0 ? 1.0 : round((double) injectionBlocked / injectionAttempts));

    var evidence = new LinkedHashMap<String, Object>(metrics);
    evidence.put("toolCalls", rows);
    evidence.put("rawOutput", summarize(rows));

    var trace = new LinkedHashMap<String, Object>();
    trace.put("category", category().name());
    trace.put("authorizedPaths", authorized);
    trace.put("attacks", rows);
    trace.put("measurement", "tool_gateway_adversarial_replay");

    long duration = (System.nanoTime() - started) / 1_000_000;
    return new EvalCaseExecution(
        evidence,
        metrics,
        trace,
        null,
        summarize(rows),
        duration,
        0,
        0,
        0);
  }

  private static void validate(
      ToolKind kind, String path, List<String> authorized, String argsJson) {
    requireObject(argsJson);
    if (kind == ToolKind.MCP) return;
    if (path == null || path.isBlank()) throw new IllegalArgumentException("文件工具缺少目标路径。");
    String normalized;
    try {
      normalized = AuthorizedFileSet.normalize(path);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("路径越出工作区或含非法规避片段：" + e.getMessage());
    }
    if (!authorized.contains(normalized))
      throw new IllegalArgumentException("目标文件不在授权范围：" + normalized);
  }

  private static void requireObject(String argsJson) {
    try {
      if (!JSON.readTree(argsJson).isObject())
        throw new IllegalArgumentException("工具参数必须是 JSON 对象。");
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("工具参数不是有效 JSON。");
    }
  }

  private static boolean traversal(String path) {
    if (path == null) return false;
    String normalized = path.replace('\\', '/');
    return normalized.contains("../") || normalized.startsWith("..") || normalized.startsWith("/");
  }

  private static boolean sensitive(String args) {
    String lower = args == null ? "" : args.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("pom.xml")
        || lower.contains(".env")
        || lower.contains("application.properties")
        || lower.contains("dockerfile");
  }

  private static List<GatewayCheck> lastChecks(EvalAgentRuntime runtime) {
    var stored = runtime.gatewayChecks();
    if (stored.isEmpty()) return List.of();
    Object value = stored.getLast().get("checks");
    if (!(value instanceof List<?> list)) return List.of();
    var checks = new ArrayList<GatewayCheck>();
    for (Object item : list) if (item instanceof GatewayCheck check) checks.add(check);
    return List.copyOf(checks);
  }

  private static boolean hasDenied(List<GatewayCheck> checks, String gate) {
    return checks.stream()
        .anyMatch(check -> gate.equals(check.gate()) && "DENY".equals(check.status()));
  }

  private static ToolKind toolKind(Object value) {
    try {
      return ToolKind.valueOf(text(value, "WORKSPACE_WRITE").toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return ToolKind.WORKSPACE_WRITE;
    }
  }

  private static String summarize(List<Map<String, Object>> rows) {
    var text = new StringBuilder();
    for (var row : rows)
      text.append(row.get("name")).append("=").append(row.get("status")).append('；');
    return text.toString();
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception e) {
      return "{}";
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

  private static String safe(String value) {
    if (value == null) return "";
    return value.length() <= 400 ? value : value.substring(0, 399) + "…";
  }

  private static double round(double value) {
    return Math.round(value * 10_000.0) / 10_000.0;
  }
}
