import React, { useEffect, useState } from 'react';
import { Button, Input, InputNumber, Select } from 'antd';

/** 任务 8 / 任务 4–7：执行页上的来源、授权、快照、费用和外部动作证据。 */
export default function RunExtensionsPanel({ detail, api }) {
  const [actions, setActions] = useState(detail.externalActions ?? []);
  const [sources, setSources] = useState(detail.sourceArtifacts ?? []);
  const [scopeState, setScopeState] = useState(detail.workspaceScope);
  const [sourceRun, setSourceRun] = useState('');
  const [sourceKind, setSourceKind] = useState('DOCUMENT');
  const [sourceVersion, setSourceVersion] = useState(null);
  const [targetVersion, setTargetVersion] = useState(detail.task?.VERSION ?? null);
  const [sourceError, setSourceError] = useState(null);
  useEffect(() => setActions(detail.externalActions ?? []), [detail.externalActions]);
  useEffect(() => setSources(detail.sourceArtifacts ?? []), [detail.sourceArtifacts]);
  useEffect(() => setScopeState(detail.workspaceScope), [detail.workspaceScope]);
  useEffect(() => setTargetVersion(detail.task?.VERSION ?? null), [detail.task?.VERSION]);
  const cost = detail.costEstimate;
  const route = detail.entryRoute;
  const scope = detail.workspaceScope;
  const snapshots = detail.snapshots ?? [];
  const lease = detail.recoveryLease;

  const revoke = async directory => {
    try {
      const updated = await api(`/runs/${detail.run.ID}/workspace-scope/revoke`, {
        method: 'POST', body: JSON.stringify({ directory })
      });
      setScopeState(current => ({
        ...current,
        DIRECTORY_SCOPES: updated.directories,
        EFFECTIVE_FILES: updated.effectiveFiles
      }));
      setSourceError(null);
    } catch (e) { setSourceError(e.message); }
  };

  const attachSource = async () => {
    try {
      const attached = await api(`/runs/${detail.run.ID}/source-artifacts`, {
        method: 'POST',
        body: JSON.stringify({
          sourceRunId: sourceRun.trim(), kind: sourceKind,
          expectedTargetTaskVersion: targetVersion, expectedSourceTaskVersion: sourceVersion
        })
      });
      setSources(rows => [...rows.filter(row => row.id !== attached.id), attached]);
      setSourceError(null);
    } catch (e) { setSourceError(e.message); }
  };

  const reconcile = async id => {
    try {
      const updated = await api(`/external-actions/${id}/reconcile`, { method: 'POST' });
      setActions(rows => rows.map(row => row.id === id ? updated : row));
    } catch (e) {
      setActions(rows => rows.map(row => row.id === id ? { ...row, resultEvidence: e.message } : row));
    }
  };

  return <>
    {route && <div className="result-box">
      <strong>入口路由 · {route.RULE_CODE}</strong>
      <p>实际模板：{route.TEMPLATE_ID || '兼容流程'}；入口：{route.ROUTED_KIND}</p>
      <p className="hint">决策摘要：{route.DECISION_SHA256?.slice(0, 16)}…；路由只解释选择，不授予权限。</p>
    </div>}
    {(sources.length > 0 || scope) && <div className="result-box">
      <strong>授权范围与既有成果</strong>
      {scope && <>
        <p>目录：{(scopeState?.DIRECTORY_SCOPES || scope.DIRECTORY_SCOPES || []).join('、') || '无'}；排除：{(scopeState?.EXCLUDED_PATHS || scope.EXCLUDED_PATHS || []).join('、') || '无'}</p>
        <p className="hint">有效文件 {(scopeState?.EFFECTIVE_FILES || scope.EFFECTIVE_FILES)?.length ?? 0} 个；范围摘要 {scope.SCOPE_SHA256?.slice(0, 16)}…</p>
        {(scopeState?.DIRECTORY_SCOPES || []).map(directory => <Button key={directory} size="small" onClick={() => revoke(directory)}>撤销目录 {directory}</Button>)}
      </>}
      {sources.map(source => <p key={source.id}>{source.kind} · {source.summary}
        {source.contentPreview && <small className="hint">内容预览：{source.contentPreview.slice(0, 240)}</small>}
      </p>)}
      {sources.length === 0 && <p className="hint">尚未接入既有成果。</p>}
      {detail.run.STATUS === 'PENDING' && <div style={{ marginTop: 8 }}>
        <div className="form-label">接入既有成果（来源运行须已结算）</div>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          <Input aria-label="来源运行标识" placeholder="来源 runId" value={sourceRun} onChange={e => setSourceRun(e.target.value)} style={{ width: 260 }}/>
          <Select aria-label="成果类型" value={sourceKind} onChange={setSourceKind} style={{ width: 150 }}
            options={[{ value: 'DOCUMENT', label: '文档' }, { value: 'CODE_DIFF', label: '代码差异' }, { value: 'TEST_EVIDENCE', label: '测试成果' }]}/>
          <InputNumber aria-label="来源任务版本" placeholder="来源任务版本" min={1} value={sourceVersion} onChange={setSourceVersion}/>
          <InputNumber aria-label="目标任务版本" placeholder="目标任务版本" min={1} value={targetVersion} onChange={setTargetVersion}/>
          <Button onClick={attachSource} disabled={!sourceRun.trim() || !sourceVersion || !targetVersion}>接入</Button>
        </div>
        {sourceError && <p className="hint">接入被拒绝：{sourceError}</p>}
      </div>}
    </div>}
    <div className="result-box">
      <strong>周期快照与恢复租约</strong>
      {snapshots.length === 0 ? <p className="hint">尚无周期快照。</p> :
        snapshots.map(snapshot => <p key={snapshot.ID}>
          事件位置 {snapshot.EVENT_SEQ} · 已完成 {snapshot.COMPLETED_NODES} 步 · 轮次 {snapshot.ROUNDS} · 工具 {snapshot.TOOLS}
          {snapshot.WORKSPACE_FINGERPRINT ? ` · 工作区 ${snapshot.WORKSPACE_FINGERPRINT.slice(0, 12)}…` : ''}
        </p>)}
      {lease && <p className="hint">恢复租约：{lease.STATUS} · {lease.OWNER} · 到期 {lease.EXPIRES_AT}</p>}
    </div>
    <div className="result-box">
      <strong>费用估算与硬预算</strong>
      {cost ? <>
        <p>上限 {cost.MAX_COST_MICROS} {cost.CURRENCY} · 已预留 {cost.RESERVED_MICROS} · 已结算 {cost.SETTLED_MICROS} · 未知调用 {cost.UNKNOWN_CALLS}</p>
        {cost.OVER_BUDGET && <p>已超支 {cost.OVERAGE_MICROS}；账本保留实际结算，后续调用已停止。</p>}
        <p className="hint">价格版本 {cost.PRICE_PROVIDER}/{cost.PRICE_MODEL} v{cost.PRICE_VERSION}；单次可计价上界 {cost.PER_CALL_UPPER_BOUND_MICROS}。未知用量不会被当作零。</p>
      </> : <p className="hint">该运行没有绑定费用硬预算；当前只有 Token 和耗时统计。</p>}
    </div>
    <div className="result-box">
      <strong>外部动作对账</strong>
      {actions.length === 0 ? <p className="hint">没有登记外部动作。</p> : actions.map(action => <div className="agent-card" key={action.id}>
        <div className="agent-text">
          <strong>{action.kind} · {action.status}</strong>
          <small>{action.target} · 目标版本 {action.targetVersion || '未记录'} · 审批 {action.approvalRef || '未记录'}</small>
          <small>尝试 {action.attempts} 次；{action.resultEvidence || '尚无查询证据'}</small>
          {(action.checks || []).map(check => <small key={check.id}>核对 {check.outcome} · {check.evidence || '无证据'} · {check.checkedAt}</small>)}
        </div>
        {action.needsManualReview && action.status !== 'SUCCEEDED' && <Button size="small" onClick={() => reconcile(action.id)}>查询对账</Button>}
      </div>)}
      <p className="hint">对账只查询并补记证据，不重放外部写入；未知结果保持待人工核对。</p>
    </div>
  </>;
}
