import React, { useEffect, useState } from 'react';

/** 任务 8：在创建任务前显示服务端规则路由及选择原因。 */
export default function EntryRoutePreview({ entryKind, workspaceId, writeEnabled, fileScopes, directoryScopes, workflowTemplate, api }) {
  const [decision, setDecision] = useState(null);
  const [error, setError] = useState(null);
  const fileKey = (fileScopes || []).join('\n');
  const directoryKey = (directoryScopes || []).join('\n');
  useEffect(() => {
    let active = true;
    const timer = setTimeout(() => {
      api('/entry-routes/preview', {
        method: 'POST',
        body: JSON.stringify({
          entryKind, workspaceId: workspaceId || null, writeEnabled: !!writeEnabled,
          fileScopes: fileScopes || [], directoryScopes: directoryScopes || [], workflowTemplate: workflowTemplate || null
        })
      }).then(value => { if (active) { setDecision(value); setError(null); } })
        .catch(e => { if (active) { setDecision(null); setError(e.message); } });
    }, 180);
    return () => { active = false; clearTimeout(timer); };
  }, [entryKind, workspaceId, writeEnabled, fileKey, directoryKey, workflowTemplate, api]);
  if (error) return <div className="result-box"><strong>规则路由</strong><p className="hint">暂时无法计算：{error}</p></div>;
  if (!decision) return null;
  return <div className="result-box">
    <strong>规则路由 · {decision.ruleCode}</strong>
    <p>执行模板：{decision.templateId || '兼容流程'}；入口：{decision.routedKind}</p>
    {decision.reasons?.map((reason, index) => <p key={`reason-${index}`} className="hint">{reason}</p>)}
    {decision.warnings?.map((warning, index) => <p key={`warning-${index}`} className="hint">注意：{warning}</p>)}
    <p className="hint">候选流程：{decision.candidates?.map(item => `${item.label}${item.templateId ? `（${item.templateId}）` : ''}`).join('；')}</p>
    <p className="hint">路由只解释选择和权限边界，不会自动生成文档、执行测试或扩大授权。</p>
  </div>;
}
