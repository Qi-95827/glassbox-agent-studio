import React, { useEffect, useState } from 'react';
import { Button, Input, InputNumber, Select } from 'antd';

/** 供应方价格版本发布与当前生效版本查看。 */
export default function CostPricePanel({ api }) {
  const [prices, setPrices] = useState([]);
  const [form, setForm] = useState({
    provider: 'default', model: 'mock', currency: 'CNY',
    inputMicrosPerMillion: 0, outputMicrosPerMillion: 0,
    inputTokenUpperBound: 4096, outputTokenUpperBound: 4096
  });
  const [error, setError] = useState(null);
  const load = async () => {
    try { setPrices(await api('/cost/prices')); } catch (e) { setError(e.message); }
  };
  useEffect(() => { load(); }, []);
  const publish = async () => {
    try {
      await api('/cost/prices', { method: 'POST', body: JSON.stringify(form) });
      setError(null);
      await load();
    } catch (e) { setError(e.message); }
  };
  return <div className="result-box">
    <strong>供应方价格版本</strong>
    <p className="hint">价格版本用于费用估算和派发前可计价上界预留；发布后旧版本保留，只有最新生效版本参与新预算。</p>
    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
      <Input aria-label="供应方" value={form.provider} onChange={e => setForm({ ...form, provider: e.target.value })} style={{ width: 110 }}/>
      <Input aria-label="模型" value={form.model} onChange={e => setForm({ ...form, model: e.target.value })} style={{ width: 110 }}/>
      <Select aria-label="币种" value={form.currency} onChange={value => setForm({ ...form, currency: value })} style={{ width: 90 }}
        options={['CNY', 'USD'].map(value => ({ value, label: value }))}/>
      <InputNumber aria-label="输入价格微单位每百万" min={0} value={form.inputMicrosPerMillion} onChange={value => setForm({ ...form, inputMicrosPerMillion: value ?? 0 })} placeholder="输入/百万"/>
      <InputNumber aria-label="输出价格微单位每百万" min={0} value={form.outputMicrosPerMillion} onChange={value => setForm({ ...form, outputMicrosPerMillion: value ?? 0 })} placeholder="输出/百万"/>
      <InputNumber aria-label="输入 Token 上界" min={1} max={1000000} value={form.inputTokenUpperBound} onChange={value => setForm({ ...form, inputTokenUpperBound: value ?? 4096 })} placeholder="输入上界"/>
      <InputNumber aria-label="输出 Token 上界" min={1} max={1000000} value={form.outputTokenUpperBound} onChange={value => setForm({ ...form, outputTokenUpperBound: value ?? 4096 })} placeholder="输出上界"/>
      <Button onClick={publish}>发布价格版本</Button>
    </div>
    {error && <p className="hint">价格版本未保存：{error}</p>}
    {prices.slice(0, 6).map(price => <p key={price.id} className="hint">
      {price.provider}/{price.model} v{price.version} · {price.currency} · 输入 {price.inputMicrosPerMillion}/百万 · 输出 {price.outputMicrosPerMillion}/百万 · {price.active ? '生效' : '历史'}
    </p>)}
  </div>;
}
