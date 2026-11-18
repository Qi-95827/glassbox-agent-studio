import React from 'react';

const sourceLabels = {
  CONTRIBUTOR: '上下文装配器', TASK: '任务', MEMORY: '项目背景', BUG_CHECK: '测试记录',
  HANDOFF: '角色交接', WORKFLOW_NODE: '计划节点', WORKFLOW_ATTEMPT: '上游尝试', TOOL_CALL: '工具调用'
};

export default function AssemblyDetails({ recorded, toolNames }) {
  if (!recorded) return <small>实际装配快照未记录</small>;
  const { version, components, budget } = recorded.snapshot;
  const { model, authorization, input } = components;
  const { limits, roleLimits, toolLimits, nodeLimits } = budget;
  return <details>
    <summary>实际装配快照 v{version}</summary>
    <small>模型 {model.name} · 配置 v{model.configurationVersion} · 框架请求尝试上限 {components.maxFrameworkRetries}</small>
    <small>
      权限配置 {authorization.profile} v{authorization.profileVersion}
      {' · '}授权文件 {authorization.fileCount} 个
      {' · '}{authorization.writeAuthorized ? '允许写入' : '无写授权'}
    </small>
    <small>预算策略 v{budget.version} · 模型 {limits.maxRounds} 次 · 工具 {limits.maxTools} 次 · {limits.maxSeconds} 秒</small>
    {roleLimits && <small>角色实例上限 · 模型 {roleLimits.maxModelCalls ?? '未设置'} · 工具 {roleLimits.maxToolCalls ?? '未设置'}</small>}
    {nodeLimits && <small>节点上限 · 模型 {nodeLimits.maxModelCalls ?? '未设置'} · 工具 {nodeLimits.maxToolCalls ?? '未设置'}</small>}
    {Object.entries(toolLimits).map(([kind, limit]) => <small key={kind}>
      {toolNames[kind] || kind} · 上限 {limit} 次
    </small>)}
    <small title={input.sha256}>输入摘要 {input.sha256.slice(0, 12)}</small>
    {input.sources.map((source, index) => <small key={`${source.kind}:${source.id}:${index}`} title={source.id}>
      {sourceLabels[source.kind] || source.kind} · {source.kind === 'CONTRIBUTOR' ? source.id : source.id.slice(0, 8)}
      {source.version != null && ` · v${source.version}`}
    </small>)}
  </details>;
}
