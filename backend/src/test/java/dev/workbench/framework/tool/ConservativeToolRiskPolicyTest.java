package dev.workbench.framework.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 风险策略按真实目标路径判定。
 *
 * <p>反例对应 `S-06`：单文件写入 {@code .env} 时参数里只有内容不含路径，旧的 {@code argsJson.contains(".env")} 判定会放行。
 */
class ConservativeToolRiskPolicyTest {
  private final ConservativeToolRiskPolicy policy = new ConservativeToolRiskPolicy();

  @Test
  void singleFileWriteToSensitivePathIsHighRisk() {
    var risk =
        policy.assess(
            new ToolRiskContext(
                "workspace_write",
                ToolEffect.WRITE,
                "{\"content\":\"正常代码\"}",
                List.of("config/.env")));

    assertEquals(ToolRiskLevel.HIGH, risk.level());
    assertTrue(risk.reason().contains(".env"));
  }

  @Test
  void ordinaryCodeContentNoLongerTriggersFalsePositive() {
    // 正文里出现 security 字样不再误拦截：目标路径是普通源文件。
    var risk =
        policy.assess(
            new ToolRiskContext(
                "workspace_write",
                ToolEffect.WRITE,
                "{\"content\":\"import security from './security-guard'\"}",
                List.of("src/App.jsx")));

    assertEquals(ToolRiskLevel.MEDIUM, risk.level());
  }

  @Test
  void dependencyAndCiPathsAreHighRisk() {
    assertEquals(
        ToolRiskLevel.HIGH,
        policy
            .assess(
                new ToolRiskContext(
                    "workspace_write", ToolEffect.WRITE, "{}", List.of("./pom.xml")))
            .level());
    assertEquals(
        ToolRiskLevel.HIGH,
        policy
            .assess(
                new ToolRiskContext(
                    "workspace_write",
                    ToolEffect.WRITE,
                    "{}",
                    List.of(".github/workflows/deploy.yml")))
            .level());
  }

  @Test
  void readOnlyAndMcpWritingKeepTheirSemantics() {
    assertEquals(
        ToolRiskLevel.LOW,
        policy
            .assess(
                new ToolRiskContext("workspace_read", ToolEffect.READ_ONLY, "{}", List.of(".env")))
            .level());
    assertEquals(
        ToolRiskLevel.HIGH,
        policy
            .assess(
                new ToolRiskContext(
                    "mcp::notes::create", ToolEffect.WRITE, "{}", List.of("notes/a.md")))
            .level());
  }

  @Test
  void missingTargetPathsFallsBackToArgumentScan() {
    assertEquals(
        ToolRiskLevel.HIGH,
        policy
            .assess(
                new ToolRiskContext("workspace_write", ToolEffect.WRITE, "{\"path\":\"app/.env\"}"))
            .level());
    assertEquals(
        ToolRiskLevel.MEDIUM,
        policy
            .assess(
                new ToolRiskContext("workspace_write", ToolEffect.WRITE, "{\"path\":\"a.txt\"}"))
            .level());
  }

  @Test
  void targetPathsAreNormalizedBeforeMatching() {
    var risk =
        policy.assess(
            new ToolRiskContext(
                "workspace_write", ToolEffect.WRITE, "{}", List.of(".\\DockerFile")));

    assertEquals(ToolRiskLevel.HIGH, risk.level());
  }
}
