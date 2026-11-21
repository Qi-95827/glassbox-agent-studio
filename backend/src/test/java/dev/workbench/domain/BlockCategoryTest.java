package dev.workbench.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 旧 status 保持兼容，阻塞原因映射为稳定的一级分类。 */
class BlockCategoryTest {
  @Test
  void classifiesReviewExecutionBlockedAndUnknown() {
    assertEquals(
        BlockCategory.EXECUTION_BLOCKED,
        BlockCategory.from(RunStatus.BLOCKED, StopCause.CHECK_FAILED.name()));
    assertEquals(
        BlockCategory.EXECUTION_BLOCKED,
        BlockCategory.from(RunStatus.BLOCKED, StopCause.OUTPUT_MISSING.name()));
    assertEquals(
        BlockCategory.EXECUTION_BLOCKED,
        BlockCategory.from(RunStatus.BLOCKED, StopCause.VALIDATION_MISSING.name()));
    assertEquals(
        BlockCategory.USER_REVIEW,
        BlockCategory.from(RunStatus.BLOCKED, StopCause.USER_REVIEW.name()));
    assertEquals(BlockCategory.USER_REVIEW, BlockCategory.from(RunStatus.BLOCKED, "SOMETHING_ELSE"));
    assertEquals(BlockCategory.UNKNOWN, BlockCategory.from(RunStatus.BLOCKED, null));
    assertEquals(BlockCategory.UNKNOWN, BlockCategory.from(RunStatus.BLOCKED, " "));
    assertNull(BlockCategory.from(RunStatus.COMPLETED, null));
  }
}
