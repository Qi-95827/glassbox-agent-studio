package dev.workbench.workspace;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.tool.ToolApprovalDecision;
import dev.workbench.framework.tool.ToolEffect;
import dev.workbench.framework.tool.ToolGateway;
import dev.workbench.framework.tool.ToolRiskAssessment;
import dev.workbench.framework.tool.ToolRiskLevel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 整个工作区授权不会降低 Gate 5.5：敏感文件写入仍在网关内挂起并等待用户决策。
 *
 * <p>网关使用真实的 {@code ConservativeToolRiskPolicy}，只把运行时替换为桩，以便观察审批调用。
 */
class WholeWorkspaceGateApprovalTest {
  private final AgentRuntime runtime = mock(AgentRuntime.class);
  private final ToolGateway gateway =
      new ToolGateway(
          new ObjectMapper().findAndRegisterModules(), runtime, new AtomicReference<>());

  private void approve() {
    when(runtime.beforeTool(any(ToolKind.class), anyInt(), anyString()))
        .thenReturn(UUID.randomUUID());
    when(runtime.approvalDecision(anyString(), anyInt(), anyString(), any()))
        .thenReturn(ToolApprovalDecision.approve());
  }

  private String write(List<String> targetPaths, String argsJson) {
    return gateway.execute(
        ToolKind.WORKSPACE_WRITE,
        "workspace.write",
        1,
        "fingerprint-" + argsJson.hashCode(),
        argsJson,
        ToolEffect.WRITE,
        "写入文件",
        value -> "written",
        () -> {},
        null,
        null,
        null,
        targetPaths,
        () -> "saved");
  }

  @Test
  void sensitiveFilesInWholeWorkspaceScopeRequireApproval() {
    approve();
    for (String path :
        List.of("pom.xml", "application.yml", "application.properties", "package.json", ".env")) {
      assertTrue(write(List.of(path), "{\"path\":\"" + path + "\"}").contains("saved"), path);
    }
    verify(runtime, times(5))
        .approvalDecision(
            eq("workspace.write"),
            eq(1),
            anyString(),
            argThat((ToolRiskAssessment risk) -> risk.level() == ToolRiskLevel.HIGH));
  }

  @Test
  void ordinaryFileInWholeWorkspaceScopeIsNotRaisedToApproval() {
    approve();
    write(List.of("src/keep.py"), "{\"path\":\"src/keep.py\"}");
    verify(runtime, never()).approvalDecision(anyString(), anyInt(), anyString(), any());
  }

  @Test
  void rejectedApprovalStopsTheWriteAndReportsApprovalGate() {
    when(runtime.beforeTool(any(ToolKind.class), anyInt(), anyString()))
        .thenReturn(UUID.randomUUID());
    when(runtime.approvalDecision(anyString(), anyInt(), anyString(), any()))
        .thenReturn(ToolApprovalDecision.reject("用户拒绝写入依赖清单"));
    String result = write(List.of("pom.xml"), "{\"path\":\"pom.xml\"}");
    assertTrue(result.contains("APPROVAL_REJECTED") || result.contains("pom.xml"), result);
    verify(runtime).toolRejected(anyString());
  }
}
