import React, { useEffect, useState } from 'react';
import { Alert, Modal, Spin } from 'antd';

const toolLabels = {
  SIMULATED_INSPECTION: '模拟检查工具',
  WORKSPACE_READ: '读取授权文件',
  WORKSPACE_SEARCH: '搜索授权文件',
  WORKSPACE_WRITE: '修改授权文件',
};

/** 目录展示角色的声明上限；实际授权和调用记录以具体运行为准。 */
export default function AgentCatalogDialog({ open, onClose, api }) {
  const [roles, setRoles] = useState(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!open) return undefined;
    let active = true;
    setRoles(null);
    setError(false);
    api('/agents/catalog')
      .then((data) => { if (active) setRoles(data); })
      .catch(() => { if (active) setError(true); });
    return () => { active = false; };
  }, [open, api]);

  return (
    <Modal title="内置 Agent 角色" open={open} onCancel={onClose} footer={null} width={760}>
      <Alert
        type="info"
        showIcon
        message="这里显示当前内置角色的能力声明；具体任务仍需用户授权，实际调用以运行记录为准。"
      />
      <p className="hint">当前为固定角色协作，暂不支持在页面创建角色或动态调整执行图。</p>
      {error ? <Alert type="error" message="角色目录暂时不可用。" /> : !roles ? <Spin /> : roles.map((role) => (
        <details className="agent-card handoff" key={role.role}>
          <summary>{role.displayName} · 角色版本 {role.version}</summary>
          <p>框架名称：{role.agentName}</p>
          <p>声明的工具能力：{role.declaredTools.map((tool) => toolLabels[tool] || tool).join('、') || '无'}</p>
          <p>角色指导：{role.systemPrompt}</p>
          {role.skills.map((skill) => (
            <div className="result-box" key={skill.id}>
              <strong>{skill.name} · v{skill.version}</strong>
              <p>{skill.guidance}</p>
              <small>内容摘要：{skill.contentSha256}</small>
            </div>
          ))}
        </details>
      ))}
    </Modal>
  );
}
