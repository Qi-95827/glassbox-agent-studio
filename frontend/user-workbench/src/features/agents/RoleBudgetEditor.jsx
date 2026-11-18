import React from 'react';
import { InputNumber } from 'antd';

const roles = [
  ['ANALYST', '分析 Agent'],
  ['CODER', '开发 Agent'],
  ['REVIEWER', '审查 Agent'],
];

/** 配置每个角色实例的可选上限；空值表示不增加角色限制。 */
export default function RoleBudgetEditor({ value, onChange }) {
  return <InvocationBudgetEditor
    heading="按角色设置单实例上限（可选）"
    explanation="与统一单 Agent 和运行总上限同时生效。工具上限为 0 表示该角色实例不能启动工具。"
    entries={roles} value={value} onChange={onChange}
  />;
}

export function InvocationBudgetEditor({ heading, explanation, entries, value, onChange }) {
  function update(key, field, nextValue) {
    const next = { ...value };
    const budget = { ...(next[key] || {}) };
    if (nextValue == null) delete budget[field];
    else budget[field] = nextValue;
    if (Object.keys(budget).length === 0) delete next[key];
    else next[key] = budget;
    onChange(next);
  }

  return (
    <details className="agent-card handoff">
      <summary>{heading}</summary>
      <p>{explanation}</p>
      {entries.map(([key, label]) => (
        <div key={key}>
          <strong>{label}</strong>
          <div className="limit-grid">
            <label>模型调用<InputNumber aria-label={`${label}模型调用上限`} min={1} max={20} precision={0}
              value={value[key]?.maxModelCalls ?? null} onChange={v => update(key, 'maxModelCalls', v)} /></label>
            <label>工具调用<InputNumber aria-label={`${label}工具调用上限`} min={0} max={20} precision={0}
              value={value[key]?.maxToolCalls ?? null} onChange={v => update(key, 'maxToolCalls', v)} /></label>
          </div>
        </div>
      ))}
      <p>空值表示不额外限制。</p>
    </details>
  );
}
