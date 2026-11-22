package dev.workbench.framework.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Gate 5.5：高危拒执行、改参二次复验、低风险自动放行。 */
class ToolGatewayApprovalTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @Test
  void highRiskRejectionReturnsStructuredReasonWithoutExecuting() {
    var executed = new AtomicBoolean();
    var result =
        gateway(ToolApprovalDecision.reject("不要修改构建脚本"))
            .execute(
                ToolKind.WORKSPACE_WRITE,
                1,
                "fp",
                "{\"path\":\"pom.xml\"}",
                ToolEffect.WRITE,
                "写入",
                value -> "ok",
                () -> {},
                value -> null,
                edited -> {},
                edited -> "edited",
                () -> {
                  executed.set(true);
                  return "original";
                });
    assertFalse(executed.get());
    assertTrue(result.contains("\"status\":\"REJECTED\""));
    assertTrue(result.contains("APPROVAL_REJECTED"));
    assertTrue(result.contains("不要修改构建脚本"));
  }

  @Test
  void editedApprovalRevalidatesAndExecutesEditedArguments() {
    var executed = new AtomicBoolean();
    var editedExecuted = new AtomicBoolean();
    var revalidated = new AtomicBoolean();
    var result =
        gateway(
                new ToolApprovalDecision(
                    ToolApprovalAction.APPROVE_WITH_EDITED_ARGS,
                    "{\"path\":\"pom.xml\",\"content\":\"fixed\"}",
                    "用户修正参数"))
            .execute(
                ToolKind.WORKSPACE_WRITE,
                1,
                "fp",
                "{\"path\":\"pom.xml\"}",
                ToolEffect.WRITE,
                "写入",
                value -> "ok:" + value,
                () -> {},
                value -> null,
                edited -> {
                  if (!edited.contains("fixed"))
                    throw new IllegalArgumentException("修改后参数未通过复验");
                  revalidated.set(true);
                },
                edited -> {
                  editedExecuted.set(true);
                  return "edited";
                },
                () -> {
                  executed.set(true);
                  return "original";
                });
    assertFalse(executed.get());
    assertTrue(revalidated.get());
    assertTrue(editedExecuted.get());
    assertTrue(result.contains("SUCCEEDED"));
  }

  @Test
  void editedApprovalFailingSecondGateFiveIsRejected() {
    var executed = new AtomicBoolean();
    var result =
        gateway(
                new ToolApprovalDecision(
                    ToolApprovalAction.APPROVE_WITH_EDITED_ARGS,
                    "{\"path\":\"pom.xml\",\"content\":\"outside\"}",
                    "越权修改"))
            .execute(
                ToolKind.WORKSPACE_WRITE,
                1,
                "fp",
                "{\"path\":\"pom.xml\"}",
                ToolEffect.WRITE,
                "写入",
                value -> "ok",
                () -> {},
                value -> null,
                edited -> {
                  throw new IllegalArgumentException("修改后的路径不在授权范围");
                },
                edited -> "edited",
                () -> {
                  executed.set(true);
                  return "original";
                });
    assertFalse(executed.get());
    assertTrue(result.contains("修改后的参数未通过授权复验"));
  }

  @Test
  void lowRiskReadOnlyCallDoesNotRequestApproval() {
    var runtime =
        new StubRuntime(ToolApprovalDecision.approve()) {
          @Override
          public ToolApprovalDecision approvalDecision(
              String toolName, int version, String argsJson, ToolRiskAssessment risk) {
            throw new AssertionError("低风险只读调用不应进入 Gate 5.5");
          }
        };
    var result =
        new ToolGateway(json, runtime, new AtomicReference<>())
            .execute(
                ToolKind.WORKSPACE_READ,
                1,
                "fp",
                "{\"path\":\"a.js\"}",
                ToolEffect.READ_ONLY,
                "读取",
                value -> "ok",
                () -> {},
                value -> null,
                null,
                null,
                () -> "data");
    assertTrue(result.contains("SUCCEEDED"));
  }

  private ToolGateway gateway(ToolApprovalDecision decision) {
    return new ToolGateway(json, new StubRuntime(decision), new AtomicReference<>());
  }

  private static class StubRuntime implements dev.workbench.framework.agent.execution.AgentRuntime {
    private final ToolApprovalDecision decision;

    private StubRuntime(ToolApprovalDecision decision) {
      this.decision = decision;
    }

    @Override
    public void beforeModel(ModelKind modelName) {}

    @Override
    public void modelResult(Integer inputTokens, Integer outputTokens) {}

    @Override
    public void assemblyResolved(AgentAssemblySnapshot.Components components) {}

    @Override
    public UUID beforeTool(ToolKind tool, int version, String fingerprint) {
      return UUID.randomUUID();
    }

    @Override
    public void check() {}

    @Override
    public void event(EventType type, String message) {}

    @Override
    public void toolResult(String result) {}

    @Override
    public void toolRejected(String safeMessage) {}

    @Override
    public ToolApprovalDecision approvalDecision(
        String toolName, int version, String argsJson, ToolRiskAssessment risk) {
      return decision;
    }
  }
}
