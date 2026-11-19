import React, { useEffect, useState } from 'react';
import { Alert, Button, Checkbox, Input, InputNumber, Select } from 'antd';

const blank = () => ({
  code: 'CASE_' + Math.random().toString(36).slice(2, 6).toUpperCase(),
  title: '新样本', goal: '描述该样本要验证的行为', scenario: 'normal', expectedStatus: 'COMPLETED',
  maxRounds: 4, maxTools: 2, sourceKind: 'MANUAL', sourceRef: null
});

/** 可管理评测数据集：编辑草稿、发布不可变版本、从失败运行录入并回看批次。 */
export default function DatasetManager({ api, onOpenRun }) {
  const [key, setKey] = useState('MANAGED_UI');
  const [rows, setRows] = useState([]);
  const [versions, setVersions] = useState([]);
  const [batches, setBatches] = useState([]);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [failureRun, setFailureRun] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [version, setVersion] = useState(null);

  const load = async () => {
    try {
      const [draft, published, batchList] = await Promise.all([
        api(`/evaluation-datasets/${encodeURIComponent(key)}`),
        api(`/evaluation-datasets/${encodeURIComponent(key)}/versions`),
        api('/evaluation-datasets/batches')
      ]);
      setRows(draft);
      setVersions(published);
      setBatches(batchList);
      setVersion(published[0]?.version ?? null);
      setError(null);
    } catch (e) { setError(e.message); }
  };
  useEffect(() => { load(); }, [key]);

  const update = (code, field, value) =>
    setRows(current => current.map(row => row.code === code ? { ...row, [field]: value } : row));
  const save = async row => {
    setBusy(true);
    try {
      await api(`/evaluation-datasets/${encodeURIComponent(key)}/samples/${encodeURIComponent(row.code)}`, {
        method: 'PUT',
        body: JSON.stringify({
          code: row.code, title: row.title, goal: row.goal, scenario: row.scenario,
          expectedStatus: row.expectedStatus, maxRounds: row.maxRounds, maxTools: row.maxTools,
          sourceKind: row.sourceKind || 'MANUAL', sourceRef: row.sourceRef || null
        })
      });
      await load();
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };
  const publish = async () => {
    setBusy(true);
    try { await api(`/evaluation-datasets/${encodeURIComponent(key)}/publish`, { method: 'POST', body: '{}' }); await load(); }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };
  const addFromRun = async () => {
    setBusy(true);
    try {
      await api(`/evaluation-datasets/${encodeURIComponent(key)}/from-run`, {
        method: 'POST',
        body: JSON.stringify({ runId: failureRun.trim(), confirmedRedaction: confirmed })
      });
      setFailureRun('');
      setConfirmed(false);
      await load();
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };
  const startBatch = async () => {
    setBusy(true);
    try {
      await api(`/evaluation-datasets/${encodeURIComponent(key)}/batches${version ? `?version=${version}` : ''}`, {
        method: 'POST', body: JSON.stringify({ maxRounds: 4, maxTools: 2, maxSeconds: 60 })
      });
      await load();
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };

  return <div className="result-box">
    <strong>可管理评测数据集</strong>
    <p className="hint">草稿可编辑；发布后版本不可变。批次绑定实际版本、配置和预算，失败样本保留运行入口。</p>
    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
      <Input aria-label="数据集编码" value={key} onChange={e => setKey(e.target.value.toUpperCase())} style={{ width: 170 }}/>
      <Button loading={busy} onClick={publish}>发布当前草稿</Button>
      <Select aria-label="评测版本" placeholder="版本" value={version} onChange={setVersion} style={{ width: 120 }}
        options={versions.map(item => ({ value: item.version, label: `v${item.version} · ${item.sampleCount} 样本` }))}/>
      <Button type="primary" loading={busy} onClick={startBatch} disabled={!versions.length}>运行数据集批次</Button>
    </div>
    {error && <Alert style={{ marginTop: 8 }} type="error" showIcon message={error}/>}
    {rows.map(row => <div className="agent-card" key={row.code}>
      <div className="agent-text">
        <Input aria-label={`${row.code} 标题`} value={row.title} onChange={e => update(row.code, 'title', e.target.value)}/>
        <Input.TextArea aria-label={`${row.code} 目标`} value={row.goal} rows={2} onChange={e => update(row.code, 'goal', e.target.value)}/>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          <Select aria-label={`${row.code} 场景`} value={row.scenario} onChange={value => update(row.code, 'scenario', value)} style={{ width: 110 }}
            options={[{ value: 'normal', label: '正常' }, { value: 'fail', label: '失败' }, { value: 'repeat', label: '重复调用' }]}/>
          <Select aria-label={`${row.code} 预期状态`} value={row.expectedStatus} onChange={value => update(row.code, 'expectedStatus', value)} style={{ width: 120 }}
            options={['COMPLETED', 'FAILED', 'LIMITED', 'BLOCKED'].map(value => ({ value, label: value }))}/>
          <InputNumber aria-label={`${row.code} 轮次`} min={1} max={20} value={row.maxRounds} onChange={value => update(row.code, 'maxRounds', value ?? 1)}/>
          <InputNumber aria-label={`${row.code} 工具`} min={0} max={20} value={row.maxTools} onChange={value => update(row.code, 'maxTools', value ?? 0)}/>
        </div>
        <small>草稿 v{row.version} · {row.sourceKind}{row.sourceRef ? ` · ${row.sourceRef}` : ''} · 已脱敏 {row.redacted ? '是' : '否'}</small>
      </div>
      <Button size="small" loading={busy} onClick={() => save(row)}>保存草稿</Button>
    </div>)}
    <Button style={{ marginTop: 8 }} onClick={() => setRows(current => [...current, blank()])}>新增样本</Button>

    <div className="section-row"><h3>失败案例录入</h3><span>需确认脱敏</span></div>
    <Input aria-label="失败运行标识" placeholder="失败运行的 runId" value={failureRun} onChange={e => setFailureRun(e.target.value)}/>
    <Checkbox checked={confirmed} onChange={e => setConfirmed(e.target.checked)}>确认已按页面规则脱敏并同意录入草稿</Checkbox>
    <Button loading={busy} disabled={!failureRun.trim() || !confirmed} onClick={addFromRun}>从失败运行录入</Button>

    <div className="section-row"><h3>可管理批次</h3><span>历史结果可复查</span></div>
    {batches.length === 0 ? <p className="hint">尚无批次。</p> : batches.map(batch => <div className="agent-card" key={batch.id}>
      <div className="agent-text">
        <strong>{batch.datasetKey} · {batch.status}</strong>
        <small>版本 {batch.datasetVersionId?.slice(0, 8)} · 定义摘要 {batch.definitionSha256?.slice(0, 16)}…</small>
        <small>配置 {batch.configJson}</small>
      </div>
      <Button size="small" onClick={async () => {
        try {
          const detail = await api(`/evaluation-datasets/batches/${batch.id}`);
          const failed = detail.cases.find(item => item.status === 'FAILED' && item.runId)
            || detail.cases.find(item => item.runId);
          if (failed && onOpenRun) onOpenRun(failed.runId);
        } catch (e) { setError(e.message); }
      }}>查看失败运行</Button>
    </div>)}
  </div>;
}
