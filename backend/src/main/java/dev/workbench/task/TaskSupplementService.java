package dev.workbench.task;

import dev.workbench.domain.EventRef;
import dev.workbench.domain.EventType;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.PlanService;
import dev.workbench.orchestration.RunService;
import dev.workbench.persistence.repository.InvocationRepository;
import dev.workbench.persistence.repository.PlanGraphRepository;
import dev.workbench.persistence.repository.SupplementRepository;
import dev.workbench.persistence.repository.SupplementRepository.Request;
import dev.workbench.persistence.repository.RunRepository;
import dev.workbench.persistence.repository.TaskRepository;
import dev.workbench.workspace.WorkspaceService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 任务 2 的等待补充与安全继续。
 *
 * <p>补充请求在复现检查因缺少可执行测试阻塞时由结算事务创建；本服务负责校验并应用用户提交。全部校验与
 * 状态变更在同一事务内完成：不满足任何条件时整体回滚，运行保持阻塞，仅另行记录拒绝事件。继续只发生在零调用的安全边界（首个节点之前），已批准计划的授权摘要必须仍与当前任务一致，避免复用过期审批。 */
@Service
public class TaskSupplementService {
  private final RunRecordService runs;
  private final PlanService plans;
  private final WorkspaceService workspaces;
  private final RunRepository runRows;
  private final TaskRepository taskRows;
  private final PlanGraphRepository planRows;
  private final InvocationRepository invocations;
  private final SupplementRepository requests;
  private final TransactionTemplate tx;
  private final RunService runService;

  public TaskSupplementService(
      RunRecordService runs,
      PlanService plans,
      WorkspaceService workspaces,
      RunRepository runRows,
      TaskRepository taskRows,
      PlanGraphRepository planRows,
      InvocationRepository invocations,
      SupplementRepository requests,
      TransactionTemplate tx,
      RunService runService) {
    this.runs = runs;
    this.plans = plans;
    this.workspaces = workspaces;
    this.runRows = runRows;
    this.taskRows = taskRows;
    this.planRows = planRows;
    this.invocations = invocations;
    this.requests = requests;
    this.tx = tx;
    this.runService = runService;
  }

  /** 应用结果：提交后的新任务版本。*/
  public record Applied(int newTaskVersion) {}

  /** 该运行唯一的未处理请求；没有时为 null。 */
  public Request openRequest(String runId) {
    var rows = requests.findOpen(runId);
    if (rows.size() > 1) throw new IllegalStateException("运行存在多条待补充事项。");
    return rows.isEmpty() ? null : rows.getFirst();
  }

  /** 展示用：优先返回未处理请求，否则返回最近一次已应用请求。*/
  public Request latestRequest(String runId) {
    var open = openRequest(runId);
    return open == null ? requests.findLatest(runId) : open;
  }

  /**
   * 校验并应用用户补充；成功后运行从安全边界重新排队。
   *
   * <p>提交必须携带读取页面时的任务版本。任何校验失败都会回滚并保持阻塞，同时记录拒绝事件，便于在运行链路上看到每次尝试。 */
  public Applied apply(
      String runId, String requestId, int expectedTaskVersion, String path, String content) {
    Applied applied;
    try {
      applied =
          tx.execute(
              status -> applyInTransaction(runId, requestId, expectedTaskVersion, path, content));
    } catch (RuntimeException failure) {
      recordRejection(runId, failure.getMessage());
      throw failure;
    }
    runService.dispatchContinuation(runId);
    return applied;
  }

  private Applied applyInTransaction(
      String runId, String requestId, int expectedTaskVersion, String path, String content) {
    if (!runRows.lockForUpdate(runId)) throw new EmptyResultDataAccessException(1);
    var run = runRows.findRunById(runId).orElseThrow(() -> new EmptyResultDataAccessException(1));
    var request = openRequest(runId);
    // 先判断请求再判断运行状态：已处理或不属于该运行的请求给出明确且幂等的拒绝原因。
if (request == null || !request.id().equals(requestId))
      throw new IllegalArgumentException("补充请求不存在、已处理或不属于该运行。");
    if (!RunStatus.BLOCKED.name().equals(run.status())
        || !StopCause.CHECK_FAILED.name().equals(run.stopCause()))
      throw new IllegalStateException("运行当前不是检查阻塞状态，不能补充。");
    String taskId = run.taskId();
    if (!taskRows.lockForUpdate(taskId)) throw new EmptyResultDataAccessException(1);
    Integer current =
        taskRows.findVersionById(taskId).orElseThrow(() -> new EmptyResultDataAccessException(1));

    if (current == null || current != expectedTaskVersion)
      throw new IllegalArgumentException(
          "任务版本已变化（当前 v" + (current == null ? 1 : current) + "），请刷新后重新提交。");
    if (path == null || path.isBlank()) throw new IllegalArgumentException("请提供测试文件路径。");
    if (content == null || content.isBlank() || content.length() > 20_000)
      throw new IllegalArgumentException("测试文件内容必须为 1 到 20000 个字符。");
    var task = runs.taskDefinition(taskId);
    if (task.workspaceId() == null) throw new IllegalArgumentException("任务没有关联工作区。");
    var plan = plans.workflowPlan(runId);
    if (plan == null) throw new IllegalStateException("运行没有已保存的动态计划。");
    // 授权摘要必须仍匹配：目标或授权范围变化后不能复用原批准继续执行。
    plan.requireTaskAuthorization(task);
    requireSafeBoundary(runId, request);
    String sha = workspaces.createSupplementTestFile(task.workspaceId(), path, content);
    try {
      String time = RunRecordService.now();
      if (!requests.recordAnswer(requestId, path, sha, time))
        throw new IllegalStateException("补充请求已被处理。");
      if (taskRows.incrementVersion(taskId, expectedTaskVersion) != 1)
        throw new IllegalArgumentException("任务版本已变化，请刷新后重新提交。");
      if (!planRows.resetFailedCheck(runId, request.nodeId()))
        throw new IllegalStateException("失败的检查节点状态已变化，不能继续。");
      planRows.resetSkippedNodes(runId);
      if (!runRows.requeueAfterSupplement(runId, time))
        throw new IllegalStateException("运行状态已变化，不能继续。");

      var node =
          plans.find(runId).stream()
              .filter(candidate -> candidate.id().toString().equals(request.nodeId()))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("补充请求关联的节点不存在。"));
      runs.event(
          runId,
          EventType.SUPPLEMENT_APPLIED,
          "用户补充测试文件 "
              + path
              + "，任务版本 v"
              + expectedTaskVersion
              + " → v"
              + (expectedTaskVersion + 1)
              + "。",
          EventRef.empty().withPlan(plan.id()).withNode(node.id()));
      runs.event(
          runId,
          EventType.CONTINUED_AFTER_SUPPLEMENT,
          "已从安全边界继续：复现检查将重新执行，首次失败的检查记录保留。",          EventRef.empty().withPlan(plan.id()).withNode(node.id()));
      return new Applied(expectedTaskVersion + 1);
    } catch (RuntimeException failure) {
      // 数据库更新失败时尽力删除刚写入的文件，避免留下与记录不一致的工作区内容。
      workspaces.discardSupplementTestFile(task.workspaceId(), path);
      throw failure;
    }
  }

  /** 继续只允许发生在零调用边界：任何 Agent、模型或工具记录都意味着需要人工核对或新运行。*/
  private void requireSafeBoundary(String runId, Request request) {
    if (invocations.hasCalls(runId))
      throw new IllegalStateException("运行已有实际调用记录，不能原地继续；请核对差异或新建运行。");

    if (runs.hasRecovery(runId)) throw new IllegalStateException("该运行已发起恢复子运行，不能原地继续。");
    PlanNode node =
        plans.find(runId).stream()
            .filter(candidate -> candidate.id().toString().equals(request.nodeId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("补充请求关联的节点不存在。"));
    if (node.status() != PlanNodeStatus.FAILED || !node.systemCheck())
      throw new IllegalStateException("关联节点不再是失败的系统检查。");
    if (requests.findLatestCheckStatus(request.nodeId()) != SystemCheckStatus.BLOCKED)
      throw new IllegalStateException("最新检查记录不是无法判定状态，需要重新核对。");
  }

  private void recordRejection(String runId, String message) {
    String reason = message == null || message.isBlank() ? "补充被拒绝" : message;
    try {
      tx.executeWithoutResult(
          status -> runs.event(runId, EventType.SUPPLEMENT_REJECTED, "补充提交被拒绝：" + reason));
    } catch (RuntimeException ignored) {
      // 拒绝事件写不进去时不改变返回给用户的原因。
    }
  }

  /** 仅供文档与测试引用的常量：复现缺测试的稳定原因编码。*/
  public static final String REPRODUCTION_MISSING_INFO = "REPRODUCTION_MISSING_INFO";
}
