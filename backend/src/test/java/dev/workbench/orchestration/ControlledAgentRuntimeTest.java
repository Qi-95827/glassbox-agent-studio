package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.control.AgentBudgetPolicy;
import dev.workbench.framework.control.RunControl;
import dev.workbench.framework.model.AgentModelRegistry.ModelReference;
import dev.workbench.domain.RunLimits;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 未完成装配或版本不符时，绝不能进入工具调用。 */
class ControlledAgentRuntimeTest {
  @Test
  void toolCallsRequireSuccessfulMatchingRegistration() {
    var repository = mock(AgentAssemblyRecordService.class);
    var control = mock(RunControl.class);
    var runtime =
        new ControlledAgentRuntime(
            "run",
            "agent",
            control,
            repository,
            mock(RunStateStore.class),
            mock(RunEvidenceRecordService.class));
    var id = UUID.randomUUID();
    when(control.beginTool(eq("agent"), eq(ToolKind.WORKSPACE_READ), eq(2), anyString(), isNull()))
        .thenReturn(id);
    assertThrows(
        IllegalStateException.class,
        () -> runtime.beforeTool(ToolKind.WORKSPACE_READ, 2, "fingerprint"));
    var components = assembly(2);
    var budget = budget(control);
    runtime.assemblyResolved(components);
    assertThrows(
        IllegalStateException.class,
        () -> runtime.beforeTool(ToolKind.WORKSPACE_READ, 1, "fingerprint"));
    assertThrows(
        IllegalStateException.class,
        () -> runtime.beforeTool(ToolKind.WORKSPACE_WRITE, 2, "fingerprint"));
    assertEquals(id, runtime.beforeTool(ToolKind.WORKSPACE_READ, 2, "fingerprint"));
    verify(control, times(1)).beginTool(anyString(), any(), anyInt(), anyString(), isNull());
    verify(repository)
        .recordAgentAssembly(
            "run",
            "agent",
            new AgentAssemblySnapshot(AgentAssemblySnapshot.VERSION, components, budget));
  }

  @Test
  void failedRegistrationDoesNotAuthorizeToolsAndUsageIsRecordedBeforeBudgetCheck() {
    var repository = mock(AgentAssemblyRecordService.class);
    var control = mock(RunControl.class);
    budget(control);
    var runtime =
        new ControlledAgentRuntime(
            "run",
            "agent",
            control,
            repository,
            mock(RunStateStore.class),
            mock(RunEvidenceRecordService.class));
    doThrow(new IllegalStateException("登记失败"))
        .when(repository)
        .recordAgentAssembly(anyString(), anyString(), any());
    assertThrows(IllegalStateException.class, () -> runtime.assemblyResolved(assembly(1)));
    assertThrows(
        IllegalStateException.class,
        () -> runtime.beforeTool(ToolKind.WORKSPACE_READ, 1, "fingerprint"));
    verify(control, never()).beginTool(anyString(), any(), anyInt(), anyString(), isNull());
    assertThrows(IllegalStateException.class, () -> runtime.beforeModel(ModelKind.MOCK));
    verify(control, never()).beginModel(anyString(), any(), isNull());
    runtime.modelResult(12, 8);
    var order = inOrder(control);
    order.verify(control).finishModel("agent", InvocationStatus.COMPLETED, 12, 8, null);
    order.verify(control).checkTokenBudget();
  }

  private static AgentBudgetPolicy budget(RunControl control) {
    var policy =
        new AgentBudgetPolicy(
            1, new RunLimits(4, 2, 2, null, null, 30, null, null), null, Map.of(), null);
    when(control.budgetPolicy(AgentRole.ANALYST, null)).thenReturn(policy);
    return policy;
  }

  private static AgentAssemblySnapshot.Components assembly(int toolVersion) {
    return new AgentAssemblySnapshot.Components(
        new AgentDefinitionRef("analyst", 1),
        "a".repeat(64),
        new ModelReference(ModelMode.MOCK, "mock", 1),
        20,
        1,
        List.of(),
        Map.of(ToolKind.WORKSPACE_READ, toolVersion),
        new AgentAssemblySnapshot.Authorization(
            1, AgentRole.ANALYST, 1, "workspace", toolVersion, "b".repeat(64), false),
        new AgentAssemblySnapshot.Input("c".repeat(64), List.of()));
  }
}
