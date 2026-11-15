import React from 'react';
import { InputNumber } from 'antd';

const tools = [
  ['SIMULATED_INSPECTION', '模拟检查工具'],
  ['WORKSPACE_READ', '读取授权文件'],
  ['WORKSPACE_WRITE', '修改授权文件'],
];

/** 按受控工具类型配置上限；空值表示沿用统一限制。 */
export default function ToolBudgetEditor({ value, onChange }) {
  function update(kind, limit) {
    const next = { ...value };
    if (limit == null) delete next[kind];
    else next[kind] = limit;
    onChange(next);
  }

  return (
    <details className="agent-card handoff">
      <summary>按工具类型设置调用上限（可选）</summary>
      <p>与运行总次数、统一单工具、单 Agent 和角色限制同时生效；设置上限不会授予工具权限。</p>
      <div className="limit-grid">
        {tools.map(([kind, label]) => (
          <label key={kind}>{label}<InputNumber aria-label={`${label}调用上限`} min={0} max={20} precision={0}
            value={value[kind] ?? null} onChange={limit => update(kind, limit)} /></label>
        ))}
      </div>
      <p>空值表示不额外限制；0 表示不允许启动该工具。恢复时已启动的历史调用仍计入。</p>
    </details>
  );
}
