import React from 'react';
import { Alert, Tag } from 'antd';

const short = id => id ? id.slice(0, 8) : '—';
const when = value => value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '未记录';
const toolNames = { SIMULATED_INSPECTION: '模拟检查', WORKSPACE_READ: '读取授权文件', WORKSPACE_SEARCH: '搜索授权文件', WORKSPACE_WRITE: '修改授权文件' };
const checkNames = { RUNNING: '检查中', PASSED: '通过', FAILED: '未通过', BLOCKED: '无法判定', INTERRUPTED: '中断' };
const roleNames = { ANALYST: '分析 Agent', CODER: '开发 Agent', REVIEWER: '审查 Agent' };
const activityNames = { PENDING: '待执行', RUNNING: '运行中', COMPLETED: '已完成', REUSED: '复用历史结果', FAILED: '失败', CANCELLED: '已取消', LIMITED: '已超限', INTERRUPTED: '已中断', SKIPPED: '未执行' };

/** 单节点下钻：只展示该节点、尝试和实际调用记录，不复制其他节点正文。 */
export default function NodeExecutionDetail({ detail, Status, stopCauseLabels = {}, eventNames = {} }) {
  const node = detail?.node;
  if (!node) return null;
  const attempts = detail.attempts ?? [];
  const checks = detail.checks ?? [];
  return <div className="node-detail">
    <div className="node-detail-meta">
      <span>序号 {node.sequence}</span>
      {node.key && <span>键 {node.key}</span>}
      {node.phase && <span>阶段 {node.phase}</span>}
      <span>{node.kind === 'SYSTEM_CHECK' ? '系统检查节点' : 'Agent 节点'}</span>
      {node.role && <span>角色 {roleNames[node.role] || node.role}</span>}
      <span>{!node.planVersionId ? '历史计划未记录权限'
        : node.workspaceWrite ? '可读写授权文件' : node.workspaceRead ? '可读取授权文件' : '仅接收上游结果'}</span>
      {(node.maxModelCalls != null || node.maxToolCalls != null) && <span>
        节点上限：模型 {node.maxModelCalls ?? '未设置'} · 工具 {node.maxToolCalls ?? '未设置'}
      </span>}
      <span>开始 {when(node.startedAt)} · 结束 {when(node.finishedAt)}</span>
    </div>
    <p className="hint">
      {node.kind === 'SYSTEM_CHECK'
        ? `检查引用：${node.checkId ? `${node.checkId} v${node.checkVersion}` : '未记录'}`
        : `定义引用：${node.definitionId ? `${node.definitionId} v${node.definitionVersion}` : '历史记录未保存'}`}
      {node.agentId && ` · 实例 #${short(node.agentId)}`}
      {node.sourceAgentId && ` · 复用来源实例 #${short(node.sourceAgentId)}`}
    </p>
    <div className="node-dependencies">
      <strong>前置步骤</strong>
      {detail.dependencies?.length
        ? detail.dependencies.map(dependency => <Tag key={dependency.nodeId}>
          {dependency.label || roleNames[dependency.role] || `#${short(dependency.nodeId)}`} · {activityNames[dependency.status] || dependency.status || '未记录'}
        </Tag>)
        : <span className="hint">无前置步骤</span>}
    </div>
    {detail.result && <div className="result-box node-result">
      <div className="result-title">节点结果（模型输出或检查摘要）</div>
      <p>{detail.result}</p>
      <p className="hint">模型结论不等于证据验证；以下工具、检查和交接记录分别展示。</p>
    </div>}

    <div className="node-section">
      <div className="node-section-title"><strong>尝试与调用</strong><span>{attempts.length} 次尝试</span></div>
      {attempts.length === 0 && <p className="hint">{node.kind === 'SYSTEM_CHECK' ? '系统检查不创建 Agent 实例。' : '尚无 Agent 尝试记录。'}</p>}
      {attempts.map(attempt => <details className="agent-card handoff" key={attempt.id} open={attempts.length === 1}>
        <summary>
          尝试 {attempt.number} · 实例 #{short(attempt.agentId)} · {activityNames[attempt.status] || attempt.status}
          {attempt.stopCause ? ` · ${stopCauseLabels[attempt.stopCause] || attempt.stopCause}` : ''}
          {` · 模型 ${attempt.models?.length ?? 0} 次 · 工具 ${attempt.tools?.length ?? 0} 次`}
        </summary>
        <p>尝试 #{short(attempt.id)} · 开始 {when(attempt.startedAt)} · 更新 {when(attempt.updatedAt)}</p>
        {attempt.result && <p>实例结果：{attempt.result}</p>}
        {attempt.models?.length > 0 && <div className="call-list">
          <strong>模型调用</strong>
          {attempt.models.map(call => <div className="call-row" key={call.id}>
            <span>{call.name === 'mock' ? '模拟模型' : call.name} · {activityNames[call.status] || call.status}</span>
            <span>输入 Token {call.inputTokens ?? '未知'} · 输出 Token {call.outputTokens ?? '未知'} · {call.durationMs == null ? '耗时未知' : `${call.durationMs} 毫秒`}</span>
            {call.errorMessage && <span className="call-error">{call.errorMessage}</span>}
          </div>)}
        </div>}
        {attempt.tools?.length > 0 && <div className="call-list">
          <strong>工具调用</strong>
          {attempt.tools.map(call => <div className="call-row" key={call.id}>
            <span>{toolNames[call.name] || call.name} {call.version ? `v${call.version}` : ''} · {activityNames[call.status] || call.status}</span>
            <span>{call.durationMs == null ? '耗时未知' : `${call.durationMs} 毫秒`}{call.resourceVersion ? ' · 已记录文件版本' : ''}</span>
            {call.result && <span className="call-result">结果：{call.result}</span>}
            {call.errorMessage && <span className="call-error">{call.errorMessage}</span>}
          </div>)}
        </div>}
        {!attempt.models?.length && !attempt.tools?.length && <p className="hint">此尝试没有模型或工具调用记录。</p>}
      </details>)}
    </div>

    <div className="node-section">
      <div className="node-section-title"><strong>系统检查</strong><span>{checks.length} 次记录</span></div>
      {checks.length === 0 && <p className="hint">此节点没有系统检查记录。</p>}
      {checks.map(check => <div className="agent-card" key={check.id}>
        <div className="agent-text">
          <strong>检查 {checkNames[check.status] || check.status} · {check.checkId} v{check.checkVersion} · 第 {check.attemptNo} 次</strong>
          <small>开始 {when(check.startedAt)} · 结束 {when(check.finishedAt)}</small>
          {check.summary && <small>摘要：{check.summary}</small>}
          {check.evidence && <small>证据：{check.evidence}</small>}
          {check.externalEvidenceId && <small>外部证据 #{short(check.externalEvidenceId)}
            {check.evidenceCurrent === false ? ' · 证据已过期' : check.evidenceCurrent === true ? ' · 证据仍对应当前工作区' : ' · 当前有效性未记录'}
          </small>}
          {check.inputs?.length > 0 && <small>输入来源：{check.inputs.map(input =>
            `${input.sourceKind === 'SYSTEM_CHECK' ? '检查' : '尝试'} #${short(input.sourceAttemptId || input.sourceCheckId)} → #${short(input.dependencyNodeId)}`).join('；')}</small>}
          {check.inputsSha256 && <small>输入摘要：{check.inputsSha256.slice(0, 16)}…</small>}
        </div>
        <Status value={check.status} label={checkNames[check.status]} />
      </div>)}
    </div>

    <div className="node-section">
      <div className="node-section-title"><strong>交接与证据</strong><span>接收 {detail.inboundHandoffs?.length ?? 0} · 发出 {detail.outboundHandoffs?.length ?? 0}</span></div>
      {(!detail.inboundHandoffs?.length && !detail.outboundHandoffs?.length) && <p className="hint">此节点没有结构化交接记录。</p>}
      {(detail.inboundHandoffs ?? []).map(handoff => <div className="agent-card" key={`in:${handoff.senderAttemptId}:${handoff.receiverAttemptId}`}>
        <div className="agent-text">
          <strong>接收 {handoff.dependencyNodeLabel || handoff.dependencyNodeId} 的结果</strong>
          <small>来源尝试 #{short(handoff.senderAttemptId)} · 实例 #{short(handoff.senderAgentId)} · {when(handoff.acceptedAt)}</small>
          {handoff.toolEvidence?.map(evidence => <small key={evidence.toolCallId}>
            工具证据：{toolNames[evidence.tool] || evidence.tool} #{short(evidence.toolCallId)}
            {evidence.resourceVersion ? ' · 含文件版本' : ''}
          </small>)}
        </div>
      </div>)}
      {(detail.outboundHandoffs ?? []).map(handoff => <div className="agent-card" key={`out:${handoff.receiverAttemptId}:${handoff.senderAttemptId}`}>
        <div className="agent-text">
          <strong>已交给 {handoff.receiverNodeLabel || handoff.receiverNodeId}</strong>
          <small>接收尝试 #{short(handoff.receiverAttemptId)} · 实例 #{short(handoff.receiverAgentId)} · {when(handoff.acceptedAt)}</small>
        </div>
      </div>)}
    </div>

    <div className="node-section">
      <div className="node-section-title"><strong>节点事件</strong><span>{detail.nodeEvents?.length ?? 0} 条</span></div>
      {detail.nodeEvents?.length
        ? <div className="node-events">{detail.nodeEvents.map(event => <div key={event.id}>
          <span>{eventNames[event.type] || event.type}</span>
          <time>{when(event.occurredAt)}</time>
          <p>{event.message}
            {event.reasonCode ? ` · 原因编码 ${event.reasonCode}` : ''}
            {event.modelCallId ? ` · 模型 #${short(event.modelCallId)}` : ''}
            {event.toolCallId ? ` · 工具 #${short(event.toolCallId)}` : ''}
          </p>
        </div>)}</div>
        : <p className="hint">此节点没有结构化归属的事件；运行级事件在下方时间线显示。</p>}
    </div>
    {detail.dependencies?.some(dependency => !dependency.status) && <Alert type="info" showIcon
      message="部分前置节点未在计划中记录；页面不推测其状态。" />}
  </div>;
}
