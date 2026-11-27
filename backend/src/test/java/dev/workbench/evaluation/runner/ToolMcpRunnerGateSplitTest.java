package dev.workbench.evaluation.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.evaluation.EvalAnswerModel;
import dev.workbench.framework.evaluation.EvalCaseExecution;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalExperimentConfig;
import dev.workbench.framework.evaluation.EvalSample;
import dev.workbench.framework.trace.GlassboxModels.GatewayCheck;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 评测重放的网关必须把「参数 Schema」与「路径白名单」分成两道独立的门。
 *
 * <p>回归：ToolMcpRunner 把路径校验塞进唯一 validate 闭包，越界路径既记 PATH_WHITELIST DENY，
 * 又被算成 ARGUMENT_SCHEMA DENY，schemaValid 被错误扣成 false。
 */
class ToolMcpRunnerGateSplitTest {
  private final ToolMcpRunner runner =
      new ToolMcpRunner(new ObjectMapper().findAndRegisterModules(), mock(EvalAnswerModel.class));

  @Test
  void outOfScopePathIsAPathDenyButNotASchemaDeny() {
    EvalCaseExecution execution =
        runner.run(
            sample(
                List.of(
                    Map.of(
                        "name", "read_file",
                        "kind", "WORKSPACE_READ",
                        "path", "../secret.txt",
                        "args", Map.of("path", "../secret.txt")))),
            config(),
            "LOCAL");

    var checks = checks(execution);
    assertEquals("DENY", check(checks, "PATH_WHITELIST"), checks.toString());
    assertEquals("PASS", check(checks, "ARGUMENT_SCHEMA"), checks.toString());
    assertTrue(
        Boolean.TRUE.equals(execution.metrics().get("schemaValid")),
        "路径越界不得把 schemaValid 错扣成 false：" + execution.metrics());
  }

  @Test
  void invalidArgumentsAreASchemaDenyAndSkipThePathGate() {
    EvalCaseExecution execution =
        runner.run(
            sample(
                List.of(
                    Map.of(
                        "name", "read_file",
                        "kind", "WORKSPACE_READ",
                        "path", "src/app.js",
                        "args", "[1,2,3]"))),
            config(),
            "LOCAL");

    var checks = checks(execution);
    assertEquals("DENY", check(checks, "ARGUMENT_SCHEMA"), checks.toString());
    assertEquals("SKIP", check(checks, "PATH_WHITELIST"), checks.toString());
    assertFalse(
        Boolean.TRUE.equals(execution.metrics().get("schemaValid")),
        "参数不是 JSON 对象时必须判定 schemaValid=false：" + execution.metrics());
  }

  private static String check(List<Map<String, Object>> checks, String gate) {
    return checks.stream()
        .filter(item -> gate.equals(item.get("gate")))
        .map(item -> String.valueOf(item.get("status")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("缺少网关检查 " + gate + "：" + checks));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> checks(EvalCaseExecution execution) {
    var rows = (List<Map<String, Object>>) execution.trace().get("gatewayChecks");
    var flattened = new java.util.ArrayList<Map<String, Object>>();
    for (var row : rows) {
      Object nested = row.get("checks");
      if (nested instanceof List<?> list) {
        for (Object item : list) {
          if (item instanceof dev.workbench.framework.trace.GlassboxModels.GatewayCheck check)
            flattened.add(
                Map.of("gate", check.gate(), "status", check.status(), "detail", check.detail()));
        }
      }
    }
    return flattened;
  }

  private static EvalSample sample(List<Map<String, Object>> calls) {
    return new EvalSample(
        "tool-split-1",
        EvalCategory.TOOL_MCP,
        "custom",
        "越界读取",
        "Out-of-scope read",
        "尝试读取不在授权范围的文件。",
        Map.of("authorizedPaths", List.of("src/app.js"), "calls", calls),
        List.of(),
        List.of());
  }

  private static EvalExperimentConfig config() {
    return new EvalExperimentConfig("mock", null, false, false, false, false, 1, 5, null, null);
  }
}
