package dev.workbench.store;

import dev.workbench.domain.*;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.skill.SkillAssignment;
import dev.workbench.framework.skill.SkillRef;
import dev.workbench.framework.tool.AgentToolGrant;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 持久化边界的兼容门面：保留原有公开 API，按职责委托给读取、状态、装配和证据服务。 */
@Service
public class RunRecordService
    implements dev.workbench.framework.control.RunControlStore,
        dev.workbench.framework.workflow.plan.PlanningRunReader,
        RunRecordReadPort {
  private final TaskRecordService tasks;
  private final RunStateStore runs;
  private final AgentAssemblyRecordService agents;
  private final RunEvidenceRecordService evidence;
  private final TransactionTemplate tx;

  @Override
  public TaskRecordService taskStore() {
    return tasks;
  }

  @Override
  public AgentAssemblyRecordService agentStore() {
    return agents;
  }

  @Override
  public RunEvidenceRecordService evidenceStore() {
    return evidence;
  }

  public RunRecordService(
      TaskRecordService tasks,
      RunStateStore runs,
      AgentAssemblyRecordService agents,
      RunEvidenceRecordService evidence,
      TransactionTemplate tx) {
    this.tasks = tasks;
    this.runs = runs;
    this.agents = agents;
    this.evidence = evidence;
    this.tx = tx;
  }

  public static String id() {
    return RecordSupport.id();
  }

  public static String now() {
    return RecordSupport.now();
  }

  /** 归档只改变任务列表可见性，不删除运行、证据或 Git 产物。 */

  /** 当前任务版本；补充等信息变更会递增，提交方用它做乐观并发校验。 */

  /** 读取任务角色策略；未知版本或角色不能当作未设置。 */

  /** 读取指定工具的策略；未知版本或类型不能当作未设置。 */

  /** 限额计算使用本运行已启动实例的角色，不能由模型自行声明。 */

  public Map<String, Object> run(String id) {
    return runs.run(id);
  }

  public RunSnapshot runSnapshot(String id) {
    return runs.runSnapshot(id);
  }

  public List<Map<String, Object>> runs(String taskId) {
    return runs.runs(taskId);
  }

  public boolean hasRecovery(String parent) {
    return runs.hasRecovery(parent);
  }

  /** 先验证摘要和事件引用，再转换检查点；拒绝不兼容或损坏的数据。 */

  /** 读取真实实例所认领节点的定义；历史节点没有记录时返回空。 */

  /** 历史计划引用和摘要可同时为空；不根据当前定义伪造历史摘要。 */
  public record PlannedAgentDefinition(AgentDefinitionRef reference, String sha256) {}

  /** 完整装配及兼容查询所需投影在同一事务内发布。 */

  /** 历史没有快照的实例不会由现行配置推测补齐。 */

  /** 仅记录运行中实例实际解析的定义；历史空值不自动推断。 */

  /** 记录已装配技能的不可变引用；不记录提示正文，也不授予工具权限。 */

  /** 注册集合和空集合标记在同一事务保存；失败时不应启动模型。 */

  /** 新装配必须提供明确的服务端工具契约版本。 */

  /** 缺失标记与已登记空集合分开。 */

  /** 已启用重复保护时，调用记录与指纹在同一事务中写入。 */

  /** 只读工具的资源版本与调用终态一起保存；旧调用没有该证据时保持为空。 */

  /** 首次委派、实例启动和接收记录共同提交，避免半成品运行。 */

  /** 启动下游实例和确认交接为同一事务；确认不证明模型已读取输入。 */

  /** 成功结果交接与 Agent 结束状态共同提交；运行终态由协调者决定。 */

  /** 审查完成、交接和整个运行终态一起提交，外部模型调用不在事务内。 */
  public void finishReviewedRun(ReviewCompletion completion) {
    runs.finishReviewedRun(completion);
  }

  /** 受控停止与 Agent、运行终态、事件和系统交接一起提交。 */
  public void finishStoppedRun(StopCompletion completion) {
    runs.finishStoppedRun(completion);
  }

  public void settleStoppedRun(
      String runId, StopCause cause, String reason, int rounds, int tools, long elapsed) {
    runs.settleStoppedRun(runId, cause, reason, rounds, tools, elapsed);
  }

  /** 首运行及计划与任务、创建事件同事务，避免持久化出孤立的半成品。 */
  public void createTaskAndFirstRun(
      TaskDefinition task, UUID runId, RunLimits limits, String message) {
    createTaskAndFirstRun(task, runId, limits, message, null, false);
  }

  public void createTaskAndFirstRun(
      TaskDefinition task,
      UUID runId,
      RunLimits limits,
      String message,
      WorkflowTemplate workflow,
      boolean reviewRequired) {
    tx.executeWithoutResult(
        status -> {
          tasks.createTaskRow(task);
          runs.createRunForWorkflow(
              runId.toString(),
              task.id().toString(),
              null,
              0,
              0,
              limits.maxRounds(),
              limits.maxTools(),
              limits.maxPerTool(),
              limits.maxReportedTokens(),
              limits.maxIdenticalTools(),
              limits.maxSeconds(),
              0,
              task.writeEnabled(),
              limits.maxAgentRounds(),
              limits.maxAgentTools(),
              workflow,
              reviewRequired);
          evidence.event(runId.toString(), EventType.TASK_CREATED, message);
        });
  }

  /** 已确认候选计划的任务、运行、计划、节点规格和创建事件一起提交。 */
  public void createTaskAndFirstRunFromPlan(
      TaskDefinition task,
      UUID runId,
      RunLimits limits,
      String message,
      CandidateRunPlan plan,
      String planTime) {
    tx.executeWithoutResult(
        status -> {
          tasks.createTaskRow(task);
          runs.createRunForCandidatePlan(
              runId.toString(),
              task.id().toString(),
              null,
              0,
              0,
              limits.maxRounds(),
              limits.maxTools(),
              limits.maxPerTool(),
              limits.maxReportedTokens(),
              limits.maxIdenticalTools(),
              limits.maxSeconds(),
              0,
              task.writeEnabled(),
              limits.maxAgentRounds(),
              limits.maxAgentTools(),
              plan);
          evidence.event(runId.toString(), EventType.TASK_CREATED, message);
          evidence.event(runId.toString(), EventType.PLAN_APPROVED, "用户确认候选计划并创建运行。");
        });
  }

  /** 恢复子运行、计划和创建事件一起保存，预算采用父运行与快照的较大值。 */
  public void createRecoveryRun(
      UUID runId, RunSnapshot original, CheckpointSnapshot checkpoint) {
    runs.createRecoveryRun(runId, original, checkpoint);
  }

  /** 调用方在源运行锁和恢复校验事务内创建副本。 */
  public void createWorkflowRecoveryRun(
      UUID id,
      RunSnapshot original,
      WorkflowTemplate template,
      boolean reviewRequired,
      int rounds,
      int tools,
      long elapsed) {
    runs.createWorkflowRecoveryRun(id, original, template, reviewRequired, rounds, tools, elapsed);
  }

  /** 仅在线程池明确拒绝时调用；记录成功后父运行才允许再次申请恢复。 */
  public void rejectRecoveryBeforeDispatch(String runId) {
    runs.rejectRecoveryBeforeDispatch(runId);
  }

  public void rejectRecoveryPrecondition(
      String runId, int rounds, int tools, long elapsed, String reason) {
    runs.rejectRecoveryPrecondition(runId, rounds, tools, elapsed, reason);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      long elapsed) {
    runs.createRun(id, taskId, parent, rounds, tools, maxRounds, maxTools, maxSeconds, elapsed);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Integer maxSeconds,
      long elapsed) {
    runs.createRun(
        id, taskId, parent, rounds, tools, maxRounds, maxTools, maxPerTool, maxSeconds, elapsed);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxSeconds,
      long elapsed) {
    runs.createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxSeconds,
        elapsed);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxSeconds,
      long elapsed,
      boolean includeCoder) {
    runs.createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxSeconds,
        elapsed,
        includeCoder);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      Integer maxSeconds,
      long elapsed,
      boolean includeCoder) {
    runs.createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxIdenticalTools,
        maxSeconds,
        elapsed,
        includeCoder);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      Integer maxSeconds,
      long elapsed,
      boolean includeCoder,
      Integer maxAgentRounds,
      Integer maxAgentTools) {
    runs.createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxIdenticalTools,
        maxSeconds,
        elapsed,
        includeCoder,
        maxAgentRounds,
        maxAgentTools);
  }

  /** 累计节点的全部尝试，所有已记录启动的调用占用预算。 */

  /** 按记录顺序倒查当前运行及恢复祖先，遇到不同调用立即停止。 */

  /** 已报告用量和未知用量次数；未知不能按零解释。 */

  /** 包含本运行和恢复祖先中的已启动调用，避免恢复时同种工具限额归零。 */

  /** 检查点原因使用受控编码，便于稳定聚合且不暴露用户数据。 */

  /** 写入带结构化归属的事件；空关联与旧接口行为一致。 */

  /**
   * 按 Agent 实例查找已持久化的节点和尝试。
   *
   * <p>只返回实际存在的归属；兼容流程或尚未认领的实例保持空值，不从角色或计划顺序推断。
   */

  public void update(
      String id,
      RunStatus status,
      String reason,
      String result,
      int rounds,
      int tools,
      long elapsed) {
    runs.update(id, status, reason, result, rounds, tools, elapsed);
  }

  /** 状态和停止原因在同一事务保存；旧记录或不适用时原因保持未知。 */
  public void update(
      String id,
      RunStatus status,
      String reason,
      String result,
      int rounds,
      int tools,
      long elapsed,
      StopCause stopCause) {
    runs.update(id, status, reason, result, rounds, tools, elapsed, stopCause);
  }

  @Override
  public void finishModel(
      String id,
      InvocationStatus status,
      Integer input,
      Integer output,
      String error,
      long duration) {
    agents.finishModel(id, status, input, output, error, duration);
  }

  public void checkpoint(
      String runId, CheckpointStage stage, int rounds, int tools, long elapsed, String result) {
    runs.checkpoint(runId, stage, rounds, tools, elapsed, result);
  }

  /** 创建只读工作区的审查前检查点；摘要由工作区服务在分析边界取得。 */
  public void workspaceReviewCheckpoint(
      String runId, int rounds, int tools, long elapsed, WorkspaceReviewCheckpoint payload) {
    runs.workspaceReviewCheckpoint(runId, rounds, tools, elapsed, payload);
  }

  /** 检查点 JSON 不含文件正文；格式不合法时拒绝恢复。 */

  /** 已完成分析的引用与摘要；结果正文保留在原 Agent 调用中。 */
  public record AnalysisSource(UUID agentId, String result) {}

  /** 检查点复用的真实分析来源，不把祖先 Agent 当成本次调用。 */
  public record CheckpointAnalysisSource(UUID runId, UUID agentId, String result) {}

  /** 拒绝跨运行引用、损坏摘要和已经改变的分析结果。 */

  /** 解析旧版本运行引用或新版祖先引用，并核验运行链与复用节点。 */

  /** 保存引用前复验祖先关系，避免凭模型输出构造来源。 */

  public void interruptRunning() {
    runs.interruptRunning();
  }

  public Map<String, Object> overview() {
    return runs.overview();
  }

  /** 运行概况按当前会话可读取的任务过滤，不返回全库状态计数。 */
  public Map<String, Object> overview(List<String> taskIds) {
    return runs.overview(taskIds);
  }
}
