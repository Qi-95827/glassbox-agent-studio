package dev.workbench.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 验证 Task 业务状态与 Run 不可变运行状态的分层推导规则。 */
class TaskStatusTest {

  @Test
  void derivesDeliveredOnceWorkspaceCommitOrMergeConfirmed() {
    assertEquals(TaskStatus.AWAITING_REVIEW, TaskStatus.derive("BLOCKED", true, false));
    assertEquals(TaskStatus.DELIVERED, TaskStatus.derive("BLOCKED", true, true));
    assertEquals(TaskStatus.DELIVERED, TaskStatus.derive("COMPLETED", true, true));
    assertEquals(TaskStatus.COMPLETED, TaskStatus.derive("COMPLETED", false, false));
    assertEquals(TaskStatus.BLOCKED, TaskStatus.derive("BLOCKED", false, false));
    assertEquals(TaskStatus.IN_PROGRESS, TaskStatus.derive("RUNNING", true, false));
    assertEquals(TaskStatus.FAILED, TaskStatus.derive("FAILED", true, false));
    assertEquals(TaskStatus.CANCELLED, TaskStatus.derive("CANCELLED", true, false));
  }
}
