import React, { useEffect, useId, useState } from 'react';
import {
  Accordion, AccordionDetails, AccordionSummary, Alert, Box, Chip, Divider,
  LinearProgress, List, ListItem, ListItemText, Stack, Tab, Tabs, Typography
} from '@mui/material';
import ExpandMoreRounded from '@mui/icons-material/ExpandMoreRounded';
import { RunStatusChip, traceNodeLabel } from './RunTraceGraph.jsx';

const toolNames = {
  SIMULATED_INSPECTION: '模拟检查', WORKSPACE_READ: '读取授权文件',
  WORKSPACE_SEARCH: '搜索授权文件', WORKSPACE_WRITE: '修改授权文件'
};

const decisionEvents = new Set([
  'NODE_RETRY_SCHEDULED', 'CHECK_STARTED', 'CHECK_FINISHED', 'ASSEMBLY_REJECTED',
  'TOOL_REJECTED', 'TOKEN_USAGE_UNKNOWN', 'BLOCKED', 'LIMIT_REACHED',
  'SUPPLEMENT_REQUESTED', 'SUPPLEMENT_REJECTED'
]);

const short = value => value?.slice(0, 8) || '未记录';
const time = value => value
  ? new Date(value).toLocaleString('zh-CN', { hour12: false })
  : '未记录';

function parseJson(value) {
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

export function TraceSection({ title, subtitle, children, expanded, onChange }) {
  const id = useId();
  return <Accordion disableGutters elevation={0} square expanded={expanded} onChange={onChange}
    sx={{ borderTop: 1, borderColor: 'divider', '&::before': { display: 'none' } }}>
    <AccordionSummary id={`${id}-label`} aria-controls={`${id}-content`} expandIcon={<ExpandMoreRounded />}>
      <Stack spacing={0.25} sx={{ minWidth: 0, overflowWrap: 'anywhere' }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{title}</Typography>
        {subtitle && <Typography variant="caption" color="text.secondary">{subtitle}</Typography>}
      </Stack>
    </AccordionSummary>
    <AccordionDetails sx={{ pt: 0, overflowWrap: 'anywhere' }}>{children}</AccordionDetails>
  </Accordion>;
}

function Readout({ children, mono = false }) {
  return <Typography component={mono ? 'pre' : 'p'} variant="body2" sx={{
    bgcolor: 'action.hover', px: 1.5, py: 1.25, borderRadius: 1.5,
    whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', m: 0,
    fontFamily: mono ? 'Consolas, monospace' : undefined
  }}>{children}</Typography>;
}

function Evidence({ row }) {
  const data = parseJson(row.resourceVersion);
  return <Stack spacing={0.6}>
    <Typography variant="body2">{toolNames[row.tool] || row.tool} v{row.version} · #{short(row.toolCallId)}</Typography>
    {row.resourceVersion && <Readout mono>{data ? JSON.stringify(data, null, 2) : row.resourceVersion}</Readout>}
  </Stack>;
}

function HandoffRecord({ record, detail, parentRunId, api, initiallyExpanded }) {
  const [expanded, setExpanded] = useState(initiallyExpanded);
  const [sender, setSender] = useState(null);
  const [pending, setPending] = useState(false);
  const { senderNodeId, senderAttemptId, senderAgentId } = record;
  const runId = detail.runId;
  const localAttempt = detail.attempts?.find(attempt => attempt.id === senderAttemptId && attempt.agentId === senderAgentId);

  useEffect(() => {
    if (!expanded || localAttempt || !senderNodeId || !senderAttemptId) return undefined;
    let active = true;
    setPending(true);
    (async () => {
      let found = null;
      for (const id of [...new Set([runId, parentRunId].filter(Boolean))]) {
        try {
          const source = await api(`/runs/${id}/execution-view/nodes/${senderNodeId}`);
          const attempt = source.runId === id && source.node?.id === senderNodeId
            ? source.attempts?.find(item => item.id === senderAttemptId && item.agentId === senderAgentId)
            : null;
          if (attempt) {
            found = { attempt, runId: id };
            break;
          }
        } catch {
          // 恢复节点的尝试可能在父运行中，其他缺失情况由面板如实标记。
        }
      }
      if (active) {
        setSender(found);
        setPending(false);
      }
    })();
    return () => { active = false; };
  }, [expanded, localAttempt?.id, senderNodeId, senderAttemptId, senderAgentId, runId, parentRunId, api]);

  const attempt = localAttempt || sender?.attempt;
  const senderLabel = record.dependencyNodeLabel || record.senderNodeLabel || '来源节点未记录';
  const receiverLabel = record.receiverNodeLabel || '接收节点未记录';
  return <TraceSection title={`${senderLabel} → ${receiverLabel}`}
    subtitle={`已接收 · ${time(record.acceptedAt)}`} expanded={expanded}
    onChange={(_, open) => setExpanded(open)}>
    {expanded && <Stack spacing={1.25}>
      <Typography variant="caption" color="text.secondary">
        发送实例 #{short(senderAgentId)} · 尝试 #{short(senderAttemptId)}<br />
        接收实例 #{short(record.receiverAgentId)} · 尝试 #{short(record.receiverAttemptId)}
      </Typography>
      <Typography variant="subtitle2">交接正文</Typography>
      {pending ? <LinearProgress aria-label="读取交接正文" /> : attempt?.result
        ? <Readout>{attempt.result}</Readout>
        : <Typography variant="body2" color="text.secondary">此交接的发送尝试正文未记录或无法读取。</Typography>}
      {sender?.runId && sender.runId !== runId && <Typography variant="caption" color="text.secondary">
        来源为父运行 #{short(sender.runId)}
      </Typography>}
      <Typography variant="subtitle2">随交接附上的工具证据</Typography>
      {record.toolEvidence?.length
        ? record.toolEvidence.map(row => <Evidence key={row.toolCallId} row={row} />)
        : <Typography variant="body2" color="text.secondary">没有附带工具证据。</Typography>}
    </Stack>}
  </TraceSection>;
}

const receiptKey = record => `${record.senderAttemptId}:${record.receiverAttemptId}:${record.dependencyNodeId}`;

function Handoffs({ detail, parentRunId, selectedEdge, api, byId }) {
  const incoming = detail.inboundHandoffs ?? [];
  const outgoing = detail.outboundHandoffs ?? [];
  const records = [...incoming, ...outgoing];
  const selected = selectedEdge
    ? records.filter(record => record.dependencyNodeId === selectedEdge.fromNodeId
      && record.receiverNodeId === selectedEdge.toNodeId)
    : records;
  const checkInputs = (detail.checks ?? []).flatMap(check => (check.inputs ?? []).map(input => ({ check, input })))
    .filter(({ input }) => !selectedEdge || input.dependencyNodeId === selectedEdge.fromNodeId);

  return <Stack spacing={1}>
    {selectedEdge && !selected.length && <Typography variant="subtitle2">
      {traceNodeLabel(byId.get(selectedEdge.fromNodeId))} → {traceNodeLabel(byId.get(selectedEdge.toNodeId))}
    </Typography>}
    {!selectedEdge && <Typography variant="caption" color="text.secondary">
      已接收 {incoming.length} 条 · 已发出 {outgoing.length} 条
    </Typography>}
    {selected.length === 0 && checkInputs.length === 0 && <Alert severity="info">
      {selectedEdge ? '这条连线是计划依赖，当前未查到实际接收记录。' : '此节点当前没有结构化交接记录。'}
    </Alert>}
    {selected.map(record => <HandoffRecord key={receiptKey(record)} record={record}
      detail={detail} parentRunId={parentRunId} api={api} initiallyExpanded={Boolean(selectedEdge)} />)}
    {checkInputs.map(({ check, input }) => <TraceSection
      key={`${check.id}:${input.dependencyNodeId}:${input.sourceAttemptId || input.sourceCheckId}`}
      title={`接收检查输入 · ${traceNodeLabel(byId.get(input.dependencyNodeId))}`}>
      <Stack spacing={0.75}>
        <Typography variant="body2">
          {input.sourceKind === 'SYSTEM_CHECK' ? '来源检查' : '来源尝试'} #{short(input.sourceAttemptId || input.sourceCheckId)}
          {input.sourceRunId && ` · 运行 #${short(input.sourceRunId)}`}
        </Typography>
        {input.resultSha256 && <Readout mono>{input.resultSha256}</Readout>}
      </Stack>
    </TraceSection>)}
  </Stack>;
}

function NodeDecisions({ detail, view, runDetail, eventNames }) {
  const route = parseJson(runDetail?.entryRoute?.EXPLANATION_JSON);
  const { node } = detail;
  const agentIds = new Set((detail.attempts ?? []).map(attempt => attempt.agentId));
  const assemblies = (runDetail?.agentAssemblies ?? [])
    .filter(record => agentIds.has(record.agentId) && record.snapshot?.components);
  const items = (view.blocking ?? []).filter(item => !item.nodeId
    || item.nodeId === node.id || item.blockingNodeIds?.includes(node.id));
  const events = (detail.nodeEvents ?? []).filter(event => event.reasonCode || decisionEvents.has(event.type));
  return <Stack spacing={1}>
    <TraceSection title="为何选择这条流程" subtitle={route?.rule || runDetail?.entryRoute?.RULE_CODE}>
      {route?.reasons?.length
        ? <Stack spacing={0.75}>
          {route.reasons.map((reason, index) => <Typography key={index} variant="body2">{reason}</Typography>)}
          {route.warnings?.map((warning, index) => <Alert key={index} severity="warning">{warning}</Alert>)}
        </Stack>
        : <Typography variant="body2" color="text.secondary">该运行未记录路由决策的依据。</Typography>}
    </TraceSection>
    <TraceSection title="计划、前置与授权" subtitle={node.definitionId
      ? `${node.definitionId} v${node.definitionVersion}`
      : node.checkId ? `${node.checkId} v${node.checkVersion}` : undefined}>
      <Stack spacing={0.75}>
        <Typography variant="body2">
          {node.planVersionId ? node.workspaceWrite ? '可读写授权文件' : node.workspaceRead ? '可读取授权文件' : '不访问工作区'
            : '历史计划未记录授权'}
        </Typography>
        {(node.maxModelCalls != null || node.maxToolCalls != null) && <Typography variant="body2">
          节点上限 · 模型 {node.maxModelCalls ?? '未设置'} · 工具 {node.maxToolCalls ?? '未设置'}
        </Typography>}
        {detail.dependencies?.length ? detail.dependencies.map(dependency =>
          <Stack direction="row" key={dependency.nodeId} spacing={1} sx={{ alignItems: 'center' }}>
            <Typography variant="body2">{dependency.label || short(dependency.nodeId)}</Typography>
            <RunStatusChip value={dependency.status} />
          </Stack>) : <Typography variant="body2" color="text.secondary">无前置节点。</Typography>}
      </Stack>
    </TraceSection>
    {assemblies.map(record => {
      const { definition, model, skills, tools, authorization, input } = record.snapshot.components;
      return <TraceSection key={record.agentId} title="Agent 装配快照"
        subtitle={`实例 #${short(record.agentId)} · ${time(record.recordedAt)}`}>
        <Stack spacing={0.75}>
          <Typography variant="body2">
            定义 {definition?.id ?? '未记录'}{definition?.version != null && ` v${definition.version}`}
            {' · '}模型 {model?.name ?? '未记录'}
          </Typography>
          <Typography variant="body2">
            工具 {Object.entries(tools ?? {}).map(([tool, version]) => `${toolNames[tool] || tool} v${version}`).join('、') || '无'}
          </Typography>
          <Typography variant="body2">
            Skill {(skills ?? []).map(skill => skill.name || skill.id || skill.ref?.id || '未记录名称').join('、') || '无'}
          </Typography>
          {authorization && <Typography variant="body2">
            授权文件 {authorization.fileCount ?? '未记录'} 个
            {authorization.writeAuthorized != null && ` · ${authorization.writeAuthorized ? '允许授权范围内写入' : '无写入授权'}`}
          </Typography>}
          {input?.sources?.length > 0 && <Typography variant="body2">
            输入来源 {input.sources.map(item => `${item.kind} #${short(item.id)}`).join('、')}
          </Typography>}
          {input?.sha256 && <Readout mono>{input.sha256}</Readout>}
        </Stack>
      </TraceSection>;
    })}
    {items.map((item, index) => <TraceSection key={`${item.code}:${index}`}
      title="阻塞或停止依据" subtitle={item.code}>
      <Readout>{item.message}</Readout>
    </TraceSection>)}
    {(detail.checks ?? []).map(check => <TraceSection key={check.id}
      title={`系统检查 · ${check.checkId} v${check.checkVersion}`} subtitle={time(check.finishedAt || check.startedAt)}>
      <Stack spacing={1} sx={{ alignItems: 'flex-start' }}>
        <Chip size="small" label={{ PASSED: '通过', FAILED: '未通过', BLOCKED: '无法判定', RUNNING: '检查中' }[check.status] || check.status}
          color={check.status === 'PASSED' ? 'success' : 'default'} variant="outlined" />
        {check.summary && <Readout>{check.summary}</Readout>}
        {check.evidence && <Readout>{check.evidence}</Readout>}
        {check.externalEvidenceId && <Typography variant="caption">
          外部证据 #{short(check.externalEvidenceId)}
          {check.evidenceCurrent === false && ' · 证据已过期'}
          {check.evidenceCurrent === true && ' · 对应当前工作区'}
        </Typography>}
      </Stack>
    </TraceSection>)}
    {events.length > 0 && <TraceSection title="判断与重试事件" subtitle={`${events.length} 条`}>
      <Events events={events} eventNames={eventNames} />
    </TraceSection>}
  </Stack>;
}

function Events({ events, eventNames }) {
  return <List dense disablePadding>
    {events.map(event => <ListItem key={event.id} disableGutters divider alignItems="flex-start">
      <ListItemText primary={eventNames[event.type] || event.type}
        secondary={<>
          {time(event.occurredAt)}{event.reasonCode && ` · ${event.reasonCode}`}<br />
          {event.message}
        </>} />
    </ListItem>)}
  </List>;
}

function NodeCalls({ detail, eventNames, stopCauseLabels }) {
  return <Stack spacing={1}>
    {(detail.attempts ?? []).map(attempt => <TraceSection key={attempt.id}
      title={`尝试 ${attempt.number} · 实例 #${short(attempt.agentId)}`}
      subtitle={`模型 ${attempt.models?.length ?? 0} 次 · 工具 ${attempt.tools?.length ?? 0} 次`}>
      <Stack spacing={1.5}>
        <Stack direction="row" sx={{ flexWrap: 'wrap', alignItems: 'center', gap: 1 }}>
          <RunStatusChip value={attempt.status} />
          <Typography variant="caption" color="text.secondary">开始 {time(attempt.startedAt)}</Typography>
        </Stack>
        {attempt.stopCause && <Alert severity="warning">
          {stopCauseLabels[attempt.stopCause] || attempt.stopCause}
        </Alert>}
        {attempt.models?.map(call => <Stack key={call.id} spacing={0.35}>
          <Typography variant="body2">{call.name === 'mock' ? '模拟模型' : call.name} · #{short(call.id)}</Typography>
          <Typography variant="caption" color="text.secondary">
            输入 {call.inputTokens ?? '未知'} · 输出 {call.outputTokens ?? '未知'} Token · {call.durationMs == null ? '耗时未知' : `${call.durationMs} ms`}
          </Typography>
          {call.errorMessage && <Readout>{call.errorMessage}</Readout>}
        </Stack>)}
        {attempt.tools?.map(call => <Stack key={call.id} spacing={0.55}>
          <Typography variant="body2">{toolNames[call.name] || call.name} · #{short(call.id)}</Typography>
          <Typography variant="caption" color="text.secondary">
            {call.status} · {call.durationMs == null ? '耗时未知' : `${call.durationMs} ms`}
          </Typography>
          {(call.result || call.errorMessage) && <Readout>{call.errorMessage || call.result}</Readout>}
          {call.resourceVersion && <Readout mono>{call.resourceVersion}</Readout>}
        </Stack>)}
        {attempt.result && <Readout>{attempt.result}</Readout>}
      </Stack>
    </TraceSection>)}
    {detail.result && <TraceSection title={detail.node.kind === 'SYSTEM_CHECK' ? '检查摘要' : '节点结果'}>
      <Readout>{detail.result}</Readout>
    </TraceSection>}
    <TraceSection title="节点时间线" subtitle={`${detail.nodeEvents?.length ?? 0} 条事件`}>
      {detail.nodeEvents?.length ? <Events events={detail.nodeEvents} eventNames={eventNames} />
        : <Typography variant="body2" color="text.secondary">此节点没有已归属的结构化事件。</Typography>}
    </TraceSection>
  </Stack>;
}

export default function RunTraceInspector({
  detail, selectedNode, selectedEdge, view, runDetail, api, byId, loading, error,
  eventNames = {}, stopCauseLabels = {}
}) {
  const [tab, setTab] = useState('handoffs');
  const id = useId();
  const tabs = [
    ['handoffs', '交接'],
    ['decisions', '判断依据'],
    ['calls', '调用与结果']
  ];

  return <Stack spacing={0.5} sx={{ minWidth: 0 }}>
    <Stack direction="row" spacing={1} sx={{ justifyContent: 'space-between', alignItems: 'center', px: 1.5, pt: 1.5 }}>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="caption" color="text.secondary">{selectedEdge ? '选择的连线' : '选择的节点'}</Typography>
        <Typography variant="subtitle1" sx={{ fontWeight: 650 }}>{traceNodeLabel(selectedNode)}</Typography>
      </Box>
      <RunStatusChip value={selectedNode.status} />
    </Stack>
    <Tabs value={tab} onChange={(_, value) => setTab(value)} variant="scrollable"
      scrollButtons="auto" aria-label="节点详情分类">
      {tabs.map(([value, label]) => <Tab key={value} value={value} label={label}
        id={`${id}-${value}`} aria-controls={`${id}-panel-${value}`} />)}
    </Tabs>
    <Divider />
    <Box role="tabpanel" id={`${id}-panel-${tab}`} aria-labelledby={`${id}-${tab}`} sx={{ p: 1.5, minWidth: 0 }}>
      {loading && !detail && !error && <LinearProgress aria-label="读取节点记录" />}
      {error && <Alert severity="error">{error}</Alert>}
      {detail && tab === 'handoffs' && <Handoffs detail={detail} parentRunId={view.parentRunId}
        api={api} selectedEdge={selectedEdge} byId={byId} />}
      {detail && tab === 'decisions' && <NodeDecisions detail={detail} view={view} runDetail={runDetail} eventNames={eventNames} />}
      {detail && tab === 'calls' && <NodeCalls detail={detail} eventNames={eventNames} stopCauseLabels={stopCauseLabels} />}
    </Box>
  </Stack>;
}
