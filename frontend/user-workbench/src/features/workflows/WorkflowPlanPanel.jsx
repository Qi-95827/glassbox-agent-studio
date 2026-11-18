import React from 'react';
import { Button } from 'antd';

const roles = { ANALYST: '分析 Agent', CODER: '开发 Agent', REVIEWER: '审查 Agent' };
const phases = { analysis: '分析', synthesis: '汇总', implementation: '修改', review: '审查', verification: '核对' };
const evidenceNames = {
  WORKSPACE_READ: '读取授权文件', WORKSPACE_SEARCH: '搜索授权文件', WORKSPACE_WRITE: '修改授权文件'
};
const title = node => node?.label || roles[node?.role] || node?.key || '未知步骤';
const checkStatusNames = { RUNNING: '检查中', PASSED: '通过', FAILED: '未通过', BLOCKED: '无法判定', INTERRUPTED: '中断' };
const short = id => id?.slice(0, 8);

export default function WorkflowPlanPanel({ detail, Status, stopCauseLabels, onApprove, approving, onRecover }) {
  const nodes = detail.planNodes ?? [];
  const plan = detail.workflowPlan;
  const attempts = detail.workflowAttempts ?? [];
  const checks = detail.workflowChecks ?? [];
  const handoffs = detail.workflowHandoffs ?? [];
  const byId = new Map(nodes.map(node => [node.id, node]));
  const waiting = plan?.reviewRequired && !plan.approvedAt && detail.run.STATUS === 'PENDING';
  const recovery = detail.workflowCheckpoints ?? [];

  return <>
    <div className="section-row">
      <h3>协作步骤</h3>
      <span>{plan ? `计划 v${plan.version} · ${plan.templateId} v${plan.templateVersion}`
        + (plan.maxAttempts > 1 ? ` · 每节点最多 ${plan.maxAttempts} 次尝试` : '') :
        nodes.length ? nodes.map(title).join(' → ') : '历史运行'}</span>
    </div>
    {detail.workflowMetrics && <p className="hint">
      计划节点 {detail.workflowMetrics.nodes}
      {' · '}完成 {detail.workflowMetrics.nodeStatusCounts?.COMPLETED ?? 0}
      {' · '}复用 {detail.workflowMetrics.nodeStatusCounts?.REUSED ?? 0}
      {' · '}失败 {detail.workflowMetrics.nodeStatusCounts?.FAILED ?? 0}
      {' · '}跳过 {detail.workflowMetrics.nodeStatusCounts?.SKIPPED ?? 0}
      {' · '}检查 {detail.workflowMetrics.checks}（通过 {detail.workflowMetrics.checkStatusCounts?.PASSED ?? 0}
      {' / '}未通过 {detail.workflowMetrics.checkStatusCounts?.FAILED ?? 0}
      {' / '}无法判定 {detail.workflowMetrics.checkStatusCounts?.BLOCKED ?? 0}）
      {' · '}模型调用 {detail.workflowMetrics.modelCalls}
      {' · '}工具调用 {detail.workflowMetrics.toolCalls}
      {' · '}未知用量 {detail.workflowMetrics.unknownTokenCalls}
    </p>}
    {waiting && <div className="result-box">
      <p>计划已保存，等待确认后执行。</p>
      <Button type="primary" loading={approving} onClick={() => onApprove(detail.run.ID, plan.id)}>
        确认并运行
      </Button>
    </div>}
    {plan && detail.run.STATUS === 'INTERRUPTED' && <div className="recovery-box">
      <strong>恢复工作流</strong>
      <p>可用边界会复用已完成节点并继承累计消耗。</p>
      {detail.task.MODEL_MODE === 'deepseek' && <p>恢复可能继续调用 DeepSeek 并产生费用。</p>}
      {recovery.length ? recovery.map(checkpoint => <Button key={checkpoint.id} size="small"
        disabled={!checkpoint.available} onClick={() => onRecover(checkpoint.id)}>
        {checkpoint.completedNodes ? `完成 ${checkpoint.completedNodes} 步后` : '首个步骤启动前'}
        {checkpoint.available ? ' · 恢复' : ' · 不可用'}
      </Button>) : <p>暂无可用检查点</p>}
    </div>}
    {nodes.map(node => {
      const dependencyNames = node.dependencyIds?.length
        ? node.dependencyIds.map(id => title(byId.get(id))).join('、')
        : roles[node.prerequisite];
      const nodeAttempts = attempts.filter(attempt => attempt.nodeId === node.id);
      const nodeChecks = checks.filter(check => check.nodeId === node.id);
      const receipts = handoffs.filter(handoff => handoff.receiverNodeId === node.id);
      return <div className="agent-card" key={node.id}>
        <div className="agent-avatar">{node.sequence}</div>
        <div className="agent-text">
          <strong>{title(node)}</strong>
          <small>{node.phase && `${phases[node.phase] || node.phase} · `}
            {node.kind === 'SYSTEM_CHECK'
              ? `系统检查：${node.checkRef ? `${node.checkRef.id} v${node.checkRef.version}` : '未记录'}`
              : `计划定义：${node.definitionRef ? `${node.definitionRef.id} v${node.definitionRef.version}` : '历史记录未保存'}`}
          </small>
          {plan && plan.scope !== 'TEXT_ONLY' && <small>{node.workspaceWrite ? '可读取或修改授权文件' :
            node.workspaceRead ? '可读取或搜索授权文件' : '仅接收上游结果'}</small>}
          {node.budget && <small>节点上限 · 模型 {node.budget.maxModelCalls ?? '未设置'} · 工具 {node.budget.maxToolCalls ?? '未设置'}</small>}
          <small>{dependencyNames ? `前置步骤：${dependencyNames}` : '无前置步骤'}
            {nodeAttempts.length === 0 && (node.agentId ? ` · 实例 #${short(node.agentId)}` :
              node.sourceAgentId ? ` · 复用来源实例 #${short(node.sourceAgentId)}` :
                node.kind === 'SYSTEM_CHECK' ? ' · 不创建 Agent 实例' : ' · 尚无 Agent 实例')}
          </small>
          {nodeChecks.map(check => <small key={check.id} title={check.summary || ''}>
            检查 {checkStatusNames[check.status] || check.status} · {check.check.id} v{check.check.version}
            {check.externalEvidenceId ? ` · 证据 #${short(check.externalEvidenceId)}` : ''}
            {check.evidenceCurrent === false ? ' · 证据已过期' : ''}
          </small>)}
          {nodeAttempts.map(attempt => <small key={attempt.id}>
            尝试 {attempt.number} #{short(attempt.id)} · 实例 #{short(attempt.agentId)} · <Status value={attempt.status} />
            {attempt.stopCause && ` · ${stopCauseLabels[attempt.stopCause] || attempt.stopCause}`}
          </small>)}
          {receipts.map(receipt => <small key={`${receipt.senderAttemptId}:${receipt.receiverAttemptId}`}>
            尝试 {nodeAttempts.find(attempt => attempt.id === receipt.receiverAttemptId)?.number ?? '—'}
            {' · '}已接收 {title(byId.get(receipt.dependencyNodeId))} · 实例 #{short(receipt.senderAgentId)}
            {receipt.toolEvidence?.map(evidence => <span key={evidence.toolCallId} title={evidence.toolCallId}>
              {' · '}{evidenceNames[evidence.tool] || evidence.tool} #{short(evidence.toolCallId)}
            </span>)}
          </small>)}
        </div>
        <Status value={node.status} label={node.status === 'PENDING' ? '待执行' : undefined} />
      </div>;
    })}
    {!nodes.length && <p className="hint">此运行没有步骤记录。</p>}
  </>;
}
