import React, { useEffect, useState } from 'react';
import { Alert, Box, Button, Chip, Divider, LinearProgress, Paper, Stack, Typography } from '@mui/material';
import DownloadOutlined from '@mui/icons-material/DownloadOutlined';
import DescriptionOutlined from '@mui/icons-material/DescriptionOutlined';
import FactCheckOutlined from '@mui/icons-material/FactCheckOutlined';
import WorkspaceDeliverySummary from '../workspaces/WorkspaceDeliverySummary.jsx';
import { TraceSection } from './RunTraceInspector.jsx';

const settledLabels = {
  COMPLETED: '运行已完成',
  BLOCKED: '运行待处理',
  CANCELLED: '运行已取消',
  FAILED: '运行失败',
  INTERRUPTED: '运行已中断',
  LIMITED: '运行已达限制'
};

const checkStatuses = {
  PASSED: ['通过', 'success'], FAILED: ['未通过', 'error'], BLOCKED: ['无法判定', 'warning'],
  RUNNING: ['检查中', 'info'], INTERRUPTED: ['已中断', 'warning'], STALE: ['已过期', 'warning']
};

export function deliveryRunStatus(status) {
  return settledLabels[status];
}

function writtenFiles(tools) {
  const files = new Map();
  for (const tool of tools ?? []) {
    if (tool.STATUS !== 'COMPLETED' || !['WORKSPACE_WRITE', '修改授权文件'].includes(tool.TOOL_NAME)) continue;
    try {
      const versions = JSON.parse(tool.RESOURCE_VERSION);
      for (const [path, version] of Object.entries(versions ?? {})) {
        if (path && typeof version === 'string' && /^[a-f0-9]{64}$/i.test(version)) {
          files.set(path, { path, version, callId: tool.ID });
        }
      }
    } catch {
      // 历史写入缺少资源版本时仍可查看调用，无法核对当前文件内容。
    }
  }
  return [...files.values()];
}

function FileOutput({ item, workspaceId, api, runId }) {
  const [state, setState] = useState({ phase: 'initial' });
  async function load(download) {
    setState({ phase: 'loading' });
    try {
      // 运行级接口校验路径属于本次交付，并在摘要变化时不返回正文。
      const file = runId
        ? await api(`/runs/${runId}/delivery/files?path=${encodeURIComponent(item.path)}`)
        : await api(`/workspaces/${workspaceId}/file?path=${encodeURIComponent(item.path)}`);
      const matches = file.currentMatches ?? (file.sha256 === item.version);
      if (!matches) {
        setState({ phase: 'error', message: '当前文件版本已变化，请从本次运行的调用证据查看原版本记录。' });
        return;
      }
      const text = file.content ?? '';
      setState({ phase: 'ready', text });
      if (download) {
        const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = item.path.split('/').at(-1);
        anchor.click();
        setTimeout(() => URL.revokeObjectURL(url), 5000);
      }
    } catch (err) {
      setState({ phase: 'error', message: err.message });
    }
  }

  function download() { return load(true); }
  function display() { return load(false); }

  return <TraceSection title={item.path} subtitle={`${item.nodeLabel ? `产出节点 ${item.nodeLabel} · ` : ''}本次运行写入 · 路径相对工作区`}>
    <Stack spacing={1.1}>
      <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 0.75 }}>
        <Button size="small" startIcon={<DescriptionOutlined />} onClick={display} disabled={state.phase === 'loading'}>
          查看文件正文
        </Button>
        <Button size="small" startIcon={<DownloadOutlined />} onClick={download} disabled={state.phase === 'loading'}>
          下载文件
        </Button>
      </Stack>
      {state.phase === 'loading' && <LinearProgress aria-label="核对文件版本" />}
      {state.phase === 'error' && <Alert severity="warning">{state.message}</Alert>}
      {state.phase === 'ready' && <Typography component="pre" variant="body2" sx={{
        m: 0, p: 1.25, bgcolor: 'action.hover', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere'
      }}>{state.text || '空文件'}</Typography>}
      <TraceSection title="本次写入记录">
        <Typography variant="caption" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>
          调用 {item.callId} · SHA-256 {item.version}
        </Typography>
      </TraceSection>
    </Stack>
  </TraceSection>;
}

function DeliveryChecks({ detail, view }) {
  const latestChecks = new Map();
  for (const check of detail.workflowChecks ?? []) {
    const previous = latestChecks.get(check.nodeId);
    if (!previous || check.attemptNo >= previous.attemptNo) latestChecks.set(check.nodeId, check);
  }
  return <>
    <Stack direction="row" sx={{ gap: 0.75, alignItems: 'center', mb: 1 }}>
      <FactCheckOutlined fontSize="small" color="primary" />
      <Typography component="h3" variant="h6">核对情况</Typography>
    </Stack>
    {!latestChecks.size && <Typography variant="body2" color="text.secondary">
      本次运行没有保存独立的系统检查记录。
    </Typography>}
    {[...latestChecks.values()].map(check => {
      const name = view?.nodes?.find(node => node.id === check.nodeId)?.label || check.check?.id || check.nodeKey;
      const [label, color] = check.evidenceCurrent === false
        ? ['证据已过期', 'warning'] : checkStatuses[check.status] ?? [check.status, 'default'];
      return <Box key={check.id} sx={{ py: 1, borderTop: 1, borderColor: 'divider' }}>
        <Stack direction="row" sx={{ gap: 0.75, alignItems: 'center', flexWrap: 'wrap', mb: 0.5 }}>
          <Typography variant="subtitle2">{name}</Typography>
          <Chip size="small" variant="outlined" label={label} color={color} />
        </Stack>
        {check.evidenceCurrent === false && <Typography variant="caption" color="warning.dark">以下为检查时保存的结论。</Typography>}
        {check.summary && <Typography variant="body2" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>{check.summary}</Typography>}
        {check.evidence && <TraceSection title="检查证据">
          <Typography component="pre" variant="body2" sx={{ m: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
            {check.evidence}
          </Typography>
        </TraceSection>}
      </Box>;
    })}
  </>;
}

function finalAgentResult(detail, view) {
  if (detail.run?.STATUS !== 'COMPLETED' || !view?.nodes?.length) return null;
  const prerequisites = new Set((view.edges ?? []).map(edge => edge.fromNodeId));
  const endpoints = view.nodes.filter(node => !prerequisites.has(node.id));
  if (endpoints.length !== 1) return null;
  const [node] = endpoints;
  if (node.kind !== 'AGENT' || node.status !== 'COMPLETED' || !node.agentId) return null;
  const agent = detail.agents?.find(item => item.ID === node.agentId && item.STATUS === 'COMPLETED');
  return agent?.RESULT?.trim() ? { text: agent.RESULT, label: node.label } : null;
}

export default function RunOutputsPanel({ detail, view, api, workspaceOpen, onOpenWorkspace }) {
  const files = writtenFiles(detail.tools);
  const workspace = detail.task?.WORKSPACE_ID;
  const run = detail.run;
  const [delivery, setDelivery] = useState(null);
  useEffect(() => {
    let active = true;
    if (!run?.ID) { setDelivery(null); return undefined; }
    api(`/runs/${run.ID}/delivery`).then(value => { if (active) setDelivery(value); }).catch(() => { if (active) setDelivery(null); });
    return () => { active = false; };
  }, [run?.ID]);
  // 交付投影来自服务端写入回执；旧记录缺少投影时回退到工具证据。
  const delivered = delivery?.files?.length
    ? delivery.files.map(file => ({
        path: file.path, version: file.sha256, callId: file.toolCallId,
        nodeLabel: file.nodeLabel, currentMatches: file.currentMatches
      }))
    : files;
  const writerNodes = (delivery?.nodes ?? []).filter(node => node.files?.length);
  const handoffNodes = (delivery?.nodes ?? []).filter(node => node.inbound?.length);
  const original = run?.RESULT?.trim() ? run.RESULT : null;
  const finalAgent = finalAgentResult(detail, view);
  const result = finalAgent?.text ?? original;
  const status = deliveryRunStatus(run?.STATUS);
  return <Stack spacing={1.5} sx={{ mt: 0.5 }}>
    <Paper variant="outlined" sx={{ p: { xs: 1.6, sm: 2.5 }, borderColor: 'primary.light', bgcolor: '#f6f9ff' }}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 0.75, mb: 1 }}>
        <Typography component="h3" variant="h5">本次结果</Typography>
        <Chip size="small" color={run?.STATUS === 'COMPLETED' ? 'success' : status ? 'warning' : 'info'}
          variant="outlined" label={status ?? '运行尚未结算'} />
      </Stack>
      {run?.REASON && <Alert sx={{ mb: 1 }} severity="warning">{run.REASON}</Alert>}
      {finalAgent?.label && <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.55 }}>
        Agent 结论来自「{finalAgent.label}」
      </Typography>}
      <Typography component={result ? 'pre' : 'p'} variant="body2" color={result ? 'text.primary' : 'text.secondary'} sx={{
        m: 0, lineHeight: 1.7, maxHeight: 'min(48vh, 430px)', overflowY: 'auto',
        whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontFamily: 'inherit'
      }}>{result || (status ? '本次运行未留下最终输出。' : '最终输出会在结算后显示。')}</Typography>
      {finalAgent && original && original !== finalAgent.text && <Box sx={{ mt: 1.4 }}>
        <TraceSection title="查看完整运行输出">
          <Typography component="pre" variant="body2" sx={{ m: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontFamily: 'inherit' }}>
            {original}
          </Typography>
        </TraceSection>
      </Box>}
    </Paper>
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0,1fr)', lg: 'minmax(0,1.4fr) minmax(0,1fr)' }, alignItems: 'start', gap: 1.5 }}>
      <Paper variant="outlined" sx={{ p: { xs: 1.6, sm: 2 }, minWidth: 0 }}>
        <Typography component="h3" variant="h6" sx={{ mb: 1 }}>交付位置</Typography>
        <Stack spacing={1.5}>
          <Box>
            <Typography component="h4" variant="subtitle2" sx={{ mb: 0.55 }}>本次运行的文件</Typography>
            {workspace && delivered.length
              ? delivered.map(item => <FileOutput key={`${item.path}:${item.version}`} item={item} workspaceId={workspace} api={api} runId={run.ID} />)
              : <Typography variant="body2" color="text.secondary">本次运行没有可交付的文件记录。</Typography>}
          </Box>
          {writerNodes.length > 0 && <Divider />}
          {writerNodes.map(node => <Box key={node.nodeId}>
            <Stack direction="row" sx={{ gap: 0.75, alignItems: 'center', flexWrap: 'wrap', mb: 0.4 }}>
              <Typography component="h4" variant="subtitle2">{node.label}</Typography>
              <Chip size="small" variant="outlined" label={node.role === 'CODER' ? '开发' : node.role === 'ANALYST' ? '分析' : '审查'} />
            </Stack>
            <Typography variant="body2" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>
              写入：{node.files.map(file => file.path).join('、')}
            </Typography>
            {node.missingRequiredFiles?.length > 0 && <Alert severity="warning" sx={{ mt: 0.75 }}>
              缺少必需文件：{node.missingRequiredFiles.join('、')}
            </Alert>}
          </Box>)}
          <Divider />
          {workspace && <WorkspaceDeliverySummary key={workspace} workspaceId={workspace} api={api}
            workspaceOpen={workspaceOpen} onOpenWorkspace={onOpenWorkspace} />}
          <Button size="small" startIcon={<DownloadOutlined />} href={`/api/runs/${run.ID}/report`}
            sx={{ alignSelf: 'flex-start' }}>下载运行报告</Button>
        </Stack>
      </Paper>
      <Paper variant="outlined" sx={{ p: { xs: 1.6, sm: 2 }, minWidth: 0 }}>
        <DeliveryChecks detail={detail} view={view} />
        {handoffNodes.length > 0 && <>
          <Divider sx={{ my: 1.5 }} />
          <Typography component="h3" variant="h6" sx={{ mb: 0.75 }}>节点收到的交接</Typography>
          {handoffNodes.map(node => <TraceSection key={node.nodeId} title={node.label}>
            {node.inbound.map(handoff => <Typography variant="body2" key={`${handoff.fromNodeId}:${handoff.attemptId}`} sx={{ overflowWrap: 'anywhere' }}>
              {handoff.fromLabel} → {handoff.toLabel}（接收尝试 {handoff.attemptId.slice(0, 8)}…）
            </Typography>)}
          </TraceSection>)}
        </>}
        {delivery?.unverified?.length > 0 && <Alert severity="warning" sx={{ mt: 1.5 }}>
          {delivery.unverified.map(item => <Typography variant="body2" key={item}>{item}</Typography>)}
        </Alert>}
      </Paper>
    </Box>
  </Stack>;
}
