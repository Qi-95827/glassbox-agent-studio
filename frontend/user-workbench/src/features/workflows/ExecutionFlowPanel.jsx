import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Empty, Input, Tooltip } from 'antd';
import { Box, Paper, Stack, Tab, Tabs, Typography } from '@mui/material';
import { createTheme, ThemeProvider } from '@mui/material/styles';
import WorkflowPlanPanel from './WorkflowPlanPanel.jsx';
import PlanAdjustmentEditor from './PlanAdjustmentEditor.jsx';
import RunExtensionsPanel from './RunExtensionsPanel.jsx';
import CostPricePanel from './CostPricePanel.jsx';
import RunTraceGraph from './RunTraceGraph.jsx';
import RunTraceInspector, { TraceSection } from './RunTraceInspector.jsx';

const activityNames = { MODEL_CALL: '模型调用', TOOL_CALL: '工具调用', ATTEMPT: 'Agent 实例', CHECK: '系统检查' };
const roleNames = { ANALYST: '分析 Agent', CODER: '开发 Agent', REVIEWER: '审查 Agent' };
const labelOf = node => node?.label || (node?.role ? roleNames[node.role] : null) || node?.key || '未记录步骤';
const blockingNames = {
  PLAN_APPROVAL_REQUIRED: '等待确认计划', RUN_PAUSED: '运行已暂停', RUN_INTERRUPTED: '运行已中断',
  RUN_BLOCKED: '等待处理', CHECK_FAILED: '检查未通过', NODE_FAILED: '节点未完成', DEPENDENCY_NOT_STARTED: '前置步骤未结算'
};

const traceTheme = createTheme({
  palette: { primary: { main: '#0b57d0' }, background: { default: '#f3f6fc' } },
  shape: { borderRadius: 10 },
  typography: { fontFamily: '"Segoe UI", "Microsoft YaHei UI", sans-serif' }
});

export default function ExecutionFlowPanel({
  view, detail, Status, stopCauseLabels, eventNames, api, connected, lastSyncedAt,
  approving, onApprove, onRecover, onPause, onResume, onTakeOver, onReturnHandover, onCancel, onSwitchRun, onSubmitSupplement
}) {
  const [tab, setTab] = useState('flow');
  const [selection, setSelection] = useState(null);
  const [nodeDetail, setNodeDetail] = useState(null);
  const [nodeLoading, setNodeLoading] = useState(false);
  const [nodeError, setNodeError] = useState(null);
  const [supplementPath, setSupplementPath] = useState('tests/test_calc.py');
  const [supplementContent, setSupplementContent] = useState('');
  const [supplementSending, setSupplementSending] = useState(false);

  const runId = view?.runId;
  const nodes = view?.nodes ?? [];
  const edges = view?.edges ?? [];
  const focusItems = view?.focus?.active ?? [];
  const focusIds = useMemo(() => new Set(focusItems.map(item => item.nodeId)), [focusItems]);
  const nodeById = new Map(nodes.map(node => [node.id, node]));
  const currentSelection = selection?.runId === runId ? selection : null;
  const selectedEdge = currentSelection?.edge;
  const selectedNode = nodeById.get(currentSelection?.nodeId)
    ?? nodes.find(node => focusIds.has(node.id))
    ?? nodes[0];
  const selectedId = selectedNode?.id;
  const matchingDetail = nodeDetail?.runId === runId && nodeDetail?.node?.id === selectedId ? nodeDetail : null;
  const live = ['RUNNING', 'PENDING', 'PAUSED', 'CANCELLING'].includes(view?.runStatus);

  useEffect(() => {
    setSupplementContent('');
    setSupplementSending(false);
  }, [runId]);

  useEffect(() => {
    if (!selectedId || !runId || tab !== 'flow') return undefined;
    let active = true;
    let inFlight = false;
    setNodeError(null);
    const load = async () => {
      if (inFlight) return;
      inFlight = true;
      setNodeLoading(true);
      try {
        const next = await api(`/runs/${runId}/execution-view/nodes/${selectedId}`);
        if (active && next.runId === runId && next.node?.id === selectedId) {
          setNodeDetail(next);
          setNodeError(null);
        }
      } catch (e) {
        if (active) setNodeError(e.message);
      } finally {
        inFlight = false;
        if (active) setNodeLoading(false);
      }
    };
    load();
    const timer = live && connected ? setInterval(load, 1800) : null;
    return () => { active = false; clearInterval(timer); };
  }, [selectedId, runId, tab, live, connected, api]);
  const plan = view?.plan;
  const actions = view?.availableActions ?? [];
  const recovery = view?.recovery;
  const blocking = (view?.blocking ?? []).filter(item => !(view.reason && item.code === 'RUN_BLOCKED' && !item.nodeId));

  const actionHandler = action => {
    if (!action.enabled) return;
    if (action.actionCode === 'APPROVE_PLAN') onApprove?.(action.runId, action.planVersionId);
    else if (action.actionCode === 'PAUSE') onPause?.();
    else if (action.actionCode === 'RESUME') onResume?.();
    else if (action.actionCode === 'TAKE_OVER') onTakeOver?.();
    else if (action.actionCode === 'RETURN_HANDOVER') onReturnHandover?.();
    else if (action.actionCode === 'CANCEL') onCancel?.();
    else if (action.actionCode === 'RECOVER') onRecover?.(action.checkpointId);
  };

  if (!view) {
    return <>
      <div className="section-row"><h3>执行流程图</h3><span>{connected ? '等待运行快照' : '连接中断'}</span></div>
      <p className="hint">运行快照暂不可用；历史列表仍显示在下方。</p>
    </>;
  }

  return <ThemeProvider theme={traceTheme}>
    <Box sx={{ px: { xs: 1.5, sm: 3 }, pt: 1.5, pb: 1, minWidth: 0 }}>
    <Stack direction="row" sx={{ alignItems: 'baseline', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1.25 }}>
      <Typography component="h3" variant="subtitle1" sx={{ fontWeight: 650 }}>执行流程图</Typography>
      <Typography component="span" variant="caption" color="text.secondary">
        {plan ? `计划 v${plan.version} · ${plan.templateId} v${plan.templateVersion}` : '历史计划无版本'}
        {' · '}已同步 {lastSyncedAt ? new Date(lastSyncedAt).toLocaleTimeString('zh-CN', { hour12: false }) : '未记录'}
      </Typography>
    </Stack>
    <Stack direction="row" sx={{ alignItems: 'center', flexWrap: 'wrap', justifyContent: 'space-between' }}>
      <Tabs value={tab} onChange={(_, value) => setTab(value)} aria-label="执行视图切换">
        <Tab value="flow" label="流程图" />
        <Tab value="list" label="列表视图" />
      </Tabs>
      {!connected && <Typography variant="caption" color="warning.dark">连接中断 · 显示最后成功快照</Typography>}
    </Stack>

    {tab === 'list' ? <WorkflowPlanPanel detail={detail} Status={Status} stopCauseLabels={stopCauseLabels}
      onApprove={onApprove} approving={approving} onRecover={onRecover} /> : <>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1, mb: 1 }}>
        节点已完成 {view.counts?.completed ?? 0} / {view.counts?.total ?? nodes.length}
        {' · '}模型 {view.counts?.modelCalls ?? 0} 次 · 工具 {view.counts?.toolCalls ?? 0} 次
      </Typography>

      {focusItems.length > 0 && <div className="flow-focus-line">
        <strong>当前动作</strong>
        {focusItems.map(item => <span key={`${item.nodeId}:${item.activityId}`}>
          {labelOf(nodeById.get(item.nodeId)) || item.nodeLabel} → {activityNames[item.activityKind] || item.activityKind}
          {item.activityLabel ? ` · ${item.activityLabel}` : ''}
          {item.startedAt ? ` · ${new Date(item.startedAt).toLocaleTimeString('zh-CN', { hour12: false })}` : ''}
        </span>)}
      </div>}

      {blocking.length > 0 && <div className="flow-blocking">
        {blocking.map((item, index) => <Alert key={`${item.code}:${item.nodeId || index}`}
          type="warning" showIcon
          message={`${blockingNames[item.code] || item.code}${item.nodeId ? ` · ${labelOf(nodeById.get(item.nodeId))}` : ''}`}
          description={<>
            <span>{item.message}</span>
            {item.blockingNodeIds?.length > 0 && <span className="flow-unstarted">
              未启动下游：{item.blockingNodeIds.map(id => labelOf(nodeById.get(id)) || id.slice(0, 8)).join('、')}
            </span>}
          </>} />)}
      </div>}
      {view.reason && !blocking.some(item => item.message === view.reason)
        && <Alert type="warning" message={view.reason} showIcon />}

      {view.supplement && <div className="flow-supplement">
        <div className="flow-supplement-heading">
          <strong>{view.supplement.status === 'APPLIED' ? '补充已应用' : '等待补充信息'}</strong>
          <span>任务 v{view.taskVersion} · 关联节点：{view.supplement.nodeLabel || '未记录'}</span>
        </div>
        <p>{view.supplement.question}</p>
        {view.supplement.status === 'APPLIED'
          ? <p className="hint">已补充 {view.supplement.answerPath || '未记录路径'}（提交时任务 v{view.supplement.taskVersion} → v{view.taskVersion}）；运行从安全边界继续，首次失败的检查记录保留。</p>
          : <div className="flow-supplement-form">
              <Input aria-label="测试文件路径" value={supplementPath} onChange={e => setSupplementPath(e.target.value)} maxLength={200} placeholder="tests/test_calc.py" />
              <Input.TextArea aria-label="测试文件内容" value={supplementContent} onChange={e => setSupplementContent(e.target.value)} maxLength={20000} showCount rows={6} placeholder="unittest 测试文件内容，例如 import unittest …" />
              <Button type="primary" loading={supplementSending} onClick={() => {
                setSupplementSending(true);
                Promise.resolve(onSubmitSupplement?.(view.supplement.id, view.taskVersion, supplementPath, supplementContent))
                  .finally(() => setSupplementSending(false));
              }}>提交补充并继续</Button>
              <span className="hint">提交按任务 v{view.taskVersion} 校验；路径必须是新的 tests/test_*.py 文件。失败时保持阻塞并显示原因。</span>
            </div>}
      </div>}

      {view.handover && <div className="flow-supplement">
        <div className="flow-supplement-heading">
          <strong>{view.handover.status === 'ACTIVE' ? '人工接手进行中' : '人工已交还'}</strong>
          <span>{view.handover.revalidationRequired ? '需重新验证：旧测试与审批失效，当前运行不能直接继续' : view.handover.evidenceInvalidated ? '代码已变化：旧测试与审批失效' : '代码版本未变化'}</span>
        </div>
        <p>
          接手时间：{new Date(view.handover.takenAt).toLocaleString('zh-CN', { hour12: false })}
          {view.handover.returnedAt ? `；交还时间：${new Date(view.handover.returnedAt).toLocaleString('zh-CN', { hour12: false })}` : ''}
        </p>
        {view.handover.note && <p className="hint">{view.handover.note}</p>}
        <p className="hint">
          基线摘要：{view.handover.baselineFingerprint?.slice(0, 12) || '未记录'}；
          当前摘要：{view.handover.currentFingerprint?.slice(0, 12) || '未记录'}。
          交还后运行仍暂停，请重新验证后再继续。
        </p>
      </div>}

      {actions.some(action => action.actionCode !== 'SUBMIT_SUPPLEMENT') && <div className="flow-actions">
        {actions.filter(action => action.actionCode !== 'SUBMIT_SUPPLEMENT').map(action => <Tooltip key={action.actionCode + (action.checkpointId || '')} title={action.disabledReason || ''}>
          <Button size="small" disabled={!action.enabled}
            type={action.actionCode === 'APPROVE_PLAN' ? 'primary' : 'default'}
            loading={action.actionCode === 'APPROVE_PLAN' && approving}
            onClick={() => actionHandler(action)}>
            {action.label}{action.targetLabel && action.actionCode !== 'APPROVE_PLAN' ? ` · ${action.targetLabel}` : ''}
          </Button>
        </Tooltip>)}
      </div>}

      {view.recoverySupport && <div className="flow-recovery">
        <strong>恢复判定</strong>
        <span>{view.recoverySupport.supported ? '支持恢复' : '不支持自动恢复'}：{view.recoverySupport.reason}</span>
        {view.budget?.inherited && <span>继承消耗已计入当前剩余预算。</span>}
      </div>}

      {recovery && <div className="flow-recovery">
        <strong>恢复来源</strong>
        <span>父运行：{recovery.parentRunId
          ? <Button type="link" size="small" onClick={() => onSwitchRun?.(recovery.parentRunId)}>#{recovery.parentRunId.slice(0, 8)}</Button>
          : '未记录'}</span>
        <span>检查点：{recovery.sourceCheckpointId ? `#${recovery.sourceCheckpointId.slice(0, 8)}` : '未记录'}</span>
        <span>继承消耗：{recovery.inherited
          ? `${recovery.inherited.rounds ?? '未知'} 次模型、${recovery.inherited.tools ?? '未知'} 次工具、${recovery.inherited.elapsedMs ?? '未知'} 毫秒`
          : '未记录'}</span>
        <span>本运行新增：模型 {recovery.newModelCalls} 次、工具 {recovery.newToolCalls} 次</span>
        <span>复用节点：{recovery.reusedNodes?.length
          ? recovery.reusedNodes.map(node => `${node.label || node.key || '未记录步骤'}（来源 #${(node.sourceAgentId || '').slice(0, 8)}）`).join('、')
          : '无'}</span>
      </div>}

      {!selectedNode ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="此运行没有计划节点" /> :
        <Box sx={{ display: 'grid', alignItems: 'start', gap: 1.5, my: 1.5,
          gridTemplateColumns: { xs: 'minmax(0, 1fr)', xl: 'minmax(0, 1fr) minmax(300px, 0.78fr)' } }}>
          <RunTraceGraph nodes={nodes} edges={edges} runId={runId} selectedId={selectedId}
            selectedEdge={selectedEdge} focusIds={focusIds} nodeDetail={matchingDetail}
            onSelectNode={nodeId => setSelection({ runId, nodeId })}
            onSelectEdge={edge => setSelection({ runId, nodeId: edge.toNodeId, edge })} />
          <Paper variant="outlined" sx={{ minWidth: 0 }}>
            <RunTraceInspector key={`${runId}:${selectedId}:${selectedEdge?.fromNodeId ?? ''}`}
              detail={matchingDetail} selectedNode={selectedNode} selectedEdge={selectedEdge}
              view={view} runDetail={detail} byId={nodeById} api={api}
              loading={nodeLoading || !matchingDetail} error={nodeError}
              stopCauseLabels={stopCauseLabels} eventNames={eventNames} />
          </Paper>
        </Box>}
      {view.budget && <TraceSection title="运行预算与计数">
        <Typography variant="body2">
          待执行 {view.counts?.pending ?? 0} · 运行中 {view.counts?.running ?? 0}
          {' · '}复用 {view.counts?.reused ?? 0} · 检查 {view.counts?.checks ?? 0}
        </Typography>
        <Typography variant="body2">
          剩余轮次 {view.budget.remainingRounds} · 工具 {view.budget.remainingTools}
          {' · '}时间 {Math.ceil((view.budget.remainingMs || 0) / 1000)} 秒
          {view.budget.remainingTokens != null && ` · Token ${view.budget.remainingTokens}`}
          {view.counts?.unknownTokenCalls > 0 && ` · 未知用量 ${view.counts.unknownTokenCalls} 次`}
        </Typography>
      </TraceSection>}
    </>}
    <PlanAdjustmentEditor detail={detail} api={api} />
    <TraceSection title="运行扩展与费用配置">
      <RunExtensionsPanel detail={detail} api={api} />
      <CostPricePanel api={api} />
    </TraceSection>
    </Box>
  </ThemeProvider>;
}
