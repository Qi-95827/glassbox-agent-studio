import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Empty, Spin, Tag, Tooltip } from 'antd';
import { ApartmentOutlined, CheckCircleFilled, ClockCircleOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import WorkflowPlanPanel from './WorkflowPlanPanel.jsx';
import NodeExecutionDetail from './NodeExecutionDetail.jsx';

const statusNames = {
  PENDING: '待执行', RUNNING: '运行中', COMPLETED: '已完成', REUSED: '复用历史结果',
  FAILED: '失败', CANCELLED: '已取消', LIMITED: '已超限', INTERRUPTED: '已中断', SKIPPED: '未执行'
};
const activityNames = { MODEL_CALL: '模型调用', TOOL_CALL: '工具调用', ATTEMPT: 'Agent 实例', CHECK: '系统检查' };
const roleNames = { ANALYST: '分析 Agent', CODER: '开发 Agent', REVIEWER: '审查 Agent' };
const labelOf = node => node?.label || (node?.role ? roleNames[node.role] : null) || node?.key || '未记录步骤';
const blockingNames = {
  PLAN_APPROVAL_REQUIRED: '等待确认计划', RUN_PAUSED: '运行已暂停', RUN_INTERRUPTED: '运行已中断',
  RUN_BLOCKED: '等待处理', CHECK_FAILED: '检查未通过', NODE_FAILED: '节点未完成', DEPENDENCY_NOT_STARTED: '前置步骤未结算'
};

const graphNodeWidth = 208;
const graphNodeHeight = 82;
const graphGapX = 76;
const graphGapY = 18;
const graphPad = 22;

/** 按依赖最长路径分列；同一层的节点按计划顺序排列。环或异常依赖不会让页面崩溃。 */
function layout(nodes, edges) {
  const byId = new Map(nodes.map(node => [node.id, node]));
  const depth = new Map();
  const visiting = new Set();
  const resolve = id => {
    if (depth.has(id)) return depth.get(id);
    if (visiting.has(id)) return 0;
    visiting.add(id);
    const incoming = edges.filter(edge => edge.toNodeId === id && byId.has(edge.fromNodeId));
    const value = incoming.length ? Math.max(...incoming.map(edge => resolve(edge.fromNodeId) + 1)) : 0;
    visiting.delete(id);
    depth.set(id, value);
    return value;
  };
  nodes.forEach(node => resolve(node.id));
  const columns = new Map();
  nodes.forEach(node => {
    const column = depth.get(node.id) || 0;
    if (!columns.has(column)) columns.set(column, []);
    columns.get(column).push(node);
  });
  const positions = new Map();
  let maxRows = 0;
  [...columns.entries()].forEach(([column, items]) => {
    items.sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0));
    maxRows = Math.max(maxRows, items.length);
    items.forEach((node, row) => {
      positions.set(node.id, {
        left: graphPad + column * (graphNodeWidth + graphGapX),
        top: graphPad + row * (graphNodeHeight + graphGapY),
        column, row
      });
    });
  });
  return {
    positions,
    width: graphPad * 2 + Math.max(1, columns.size) * graphNodeWidth + Math.max(0, columns.size - 1) * graphGapX,
    height: graphPad * 2 + Math.max(1, maxRows) * graphNodeHeight + Math.max(0, maxRows - 1) * graphGapY
  };
}

function edgePath(from, to) {
  if (!from || !to) return null;
  const x1 = from.left + graphNodeWidth;
  const y1 = from.top + graphNodeHeight / 2;
  const x2 = to.left;
  const y2 = to.top + graphNodeHeight / 2;
  const middle = Math.max(24, (x2 - x1) / 2);
  return `M ${x1} ${y1} C ${x1 + middle} ${y1}, ${x2 - middle} ${y2}, ${x2} ${y2}`;
}

function nodeClass(node, focusIds, blockedIds) {
  const status = (node.status || 'PENDING').toLowerCase();
  const classes = ['flow-node', 'flow-status-' + status];
  if (focusIds.has(node.id)) classes.push('flow-focus');
  if (blockedIds.has(node.id)) classes.push('flow-blocked');
  if (node.kind === 'SYSTEM_CHECK') classes.push('flow-check-node');
  if (node.status === 'REUSED') classes.push('flow-reused-node');
  return classes.join(' ');
}

export default function ExecutionFlowPanel({
  view, detail, Status, stopCauseLabels, eventNames, api, connected, lastSyncedAt,
  approving, onApprove, onRecover, onPause, onResume, onCancel, onSwitchRun
}) {
  const [tab, setTab] = useState('flow');
  const [selectedId, setSelectedId] = useState(null);
  const [nodeDetail, setNodeDetail] = useState(null);
  const [nodeLoading, setNodeLoading] = useState(false);
  const [nodeError, setNodeError] = useState(null);

  const runId = view?.runId;
  useEffect(() => {
    setSelectedId(null);
    setNodeDetail(null);
    setNodeError(null);
  }, [runId]);

  useEffect(() => {
    if (!selectedId || !runId) {
      setNodeDetail(null);
      return undefined;
    }
    let active = true;
    const load = async () => {
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
        if (active) setNodeLoading(false);
      }
    };
    load();
    const timer = setInterval(load, 1800);
    return () => { active = false; clearInterval(timer); };
  }, [selectedId, runId, api]);

  const nodes = view?.nodes ?? [];
  const edges = view?.edges ?? [];
  const layoutResult = useMemo(() => layout(nodes, edges), [nodes, edges]);
  const focusItems = view?.focus?.active ?? [];
  const focusIds = useMemo(() => new Set(focusItems.map(item => item.nodeId)), [focusItems]);
  const blockedIds = useMemo(() => {
    const ids = new Set();
    (view?.blocking ?? []).forEach(item => {
      if (item.nodeId) ids.add(item.nodeId);
      (item.blockingNodeIds ?? []).forEach(id => ids.add(id));
    });
    return ids;
  }, [view]);
  const plan = view?.plan;
  const actions = view?.availableActions ?? [];
  const recovery = view?.recovery;

  const actionHandler = action => {
    if (!action.enabled) return;
    if (action.actionCode === 'APPROVE_PLAN') onApprove?.(action.runId, action.planVersionId);
    else if (action.actionCode === 'PAUSE') onPause?.();
    else if (action.actionCode === 'RESUME') onResume?.();
    else if (action.actionCode === 'CANCEL') onCancel?.();
    else if (action.actionCode === 'RECOVER') onRecover?.(action.checkpointId);
  };

  const nodeById = new Map(nodes.map(node => [node.id, node]));
  const selectedNode = selectedId ? nodeById.get(selectedId) : null;

  if (!view) {
    return <>
      <div className="section-row"><h3>执行流程图</h3><span>{connected ? '等待运行快照' : '连接中断'}</span></div>
      <p className="hint">运行快照暂不可用；历史列表仍显示在下方。</p>
    </>;
  }

  return <>
    <div className="section-row">
      <h3>执行流程图</h3>
      <span>
        {plan ? `计划 v${plan.version} · ${plan.templateId} v${plan.templateVersion}` : '历史计划无版本'}
        {' · '}已同步 {lastSyncedAt ? new Date(lastSyncedAt).toLocaleTimeString('zh-CN', { hour12: false }) : '未记录'}
      </span>
    </div>
    <div className="flow-tabs" role="tablist" aria-label="执行视图切换">
      <button role="tab" aria-selected={tab === 'flow'} className={tab === 'flow' ? 'selected' : ''} onClick={() => setTab('flow')}>流程图</button>
      <button role="tab" aria-selected={tab === 'list'} className={tab === 'list' ? 'selected' : ''} onClick={() => setTab('list')}>列表视图</button>
      {!connected && <span className="flow-offline">连接中断 · 显示最后成功快照</span>}
    </div>

    {tab === 'list' ? <WorkflowPlanPanel detail={detail} Status={Status} stopCauseLabels={stopCauseLabels}
      onApprove={onApprove} approving={approving} onRecover={onRecover} /> : <>
      <div className="flow-summary">
        <span>共 {view.counts?.total ?? nodes.length} 个节点</span>
        <span>完成 {view.counts?.completed ?? 0}</span>
        <span>运行中 {view.counts?.running ?? 0}</span>
        <span>待执行 {view.counts?.pending ?? 0}</span>
        <span>复用 {view.counts?.reused ?? 0}</span>
        <span>检查 {view.counts?.checks ?? 0}</span>
        <span>模型 {view.counts?.modelCalls ?? 0} 次</span>
        <span>工具 {view.counts?.toolCalls ?? 0} 次</span>
        {view.counts?.unknownTokenCalls > 0 && <span className="flow-warning">未知用量 {view.counts.unknownTokenCalls} 次</span>}
      </div>

      {focusItems.length > 0 && <div className="flow-focus-line">
        <strong>当前动作</strong>
        {focusItems.map(item => <span key={`${item.nodeId}:${item.activityId}`}>
          {labelOf(nodeById.get(item.nodeId)) || item.nodeLabel} → {activityNames[item.activityKind] || item.activityKind}
          {item.activityLabel ? ` · ${item.activityLabel}` : ''}
          {item.startedAt ? ` · ${new Date(item.startedAt).toLocaleTimeString('zh-CN', { hour12: false })}` : ''}
        </span>)}
      </div>}

      {(view.blocking ?? []).length > 0 && <div className="flow-blocking">
        {(view.blocking ?? []).map((item, index) => <Alert key={`${item.code}:${item.nodeId || index}`}
          type="warning" showIcon
          message={`${blockingNames[item.code] || item.code}${item.nodeId ? ` · ${labelOf(nodeById.get(item.nodeId))}` : ''}`}
          description={<>
            <span>{item.message}</span>
            {item.blockingNodeIds?.length > 0 && <span className="flow-unstarted">
              未启动下游：{item.blockingNodeIds.map(id => labelOf(nodeById.get(id)) || id.slice(0, 8)).join('、')}
            </span>}
          </>} />)}
      </div>}

      <div className="flow-actions">
        {actions.length === 0 && <span className="hint">当前没有可用操作。</span>}
        {actions.map(action => <Tooltip key={action.actionCode + (action.checkpointId || '')} title={action.disabledReason || ''}>
          <Button size="small" disabled={!action.enabled}
            type={action.actionCode === 'APPROVE_PLAN' ? 'primary' : 'default'}
            loading={action.actionCode === 'APPROVE_PLAN' && approving}
            onClick={() => actionHandler(action)}>
            {action.label}{action.targetLabel && action.actionCode !== 'APPROVE_PLAN' ? ` · ${action.targetLabel}` : ''}
          </Button>
        </Tooltip>)}
      </div>

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

      {nodes.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="此运行没有计划节点" /> :
        <div className="flow-scroll">
          <div className="flow-canvas" style={{ width: layoutResult.width, height: layoutResult.height }}>
            <svg className="flow-edges" width={layoutResult.width} height={layoutResult.height} aria-hidden="true">
              <defs>
                <marker id="flow-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                  <path d="M0,0 L8,4 L0,8 z" fill="#aeb8cc" />
                </marker>
              </defs>
              {edges.map(edge => {
                const path = edgePath(layoutResult.positions.get(edge.fromNodeId), layoutResult.positions.get(edge.toNodeId));
                if (!path) return null;
                const dependency = nodeById.get(edge.fromNodeId);
                const unmet = dependency && !['COMPLETED', 'REUSED'].includes(dependency.status);
                return <path key={`${edge.fromNodeId}:${edge.toNodeId}`} d={path} fill="none"
                  stroke={unmet ? '#d8deea' : '#b8c3d8'} strokeWidth="1.6"
                  strokeDasharray={unmet ? '5 5' : undefined} markerEnd="url(#flow-arrow)" />;
              })}
            </svg>
            {nodes.map(node => {
              const position = layoutResult.positions.get(node.id);
              if (!position) return null;
              const focus = focusItems.find(item => item.nodeId === node.id);
              return <button key={node.id} type="button"
                className={nodeClass(node, focusIds, blockedIds)}
                style={{ left: position.left, top: position.top, width: graphNodeWidth, height: graphNodeHeight }}
                aria-pressed={selectedId === node.id}
                aria-label={`${labelOf(node)}，${statusNames[node.status] || node.status}${focus ? `，当前动作 ${activityNames[focus.activityKind] || focus.activityKind}` : ''}`}
                onClick={() => setSelectedId(node.id)}>
                <span className="flow-node-top">
                  <span className="flow-node-index">{node.sequence}</span>
                  <span className="flow-node-kind">{node.kind === 'SYSTEM_CHECK' ? <SafetyCertificateOutlined /> : <ApartmentOutlined />}
                    {node.kind === 'SYSTEM_CHECK' ? '检查' : 'Agent'}</span>
                  <i className="flow-node-dot" />
                </span>
                <strong>{labelOf(node)}</strong>
                <span className="flow-node-status">
                  {statusNames[node.status] || node.status}
                  {node.attemptCount > 0 ? ` · ${node.attemptCount} 次尝试` : ''}
                </span>
                {focus && <span className="flow-node-focus">{activityNames[focus.activityKind] || focus.activityKind}</span>}
              </button>;
            })}
          </div>
        </div>}

      <div className="flow-legend" aria-label="图例">
        <span><i className="legend-dot status-completed" />已完成</span>
        <span><i className="legend-dot status-running" />运行中</span>
        <span><i className="legend-ring" />当前动作</span>
        <span><i className="legend-dot status-failed" />阻塞/失败</span>
        <span><i className="legend-dot status-reused" />复用历史</span>
        <span><i className="legend-dot status-pending" />待执行</span>
      </div>

      {selectedId && <div className="flow-detail">
        <div className="flow-detail-heading">
          <div><strong>{labelOf(selectedNode)}</strong>
            <span>{selectedNode?.kind === 'SYSTEM_CHECK' ? '系统检查' : 'Agent 节点'} · {statusNames[selectedNode?.status] || selectedNode?.status}</span></div>
          <Button size="small" onClick={() => setSelectedId(null)}>收起</Button>
        </div>
        {nodeLoading && !nodeDetail && <Spin />}
        {nodeError && <Alert type="error" showIcon message={nodeError} />}
        {nodeDetail && <NodeExecutionDetail detail={nodeDetail} Status={Status}
          stopCauseLabels={stopCauseLabels} eventNames={eventNames} />}
      </div>}
    </>}
  </>;
}
