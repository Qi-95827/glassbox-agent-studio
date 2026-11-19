package dev.workbench.workspace;

import java.time.Instant;
import java.util.UUID;

/** 用户触发的本地 Git 动作；仅确认成功时返回提交 ID。 */
public record GitAction(
    UUID id,
    UUID workspaceId,
    Kind kind,
    GitActionStatus status,
    UUID approvalId,
    String commitId,
    String target,
    String detail,
    Instant createdAt,
    Instant updatedAt) {
  public enum Kind {
    LOCAL_COMMIT,
    PUSH,
    MERGE
  }
}
