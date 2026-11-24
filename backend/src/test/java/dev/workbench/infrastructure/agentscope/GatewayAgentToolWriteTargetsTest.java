package dev.workbench.infrastructure.agentscope;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.framework.tool.ConservativeToolRiskPolicy;
import dev.workbench.framework.tool.ToolEffect;
import dev.workbench.framework.tool.ToolRiskContext;
import dev.workbench.framework.tool.ToolRiskLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 写入目标路径必须只取真实目标，不能把只读授权文件混进来。
 *
 * <p>反例对应 `S-06` 的误拦截面：任务只读授权里常有 {@code pom.xml}/{@code .env}，若把它们当作写入目标， 一次普通源码写入也会被抬成 HIGH 并强制审批。
 */
class GatewayAgentToolWriteTargetsTest {
  private final ConservativeToolRiskPolicy policy = new ConservativeToolRiskPolicy();

  @Test
  void multiFileWriteUsesOnlyTheDeclaredPath() {
    var targets = GatewayAgentTool.writeTargets("src/App.java", List.of("pom.xml", "config/.env"));

    assertEquals(List.of("src/App.java"), targets);
    assertEquals(
        ToolRiskLevel.MEDIUM,
        policy
            .assess(new ToolRiskContext("workspace_write", ToolEffect.WRITE, "{}", targets))
            .level(),
        "只读授权含 pom.xml 时，写 src/App.java 不得被误判为高危");
  }

  @Test
  void singleFileClosureFallsBackToWritePaths() {
    var targets = GatewayAgentTool.writeTargets(null, List.of("README.md"));

    assertEquals(List.of("README.md"), targets);
    assertEquals(
        ToolRiskLevel.MEDIUM,
        policy
            .assess(new ToolRiskContext("workspace_write", ToolEffect.WRITE, "{}", targets))
            .level());
  }

  @Test
  void singleFileClosureToSensitivePathIsHighRisk() {
    var targets = GatewayAgentTool.writeTargets("  ", List.of("config/.env"));

    assertEquals(List.of("config/.env"), targets);
    assertEquals(
        ToolRiskLevel.HIGH,
        policy
            .assess(new ToolRiskContext("workspace_write", ToolEffect.WRITE, "{}", targets))
            .level());
  }

  @Test
  void noPathAndNoWriteGrantProducesNoTargets() {
    assertTrue(GatewayAgentTool.writeTargets(null, List.of()).isEmpty());
    assertTrue(GatewayAgentTool.writeTargets(null, null).isEmpty());
  }
}
