package dev.workbench.framework.workflow.plan;

import dev.workbench.domain.TaskDefinition;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 动态计划版本的固定模板引用与审批记录。 */
public record WorkflowPlan(
    UUID id,
    int version,
    String templateId,
    int templateVersion,
    boolean reviewRequired,
    Instant approvedAt,
    String graphSha256,
    UUID sourceCheckpointId,
    WorkflowTemplate.Scope scope,
    String authorizationSha256,
    int maxAttempts) {
  public void requireTaskAuthorization(TaskDefinition task) {
    if (!Objects.equals(scope.authorizationDigest(task), authorizationSha256))
      throw new IllegalStateException("任务授权文件范围与计划版本不一致。");
  }
}
