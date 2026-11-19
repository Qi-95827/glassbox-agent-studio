import React, { useState } from 'react';
import { Button, InputNumber, Select } from 'antd';

/** 待确认计划的预算和依赖调整、候选校验与版本差异。 */
export default function PlanAdjustmentEditor({ detail, api, onError }) {
  const nodes = detail.planNodes ?? [];
  const plan = detail.workflowPlan;
  const [budgets, setBudgets] = useState(() => Object.fromEntries(nodes.map(node => [node.key, {
    maxModelCalls: node.budget?.maxModelCalls ?? null,
    maxToolCalls: node.budget?.maxToolCalls ?? null
  }])));
  const [dependencies, setDependencies] = useState(() => Object.fromEntries(nodes.map(node => [node.key,
    node.dependencyIds.map(id => nodes.find(item => item.id === id)?.key).filter(Boolean)
  ])));
  const [proposal, setProposal] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  if (!plan || !plan.reviewRequired || plan.approvedAt || detail.run.STATUS !== 'PENDING') return null;

  const propose = async () => {
    setBusy(true);
    try {
      const changes = nodes.map(node => ({
        key: node.key,
        maxModelCalls: budgets[node.key]?.maxModelCalls ?? null,
        maxToolCalls: budgets[node.key]?.maxToolCalls ?? null,
        dependencies: dependencies[node.key] || []
      }));
      const saved = await api(`/runs/${detail.run.ID}/plan-proposals`, {
        method: 'POST',
        body: JSON.stringify({ changes, expectedBaseSha256: plan.graphSha256 })
      });
      setProposal(saved);
      setError(null);
    } catch (e) { setError(e.message); if (onError) onError(e.message); }
    finally { setBusy(false); }
  };
  const apply = async () => {
    if (!proposal) return;
    setBusy(true);
    try {
      await api(`/runs/${detail.run.ID}/plan-proposals/${proposal.id}/apply`, { method: 'POST' });
      setProposal(null);
      setError(null);
    } catch (e) { setError(e.message); if (onError) onError(e.message); }
    finally { setBusy(false); }
  };

  return <div className="result-box">
    <strong>调整候选计划</strong>
    <p className="hint">只能调整节点预算和依赖；节点身份、授权、Agent 定义仍由服务端校验。应用后仍需确认计划才执行。</p>
    {nodes.map(node => <div key={node.key} style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 6 }}>
      <span style={{ minWidth: 180 }}>{node.label || node.key}</span>
      <InputNumber aria-label={`${node.key} 模型调用上限`} min={1} max={20} placeholder="模型"
        value={budgets[node.key]?.maxModelCalls}
        onChange={value => setBudgets(current => ({ ...current, [node.key]: { ...current[node.key], maxModelCalls: value ?? null } }))}/>
      <InputNumber aria-label={`${node.key} 工具调用上限`} min={0} max={20} placeholder="工具"
        value={budgets[node.key]?.maxToolCalls}
        onChange={value => setBudgets(current => ({ ...current, [node.key]: { ...current[node.key], maxToolCalls: value ?? null } }))}/>
      <Select mode="multiple" aria-label={`${node.key} 前置依赖`} style={{ minWidth: 220 }}
        placeholder="前置节点" value={dependencies[node.key] || []}
        onChange={value => setDependencies(current => ({ ...current, [node.key]: value }))}
        options={nodes.filter(item => item.key !== node.key).map(item => ({ value: item.key, label: item.label || item.key }))}/>
    </div>)}
    <Button style={{ marginTop: 8 }} loading={busy} onClick={propose}>校验并保存候选</Button>
    {error && <p className="hint">调整未通过：{error}</p>}
    {proposal && <div style={{ marginTop: 8 }}>
      <p>候选 v{proposal.version} · {proposal.proposalSha256?.slice(0, 16)}…</p>
      {proposal.diff?.length === 0 ? <p className="hint">与当前计划没有字段差异。</p> : proposal.diff?.map(change =>
        <p key={change.key} className="hint">{change.label}：模型 {change.beforeMaxModelCalls ?? '未设置'} → {change.afterMaxModelCalls ?? '未设置'}；
          工具 {change.beforeMaxToolCalls ?? '未设置'} → {change.afterMaxToolCalls ?? '未设置'}
          {change.addedDependencies?.length ? `；新增依赖 ${change.addedDependencies.join('、')}` : ''}
          {change.removedDependencies?.length ? `；移除依赖 ${change.removedDependencies.join('、')}` : ''}
        </p>)}
      <Button type="primary" loading={busy} onClick={apply}>应用候选并保留版本</Button>
    </div>}
  </div>;
}
