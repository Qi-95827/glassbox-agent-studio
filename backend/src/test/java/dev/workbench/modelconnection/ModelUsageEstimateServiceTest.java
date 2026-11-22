package dev.workbench.modelconnection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.workbench.framework.secret.SecretCipher;
import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.entity.UserModelConnectionEntity;
import dev.workbench.persistence.repository.InvocationRepository;
import dev.workbench.persistence.repository.TaskData;
import dev.workbench.persistence.repository.TaskRepository;
import dev.workbench.persistence.repository.UserModelConnectionRepository;
import dev.workbench.persistence.repository.view.ModelData;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModelUsageEstimateServiceTest {
  @Test
  void multipliesRecordedTokensByReferencePrices() {
    var ownership = mock(OwnershipService.class);
    var tasks = mock(TaskRepository.class);
    var connections = mock(UserModelConnectionRepository.class);
    var invocations = mock(InvocationRepository.class);
    when(ownership.taskIdOfRun("run-1")).thenReturn("task-1");
    when(tasks.findTaskById("task-1")).thenReturn(Optional.of(task("task-1", "conn-1")));
    when(invocations.findModelCalls("run-1"))
        .thenReturn(
            List.of(call("600000", "200000"), call("400000", "300000")));
    when(connections.findById("conn-1")).thenReturn(Optional.of(connection(2.0, 4.0)));

    var estimate =
        new ModelUsageEstimateService(ownership, tasks, connections, invocations)
            .estimate("run-1");

    assertTrue(estimate.byok());
    assertTrue(estimate.priced());
    assertEquals(1_000_000L, estimate.inputTokens());
    assertEquals(500_000L, estimate.outputTokens());
    assertEquals(0, new BigDecimal("4").compareTo(estimate.estimatedCost()));
    assertEquals("用户自备 Key（参考估算）", estimate.label());
  }

  @Test
  void missingReferencePriceStillReturnsTokens() {
    var ownership = mock(OwnershipService.class);
    var tasks = mock(TaskRepository.class);
    var connections = mock(UserModelConnectionRepository.class);
    var invocations = mock(InvocationRepository.class);
    when(ownership.taskIdOfRun("run-2")).thenReturn("task-2");
    when(tasks.findTaskById("task-2")).thenReturn(Optional.of(task("task-2", "conn-2")));
    when(invocations.findModelCalls("run-2")).thenReturn(List.of(call("10", null)));
    when(connections.findById("conn-2")).thenReturn(Optional.of(connection(null, null)));

    var estimate =
        new ModelUsageEstimateService(ownership, tasks, connections, invocations)
            .estimate("run-2");

    assertTrue(estimate.byok());
    assertFalse(estimate.priced());
    assertEquals(0, estimate.inputTokens());
    assertEquals(1, estimate.unknownTokenCalls());
    assertNull(estimate.estimatedCost());
    assertEquals("未设参考单价（不扣平台费用）", estimate.label());
  }

  private static ModelData call(String input, String output) {
    return new ModelData(
        "call",
        "run",
        "COMPLETED",
        "t1",
        "t2",
        "agent",
        "deepseek-chat",
        input == null ? null : Integer.parseInt(input),
        output == null ? null : Integer.parseInt(output),
        10L,
        null);
  }

  private static TaskData task(String id, String connectionId) {
    return new TaskData(
        id,
        "目标",
        "NORMAL",
        "t1",
        null,
        "user",
        null,
        null,
        false,
        null,
        false,
        "[]",
        "[]",
        "CODE",
        "[]",
        1,
        connectionId);
  }

  private static UserModelConnectionEntity connection(Double input, Double output) {
    byte[] key = new byte[32];
    new SecureRandom(new byte[] {4, 5, 6}).nextBytes(key);
    return new UserModelConnectionEntity(
        "conn-1",
        "user-a",
        "我的模型",
        "DEEPSEEK",
        "https://api.deepseek.com",
        "deepseek-chat",
        new SecretCipher(key).encrypt("sk-x"),
        "sk-****",
        true,
        true,
        input,
        output,
        "CNY",
        "t1",
        "t1");
  }
}
