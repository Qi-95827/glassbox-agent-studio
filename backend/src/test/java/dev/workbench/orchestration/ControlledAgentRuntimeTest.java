package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.control.RunControl;
import dev.workbench.task.RunRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 未完成装配或版本不符时，绝不能进入工具调用。 */
class ControlledAgentRuntimeTest {
  @Test
  void toolCallsRequireSuccessfulMatchingRegistration() {
    var repository = mock(RunRepository.class);
    var control = mock(RunControl.class);
    var runtime = new ControlledAgentRuntime("run", "agent", control, repository);
    var id = UUID.randomUUID();
    when(control.beginTool(eq("agent"), eq(ToolKind.WORKSPACE_READ), eq(2), anyString()))
        .thenReturn(id);
    assertThrows(
        IllegalStateException.class,
        () -> runtime.beforeTool(ToolKind.WORKSPACE_READ, 2, "fingerprint"));
    runtime.toolsLoaded(Map.of(ToolKind.WORKSPACE_READ, 2));
    assertThrows(
        IllegalStateException.class,
        () -> runtime.beforeTool(ToolKind.WORKSPACE_READ, 1, "fingerprint"));
    assertThrows(
        IllegalStateException.class,
        () -> runtime.beforeTool(ToolKind.WORKSPACE_WRITE, 2, "fingerprint"));
    assertEquals(id, runtime.beforeTool(ToolKind.WORKSPACE_READ, 2, "fingerprint"));
    verify(control, times(1)).beginTool(anyString(), any(), anyInt(), anyString());
    verify(repository).recordAgentVersionedToolGrants("agent", Map.of(ToolKind.WORKSPACE_READ, 2));
  }

  @Test
  void failedRegistrationDoesNotAuthorizeToolsAndUsageIsRecordedBeforeBudgetCheck() {
    var repository = mock(RunRepository.class);
    var control = mock(RunControl.class);
    var runtime = new ControlledAgentRuntime("run", "agent", control, repository);
    doThrow(new IllegalStateException("登记失败"))
        .when(repository)
        .recordAgentVersionedToolGrants(anyString(), anyMap());
    assertThrows(
        IllegalStateException.class, () -> runtime.toolsLoaded(Map.of(ToolKind.WORKSPACE_READ, 1)));
    assertThrows(
        IllegalStateException.class,
        () -> runtime.beforeTool(ToolKind.WORKSPACE_READ, 1, "fingerprint"));
    verify(control, never()).beginTool(anyString(), any(), anyInt(), anyString());
    runtime.modelResult(12, 8);
    var order = inOrder(control);
    order.verify(control).finishModel(InvocationStatus.COMPLETED, 12, 8, null);
    order.verify(control).checkTokenBudget();
  }
}
