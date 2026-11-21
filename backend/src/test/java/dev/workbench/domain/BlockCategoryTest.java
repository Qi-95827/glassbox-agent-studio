package dev.workbench.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 旧 status 保持兼容，阻塞原因映射为稳定的一级分类。 */
class BlockCategoryTest {
  @Test
  void classifiesReviewAndExecutionBlocked() {
    assertEquals(
        BlockCategory.EXECUTION_BLOCKED,
        BlockCategory.from(RunStatus.BLOCKED, StopCause.CHECK_FAILED.name()));
    assertEquals(
        BlockCategory.EXECUTION_BLOCKED,
        BlockCategory.from(RunStatus.BLOCKED, StopCause.OUTPUT_MISSING.name()));
    assertEquals(
        BlockCategory.USER_REVIEW, BlockCategory.from(RunStatus.BLOCKED, null));
    assertNull(BlockCategory.from(RunStatus.COMPLETED, null));
  }
}
