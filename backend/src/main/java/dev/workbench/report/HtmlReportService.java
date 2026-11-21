package dev.workbench.report;

import dev.workbench.bug.BugIntakeService;
import dev.workbench.bug.BugService;
import dev.workbench.planning.PlanService;
import dev.workbench.memory.MemoryService;
import dev.workbench.observability.RunMetricsService;
import dev.workbench.domain.Handoff;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunCountStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import dev.workbench.task.WorkflowExecutionService;
import dev.workbench.workspace.ApprovalService;
import dev.workbench.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/** 从持久化的运行事实生成静态 HTML；不可信内容只作为文本展示。 */
@Service
public class HtmlReportService {
  private final RunStateStore repository;
  private final TaskRecordService tasks;
  private final AgentAssemblyRecordService agents;
  private final RunEvidenceRecordService evidence;
  private final RunCountStore counts;
  private final WorkspaceService workspaces;
  private final BugService bugs;
  private final BugIntakeService intakes;
  private final ApprovalService approvals;
  private final PlanService plans;
  private final RunMetricsService metrics;
  private final MemoryService memories;
  private final WorkflowExecutionService workflows;

  public HtmlReportService(
      RunStateStore repository,
      TaskRecordService tasks,
      AgentAssemblyRecordService agents,
      RunEvidenceRecordService evidence,
      RunCountStore counts,
      WorkspaceService workspaces,
      BugService bugs,
      BugIntakeService intakes,
      ApprovalService approvals,
      PlanService plans,
      RunMetricsService metrics,
      MemoryService memories,
      WorkflowExecutionService workflows) {
    this.repository = repository;
    this.tasks = tasks;
    this.agents = agents;
    this.evidence = evidence;
    this.counts = counts;
    this.workspaces = workspaces;
    this.bugs = bugs;
    this.intakes = intakes;
    this.approvals = approvals;
    this.plans = plans;
    this.metrics = metrics;
    this.memories = memories;
    this.workflows = workflows;
  }

  /** 仅输出固定枚举和计数，不读取或拼接用户、模型和工具的自由文本。 */
  public String renderSummary(String runId) {
    var run = repository.runSnapshot(runId);
    var nodes = plans.find(runId);
    var tokens = counts.reportedTokens(runId);
    StringBuilder html =
        new StringBuilder(
            "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta"
                + " name=\"viewport\" content=\"width=device-width\"><title>Agent 运行精简报告</title>"
                + "<style>body{font:15px/1.7 system-ui,sans-serif;max-width:900px;margin:40px auto;"
                + "padding:0 20px;color:#24304b}section{border:1px solid #dce3ef;padding:16px;"
                + "margin:16px 0;border-radius:8px}</style></head><body><h1>Agent 运行精简报告</h1>"
                + "<p>仅包含受控状态和统计，不包含任务、模型、工具正文或代码；统计信息仍可能被关联，请在分享前检查。</p>");
    html.append("<section><h2>运行状态</h2><p>状态：")
        .append(escape(run.status().name()))
        .append("；已记录运行时间（毫秒）：")
        .append(run.elapsedMs())
        .append("；模型轮次（含继承）：")
        .append(run.rounds())
        .append("；工具次数（含继承）：")
        .append(run.tools())
        .append("。</p></section>");
    html.append("<section><h2>协作步骤</h2><ul>");
    for (var node : nodes)
      html.append("<li>")
          .append(escape(node.label() == null ? node.role().displayName() : node.label()))
          .append(" · ")
          .append(escape(node.status().name()))
          .append("</li>");
    if (nodes.isEmpty()) html.append("<li>未记录步骤。</li>");
    html.append("</ul></section>");
    appendBudgetPolicies(
        html,
        tasks.roleBudgets(run.taskId().toString()),
        tasks.toolBudgets(run.taskId().toString()));
    html.append("<section><h2>调用统计</h2><p>本运行 Agent 调用：")
        .append(agents.agents(runId).size())
        .append("；模型调用：")
        .append(agents.models(runId).size())
        .append("；工具调用：")
        .append(agents.tools(runId).size())
        .append("。</p><p>本运行及恢复祖先的已报告 Token：")
        .append(tokens.knownTokens())
        .append("；用量未知的模型调用：")
        .append(tokens.unknownCalls())
        .append("。部分用量未知时，已报告值不是总量。</p></section></body></html>");
    return html.toString();
  }

  public String render(String runId) {
    Map<String, Object> run = repository.run(runId),
        task = tasks.task(run.get("TASK_ID").toString());
    var taskDefinition = tasks.taskDefinition(run.get("TASK_ID").toString());
    StringBuilder html =
        new StringBuilder(
            "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta"
                + " name=\"viewport\" content=\"width=device-width\"><title>Agent"
                + " 运行报告</title><style>body{font:15px/1.7"
                + " system-ui,sans-serif;max-width:900px;margin:40px auto;padding:0"
                + " 20px;color:#24304b}section{border:1px solid #dce3ef;padding:16px;margin:16px"
                + " 0;border-radius:8px}pre{white-space:pre-wrap;overflow-wrap:anywhere}li{margin:8px"
                + " 0}</style></head><body><h1>Agent 运行报告</h1><p>这是生成时读取的静态记录，各部分并非跨数据库和 Git"
                + " 的一致性快照。报告未自动脱敏，分享前请检查内容。</p>");
    html.append("<section><h2>任务与状态</h2><p>目标：")
        .append(escape(task.get("GOAL")))
        .append("</p><p>运行：")
        .append(escape(run.get("ID")))
        .append("；状态：")
        .append(escape(run.get("STATUS")))
        .append("；停止原因分类：")
        .append(run.get("STOP_CAUSE") == null ? "未记录" : escape(run.get("STOP_CAUSE")))
        .append("；模型模式：")
        .append(escape(task.get("MODEL_MODE")))
        .append("；用户选择的入口：")
        .append(escape(taskDefinition.entryKind().label()))
        .append("（类型本身不证明产物或测试完成）")
        .append("</p><p>结果与原因：</p><pre>")
        .append(escape(run.get("RESULT")))
        .append("\n")
        .append(escape(run.get("REASON")))
        .append("</pre></section>");
    appendRequirements(html, run.get("TASK_ID").toString());
    if (task.get("BUG_ID") != null) appendBugDelivery(html, runId);
    appendBudgetPolicies(html, taskDefinition.roleBudgets(), taskDefinition.toolBudgets());
    appendPlanAndAgents(html, runId);
    appendWorkflowMetrics(html, runId);
    appendMemoryReferences(html, runId);
    appendHandoffs(html, runId);
    appendCalls(html, runId);
    html.append("<section><h2>执行记录</h2><p>以下内容来自本次运行记录，模拟工具、模型推断和真实验证必须区分。</p><ul>");
    for (Map<String, Object> event : evidence.events(runId))
      html.append("<li>")
          .append(escape(event.get("CREATED_AT")))
          .append(" · ")
          .append(escape(event.get("EVENT_TYPE")))
          .append("：")
          .append(escape(event.get("MESSAGE")))
          .append("</li>");
    html.append("</ul></section><section><h2>调用统计</h2><p>模型轮次：")
        .append(escape(run.get("ROUNDS")))
        .append(" / ")
        .append(run.get("MAX_ROUNDS") == null ? "未设置" : escape(run.get("MAX_ROUNDS")))
        .append("；工具次数：")
        .append(escape(run.get("TOOLS")))
        .append(" / ")
        .append(run.get("MAX_TOOLS") == null ? "未设置" : escape(run.get("MAX_TOOLS")))
        .append("；单工具上限：")
        .append(run.get("MAX_PER_TOOL") == null ? "未设置" : escape(run.get("MAX_PER_TOOL")))
        .append("；单 Agent 模型调用上限：")
        .append(run.get("MAX_AGENT_ROUNDS") == null ? "未设置" : escape(run.get("MAX_AGENT_ROUNDS")))
        .append("；单 Agent 工具调用上限：")
        .append(run.get("MAX_AGENT_TOOLS") == null ? "未设置" : escape(run.get("MAX_AGENT_TOOLS")))
        .append("；已报告 Token 上限：")
        .append(
            run.get("MAX_REPORTED_TOKENS") == null ? "未设置" : escape(run.get("MAX_REPORTED_TOKENS")))
        .append("；连续相同调用上限：")
        .append(
            run.get("MAX_IDENTICAL_TOOLS") == null ? "未设置" : escape(run.get("MAX_IDENTICAL_TOOLS")))
        .append("；已记录耗时（毫秒）：")
        .append(escape(run.get("ELAPSED_MS")))
        .append("；费用：未计算。Token 上限为响应后阈值，单次调用可能超额。</p></section>");
    appendCheckpoints(html, runId);
    Object workspaceId = task.get("WORKSPACE_ID");
    if (workspaceId != null) {
      html.append("<section><h2>工作区状态</h2><p>以下是报告生成时获取的状态；不自动代表代码已测试或合并。</p>");
      try {
        var status = workspaces.status(workspaceId.toString());
        html.append("<p>目标分支变化：")
            .append(status.baseChanged() ? "是" : "否")
            .append("</p><pre>")
            .append(escape(status.changes()))
            .append("\n")
            .append(escape(status.diff()))
            .append("</pre>");
      } catch (RuntimeException e) {
        html.append("<p>无法读取工作区状态，请重新检查。</p>");
      }
      html.append("</section>");
      html.append("<section><h2>工作区审查记录</h2><p>审查只记录用户对特定差异的决定，不代表已经提交或合并。</p>");
      for (Map<String, Object> approval : approvals.list(workspaceId.toString()))
        html.append("<p>")
            .append(escape(approval.get("CREATED_AT")))
            .append(" · ")
            .append(escape(approval.get("STATUS")))
            .append("</p>");
      html.append("</section>");
      html.append("<section><h2>本次运行的检测证据</h2><p>仅包含明确关联此运行的测试；代码状态摘要对应检测时的工作区，不自动证明修复完成。</p>");
      var runChecks = bugs.checksForRun(runId);
      if (runChecks.isEmpty()) html.append("<p>没有可确认归属的检测记录。</p>");
      for (var check : runChecks)
        html.append("<p>检测 ID：")
            .append(escape(check.get("ID")))
            .append(" · 类型：")
            .append(escape(check.get("KIND")))
            .append(" · 结果：")
            .append(escape(check.get("STATUS")))
            .append(" · 代码状态摘要：")
            .append(escape(check.get("WORKSPACE_FINGERPRINT")))
            .append("</p><pre>")
            .append(escape(check.get("OUTPUT")))
            .append("</pre>");
      html.append("</section>");
      html.append("<section><h2>工作区验证历史</h2><p>以下为工作区关联记录，不一定与当前代码版本一致，不能自动作为本次运行验收。</p>");
      for (Map<String, Object> check : bugs.checks(workspaceId.toString()))
        html.append("<p>")
            .append(escape(check.get("CREATED_AT")))
            .append(" · ")
            .append(escape(check.get("KIND")))
            .append(" · ")
            .append(escape(check.get("STATUS")))
            .append("</p><pre>")
            .append(escape(check.get("OUTPUT")))
            .append("</pre>");
      html.append("</section>");
    }
    html.append(
        "<section><h2>未验证事项</h2><p>模拟工具不是实际代码测试；模型意见不能代替人工审查。此报告不自动判定 Bug"
            + " 已修复或代码已合并。</p></section></body></html>");
    return html.toString();
  }

  /** 完整报告的结构不变，但把常见绝对路径、邮箱和凭据样式文本替换为脱敏占位。 */
  public String renderRedacted(String runId) {
    return redact(render(runId));
  }

  public static String redact(String html) {
    return SensitiveTextRedactor.redact(html);
  }

  /** 分诊和交付结论来自持久化记录；报告生成时只读组合，不重新执行测试。 */
  private void appendBugDelivery(StringBuilder html, String runId) {
    Map<String, Object> delivery = intakes.delivery(runId);
    BugIntakeService.Intake intake = (BugIntakeService.Intake) delivery.get("intake");
    html.append("<section><h2>Bug 分诊与交付</h2>");
    if (intake == null) {
      html.append("<p>本次运行没有保存 Bug 原始描述记录，无法生成完整交付结论。</p></section>");
      return;
    }
    html.append("<p>原始描述：</p><pre>")
        .append(escape(intake.originalDescription()))
        .append("</pre><p>模拟 Bug 定义：</p><pre>")
        .append(escape(intake.bugDescription()))
        .append("</pre><p>来源：")
        .append(escape(intake.source()))
        .append(" v")
        .append(intake.sourceVersion())
        .append("；目标用例：")
        .append(escape(intake.expectedTestId()))
        .append("；提交时间：")
        .append(escape(intake.createdAt()))
        .append("；提交序号：")
        .append(intake.submissionSeq())
        .append("。</p><p>预期现象：")
        .append(escape(intake.expectedSymptom()))
        .append("</p>");
    if (intake.duplicateOf() != null)
      html.append("<p>同来源同内容重复提交；首次记录：").append(escape(intake.duplicateOf())).append("。</p>");
    else if (intake.previousIntakeId() != null)
      html.append("<p>同来源内容已变化；上一记录：").append(escape(intake.previousIntakeId())).append("。</p>");
    else html.append("<p>该来源的首次提交。</p>");
    html.append("<p>分诊：")
        .append(escape(intake.triageStatus().label()))
        .append("（")
        .append(escape(intake.triageStatus().name()))
        .append("）；原因：")
        .append(escape(intake.triageReason()))
        .append("。</p>");
    appendDeliveryCheck(html, "复现检查", delivery.get("reproduction"));
    appendDeliveryCheck(html, "回归检查", delivery.get("regression"));
    Object currentFingerprint = delivery.get("currentFingerprint");
    Object regressionFingerprint = delivery.get("regressionFingerprint");
    Object regressionCurrent = delivery.get("regressionEvidenceCurrent");
    html.append("<p>当前代码状态摘要：")
        .append(currentFingerprint == null ? "无法读取" : escape(currentFingerprint))
        .append("；回归记录代码状态摘要：")
        .append(regressionFingerprint == null ? "未记录" : escape(regressionFingerprint))
        .append("；回归证据对应当前版本：")
        .append(
            regressionCurrent == null ? "尚无回归" : Boolean.TRUE.equals(regressionCurrent) ? "是" : "否")
        .append("。</p>");
    html.append("<p>交付结论：")
        .append(escape(delivery.get("conclusionLabel")))
        .append("；")
        .append(escape(delivery.get("conclusionReason")))
        .append("</p><p>该结论不自动合并、关闭 Bug 或替代用户审查。</p></section>");
  }

  @SuppressWarnings("unchecked")
  private void appendDeliveryCheck(StringBuilder html, String title, Object value) {
    html.append("<p>").append(title).append("：");
    if (!(value instanceof Map<?, ?> check)) {
      html.append("未执行或未记录。</p>");
      return;
    }
    html.append("检测 ")
        .append(escape(check.get("ID")))
        .append(" · 结果 ")
        .append(escape(check.get("STATUS")))
        .append(" · 代码状态摘要 ")
        .append(escape(check.get("WORKSPACE_FINGERPRINT")))
        .append("</p>");
    Object cases = check.get("CASES");
    if (cases instanceof List<?> list && !list.isEmpty()) {
      html.append("<ul>");
      for (Object item : list) {
        Map<String, Object> test = (Map<String, Object>) item;
        html.append("<li>")
            .append(escape(test.get("TEST_ID")))
            .append(" · ")
            .append(escape(test.get("RESULT")));
        if (test.get("MESSAGE") != null && !test.get("MESSAGE").toString().isBlank())
          html.append(" · ").append(escape(test.get("MESSAGE")));
        html.append("</li>");
      }
      html.append("</ul>");
    }
  }

  /** 引用只证明装配阶段选中该版本，其他报告字段仍可能包含敏感内容。 */
  private void appendMemoryReferences(StringBuilder html, String runId) {
    var load = memories.loadForRun(runId);
    html.append(
        "<section><h2>项目背景引用</h2><p>仅表示装配时选中，不证明模型已接收或遵循。" + "引用不包含正文，报告其他部分仍可能包含敏感内容。</p>");
    if (!load.recorded()) {
      html.append("<p>未记录；历史数据缺失不代表没有使用。</p>");
    } else {
      html.append("<p>装配时间：")
          .append(escape(load.loadedAt()))
          .append("；声明数量：")
          .append(load.declaredCount())
          .append("；现存引用数：")
          .append(load.references().size())
          .append("。</p>");
      if (load.integrity() == dev.workbench.memory.MemoryLoadIntegrity.INCONSISTENT)
        html.append("<p>引用数量或顺序不一致，请核对记录；不能推断原始装配内容。</p>");
      html.append("<ul>");
      for (var reference : load.references()) {
        html.append("<li>序号 ")
            .append(reference.sequence())
            .append(" · 记忆 ")
            .append(escape(reference.memoryId()))
            .append(" · 类别 ")
            .append(escape(reference.category()))
            .append(" · 版本 ")
            .append(reference.version())
            .append("</li>");
      }
      if (load.references().isEmpty()
          && load.integrity() == dev.workbench.memory.MemoryLoadIntegrity.CONSISTENT)
        html.append("<li>装配记录显示没有选中项目背景。</li>");
      html.append("</ul>");
    }
    html.append("</section>");
  }

  /** 策略仅输出系统枚举和数值，可用于完整及字段白名单报告。 */
  private void appendBudgetPolicies(
      StringBuilder html,
      Map<dev.workbench.domain.AgentRole, dev.workbench.domain.RoleBudget> roles,
      Map<dev.workbench.domain.ToolKind, Integer> tools) {
    html.append("<section><h2>角色与工具限额</h2><p>这些上限与运行总预算同时生效，不授予工具权限。</p><ul>");
    roles.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              var budget = entry.getValue();
              html.append("<li>")
                  .append(escape(entry.getKey().displayName()))
                  .append("：模型 ")
                  .append(budget.maxModelCalls() == null ? "未设置" : budget.maxModelCalls())
                  .append("，工具 ")
                  .append(budget.maxToolCalls() == null ? "未设置" : budget.maxToolCalls())
                  .append("。</li>");
            });
    tools.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry ->
                html.append("<li>")
                    .append(escape(entry.getKey().recordName()))
                    .append("：")
                    .append(entry.getValue())
                    .append(" 次。</li>"));
    if (roles.isEmpty() && tools.isEmpty()) html.append("<li>未额外配置角色或工具类型上限。</li>");
    html.append("</ul></section>");
  }

  private void appendRequirements(StringBuilder html, String taskId) {
    var task = tasks.taskDefinition(taskId);
    html.append("<section><h2>任务约束与验收要求</h2><p>以下是用户提出的要求，不代表已经验证通过。</p><ul>");
    if (!task.fileScopes().isEmpty()) {
      for (String path : task.fileScopes())
        html.append("<li>授权文件：").append(escape(path)).append("</li>");
    }
    task.constraints()
        .forEach(value -> html.append("<li>约束：").append(escape(value)).append("</li>"));
    task.acceptanceCriteria()
        .forEach(value -> html.append("<li>待验证：").append(escape(value)).append("</li>"));
    if (task.constraints().isEmpty() && task.acceptanceCriteria().isEmpty())
      html.append("<li>未提供。</li>");
    html.append("</ul></section>");
  }

  private void appendPlanAndAgents(StringBuilder html, String runId) {
    var nodes = plans.find(runId);
    html.append("<section><h2>计划与 Agent</h2><p>计划节点是执行安排；复用历史结果不表示本次重新调用。</p><ul>");
    for (var node : nodes) {
      html.append("<li>步骤 ")
          .append(node.sequence())
          .append(" · ")
          .append(escape(node.label() == null ? node.role() : node.label()))
          .append(" · ")
          .append(escape(node.status()));
      if (node.systemCheck()) html.append(" · 系统检查：").append(escape(node.checkRef().toString()));
      if (!node.dependencyIds().isEmpty()) {
        html.append(" · 前置：");
        var names =
            node.dependencyIds().stream()
                .map(
                    id ->
                        nodes.stream()
                            .filter(candidate -> candidate.id().equals(id))
                            .findFirst()
                            .orElseThrow())
                .map(
                    prerequisite ->
                        prerequisite.label() == null
                            ? prerequisite.role().name()
                            : prerequisite.label())
                .toList();
        html.append(escape(String.join("、", names)));
      }
      if (node.agentId() != null) html.append(" · 本次实例：").append(escape(node.agentId()));
      if (node.sourceAgentId() != null)
        html.append(" · 复用来源：").append(escape(node.sourceAgentId()));
      html.append("</li>");
    }
    if (nodes.isEmpty()) html.append("<li>历史运行没有计划记录。</li>");
    html.append("</ul><p>以下技能仅表示已装配方法，不证明模型遵循。</p><ul>");
    var skills = agents.skills(runId);
    var toolGrants = agents.agentToolGrants(runId);
    for (var agent : agents.agents(runId)) {
      String agentId = agent.get("ID").toString();
      html.append("<li>")
          .append(escape(agent.get("ROLE_NAME")))
          .append(" · ")
          .append(escape(agentId))
          .append(" · 状态：")
          .append(escape(agent.get("STATUS")))
          .append(" · 角色版本：")
          .append(escape(agent.get("ROLE_VERSION")));
      var assigned =
          skills.stream().filter(skill -> skill.agentId().toString().equals(agentId)).toList();
      if (assigned.isEmpty()) html.append(" · 技能：无记录");
      else
        for (var skill : assigned)
          html.append(" · 技能：")
              .append(escape(skill.skillId()))
              .append(" v")
              .append(skill.version());
      var grant =
          toolGrants.stream().filter(item -> item.agentId().toString().equals(agentId)).findFirst();
      html.append(" · 装配时注册工具：");
      if (grant.isEmpty()) html.append("未记录");
      else if (grant.get().tools().isEmpty()) html.append("无");
      else
        html.append(
            escape(
                grant.get().toolVersions().stream()
                    .map(
                        tool ->
                            tool.tool().recordName()
                                + (tool.version() == null ? " 版本未知" : " v" + tool.version()))
                    .collect(java.util.stream.Collectors.joining("、"))));
      html.append("</li>");
    }
    html.append("</ul><p>工具装配不代表已经调用；实际调用仍需权限及参数校验。</p></section>");
  }

  /** 计划、检查与实际台账的汇总；不把未启动调用或未知用量推算成成功。 */
  private void appendWorkflowMetrics(StringBuilder html, String runId) {
    var summary = metrics.workflow(runId);
    html.append("<section><h2>计划、检查与调用统计</h2><p>计划节点：")
        .append(summary.nodes())
        .append("；")
        .append(
            summary.nodeStatusCounts().entrySet().stream()
                .map(entry -> entry.getKey().name() + " " + entry.getValue())
                .collect(java.util.stream.Collectors.joining("、")))
        .append("。系统检查记录：")
        .append(summary.checks())
        .append("；")
        .append(
            summary.checkStatusCounts().entrySet().stream()
                .map(entry -> entry.getKey().name() + " " + entry.getValue())
                .collect(java.util.stream.Collectors.joining("、")))
        .append("；未执行检查节点：")
        .append(summary.unexecutedChecks())
        .append("。</p><p>实际启动模型调用：")
        .append(summary.modelCalls())
        .append("；工具调用：")
        .append(summary.toolCalls())
        .append("；用量未知的模型调用：")
        .append(summary.unknownTokenCalls())
        .append("。未启动的调用不计入实际次数。</p><ul>");
    var checks = workflows.workflowChecks(runId);
    for (var check : checks)
      html.append("<li>检查 ")
          .append(escape(check.check().toString()))
          .append(" · ")
          .append(escape(check.status().name()))
          .append(" · ")
          .append(escape(check.summary() == null ? "无摘要" : check.summary()))
          .append(
              check.externalEvidenceId() == null
                  ? ""
                  : " · 外部证据：" + escape(check.externalEvidenceId()))
          .append(Boolean.FALSE.equals(check.evidenceCurrent()) ? " · 证据已过期" : "")
          .append("</li>");
    if (checks.isEmpty()) html.append("<li>没有已执行的系统检查。</li>");
    html.append("</ul></section>");
  }

  private void appendHandoffs(StringBuilder html, String runId) {
    html.append("<section><h2>结构化交接</h2><p>模型摘要和推断需结合证据核实。</p><ul>");
    var handoffs = evidence.handoffs(runId);
    for (var row : handoffs) {
      Handoff item = (Handoff) row.get("PAYLOAD");
      html.append("<li>")
          .append(escape(item.sender() == null ? null : item.sender().code()))
          .append(" → ")
          .append(escape(item.receiver() == null ? null : item.receiver().code()))
          .append(" · ID：")
          .append(escape(item.handoffId()))
          .append(" · 契约版本：")
          .append(escape(item.schemaVersion()))
          .append("<p>接收记录：")
          .append(
              ("AGENT_TRANSFER".equals(row.get("DIRECTION"))
                      || "DELEGATION".equals(row.get("DIRECTION")))
                  ? (row.get("RECEIVER_AGENT_ID") == null
                      ? "未记录（可能尚未启动或为历史记录）"
                      : "接收实例已启动；不证明模型收到输入")
                  : "不适用")
          .append("</p><p>摘要：")
          .append(escape(item.summary()))
          .append("</p><p>约束：")
          .append(escape(String.join("；", item.constraints())))
          .append("</p><p>证据运行：")
          .append(escape(item.evidenceRunId()))
          .append("；工具事件：")
          .append(escape(item.evidenceEventIds()))
          .append("；测试记录：")
          .append(item.testCheckIds() == null ? "未记录（旧契约）" : escape(item.testCheckIds()))
          .append("</p></li>");
    }
    if (handoffs.isEmpty()) html.append("<li>没有交接记录。</li>");
    html.append("</ul></section>");
  }

  private void appendCalls(StringBuilder html, String runId) {
    html.append("<section><h2>Agent 调用与用量</h2><p>已知 Token 仅累加有记录的值，未知不算作零；费用未计算。</p><ul>");
    var stats = metrics.agents(runId);
    for (var stat : stats) {
      html.append("<li>")
          .append(escape(stat.roleName()))
          .append(" · ")
          .append(escape(stat.agentId()))
          .append(" · 墙钟区间毫秒（含等待和暂停）：")
          .append(escape(stat.durationMs()))
          .append(" · 模型调用：")
          .append(stat.modelCalls())
          .append(" · 工具调用：")
          .append(stat.toolCalls())
          .append("<br>已知输入 Token：")
          .append(escape(stat.knownInputTokens()))
          .append("，未知 ")
          .append(stat.unknownInputCount())
          .append(" 次；已知输出 Token：")
          .append(escape(stat.knownOutputTokens()))
          .append("，未知 ")
          .append(stat.unknownOutputCount())
          .append(" 次；工具：")
          .append(escape(stat.toolNames()));
      for (var tool : stat.toolBreakdown()) {
        html.append("<br>分工具：")
            .append(escape(tool.name()))
            .append(" · 调用 ")
            .append(tool.calls())
            .append(" 次 · 状态 ")
            .append(escape(tool.statusCounts()))
            .append(" · 未知状态 ")
            .append(tool.unknownStatusCount())
            .append(" 次 · 已知耗时毫秒 ")
            .append(escape(tool.knownDurationMs()))
            .append(" · 耗时未知 ")
            .append(tool.unknownDurationCount())
            .append(" 次");
      }
      html.append("</li>");
    }
    if (stats.isEmpty()) html.append("<li>没有 Agent 调用记录。</li>");
    html.append("</ul><h3>工具调用</h3><ul>");
    var tools = agents.tools(runId);
    for (var tool : tools)
      html.append("<li>")
          .append(escape(tool.get("TOOL_NAME")))
          .append(" · 版本：")
          .append(tool.get("TOOL_VERSION") == null ? "未知" : escape(tool.get("TOOL_VERSION")))
          .append(" · ")
          .append(escape(tool.get("STATUS")))
          .append(" · Agent：")
          .append(escape(tool.get("AGENT_ID")))
          .append(" · 耗时毫秒：")
          .append(escape(tool.get("DURATION_MS")))
          .append("</li>");
    if (tools.isEmpty()) html.append("<li>没有工具调用记录。</li>");
    html.append("</ul></section>");
  }

  private void appendCheckpoints(StringBuilder html, String runId) {
    html.append("<section><h2>检查点</h2><p>有效性仅针对当前实现的安全边界，不表示外部副作用已回退。</p><ul>");
    var checkpoints = evidence.checkpoints(runId);
    for (var cp : checkpoints)
      html.append("<li>")
          .append(escape(cp.get("STAGE")))
          .append(" · ")
          .append(escape(cp.get("CREATED_AT")))
          .append(" · 可校验：")
          .append(Boolean.TRUE.equals(cp.get("VALID")) ? "是" : "否")
          .append("</li>");
    if (checkpoints.isEmpty()) html.append("<li>没有检查点记录。</li>");
    html.append("</ul></section>");
  }

  private String escape(Object value) {
    return HtmlUtils.htmlEscape(value == null ? "未记录" : value.toString(), "UTF-8");
  }
}
