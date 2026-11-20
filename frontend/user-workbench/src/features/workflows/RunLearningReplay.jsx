import React, { useEffect, useState } from 'react';
import {
  Alert, Box, Button, Chip, Divider, LinearProgress, List, ListItemButton, ListItemText,
  Paper, Stack, ToggleButton, ToggleButtonGroup, Typography
} from '@mui/material';
import ArrowBackRounded from '@mui/icons-material/ArrowBackRounded';
import ArrowForwardRounded from '@mui/icons-material/ArrowForwardRounded';
import GpsFixedRounded from '@mui/icons-material/GpsFixedRounded';
import PlayArrowRounded from '@mui/icons-material/PlayArrowRounded';
import PauseRounded from '@mui/icons-material/PauseRounded';
import { TraceSection } from './RunTraceInspector.jsx';

const choices = [
  ['all', '全部'],
  ['control', '判断与交接'],
  ['call', '模型与工具']
];

const time = value => value
  ? new Date(value).toLocaleTimeString('zh-CN', { hour12: false }) : '未记录';

function group(event) {
  return /^(MODEL|TOOL)_/.test(event.EVENT_TYPE) ? 'call' : 'control';
}

function role(event) {
  if (event.EVENT_TYPE?.startsWith('MODEL_')) return '模型调用';
  if (event.EVENT_TYPE?.startsWith('TOOL_')) return '工具调用';
  if (event.EVENT_TYPE?.startsWith('CHECK_')) return '系统检查';
  if (event.EVENT_TYPE?.startsWith('HANDOFF')) return 'Agent 交接';
  if (event.EVENT_TYPE?.startsWith('AGENT_')) return 'Agent 实例';
  return '运行控制';
}

function findById(items, id) {
  return id ? items?.find(item => item.ID === id) : undefined;
}

function StoredText({ label, text }) {
  return <Box sx={{ minWidth: 0 }}>
    <Typography variant="subtitle2" sx={{ mb: 0.5 }}>{label}</Typography>
    <Typography variant="body2" component="pre" sx={{
      m: 0, p: 1.5, borderRadius: 2, bgcolor: 'action.hover', fontFamily: 'inherit',
      whiteSpace: 'pre-wrap', overflowWrap: 'anywhere'
    }}>{text || '未记录'}</Typography>
  </Box>;
}

function ResourceVersions({ raw }) {
  if (!raw) return null;
  let paths;
  try { paths = typeof raw === 'string' ? JSON.parse(raw) : raw; } catch { paths = null; }
  if (!paths || typeof paths !== 'object' || Array.isArray(paths)) {
    return <StoredText label="记录的资源版本" text={raw} />;
  }
  return <Stack spacing={0.65}>
    <Typography variant="subtitle2">记录的文件版本</Typography>
    {Object.entries(paths).map(([path, version]) => <Box key={path} sx={{ overflowWrap: 'anywhere' }}>
      <Typography variant="body2">{path}</Typography>
      <Typography variant="caption" color="text.secondary">{String(version)}</Typography>
    </Box>)}
  </Stack>;
}

function Evidence({ event, run, nodeDetail, loading }) {
  const starting = event.EVENT_TYPE?.endsWith('_STARTED');
  const tool = findById(run.tools, event.TOOL_CALL_ID);
  const model = findById(run.models, event.MODEL_CALL_ID);
  const agent = findById(run.agents, event.AGENT_ID);
  const check = event.CHECK_ID ? nodeDetail?.checks?.find(item => item.id === event.CHECK_ID) : undefined;
  const handoffs = event.EVENT_TYPE?.startsWith('HANDOFF') && event.ATTEMPT_ID
    ? nodeDetail?.inboundHandoffs?.filter(row => row.receiverAttemptId === event.ATTEMPT_ID) ?? []
    : [];
  const checkReceipts = event.EVENT_TYPE?.startsWith('HANDOFF') && event.ATTEMPT_ID
    ? nodeDetail?.checkReceipts?.filter(row => row.receiverAttemptId === event.ATTEMPT_ID) ?? []
    : [];
  const raw = [
    ['事件记录', event],
    ['工具调用记录', tool],
    ['模型调用记录', model],
    ['检查记录', check],
    ['Agent 实例记录', agent],
    ['接收尝试的交接记录', handoffs.length ? handoffs : null],
    ['系统检查的接收记录', checkReceipts.length ? checkReceipts : null]
  ].filter(([, record]) => record);

  return <Stack spacing={2}>
    {tool && <Stack spacing={1.2}>
      <Typography variant="subtitle2">{tool.TOOL_NAME} · {starting ? '开始调用' : tool.STATUS}</Typography>
      {!starting && tool.RESULT && <StoredText label="工具返回记录" text={tool.RESULT} />}
      {!starting && tool.ERROR_MESSAGE && <StoredText label="工具错误记录" text={tool.ERROR_MESSAGE} />}
      {!starting && <ResourceVersions raw={tool.RESOURCE_VERSION} />}
    </Stack>}
    {model && <Stack spacing={0.6}>
      <Typography variant="subtitle2">{model.MODEL_NAME} · {starting ? '开始调用' : model.STATUS}</Typography>
      {!starting && <Typography variant="body2">输入 {model.INPUT_TOKENS ?? '未知'} / 输出 {model.OUTPUT_TOKENS ?? '未知'} Token</Typography>}
      {!starting && model.ERROR_MESSAGE && <StoredText label="模型错误记录" text={model.ERROR_MESSAGE} />}
    </Stack>}
    {check && <Stack spacing={1.2}>
      <Typography variant="subtitle2">{check.checkId} · {starting ? '开始检查' : check.status}</Typography>
      {!starting && <StoredText label="检查结果" text={check.summary} />}
      {!starting && check.evidence && <StoredText label="检查证据" text={check.evidence} />}
      {check.inputs?.map(input => <StoredText key={input.dependencyNodeId}
        label="检查时读取的上一步结果" text={input.sourceResult} />)}
    </Stack>}
    {handoffs.map(row => <Stack key={`${row.receiverAttemptId}:${row.dependencyNodeId}`} spacing={1}>
      <Typography variant="subtitle2">{row.senderNodeLabel || row.dependencyNodeLabel || '来源节点'} → {row.receiverNodeLabel || '接收节点'}</Typography>
      <StoredText label="交接正文" text={row.sourceResult} />
      {row.toolEvidence?.map(evidence => <ResourceVersions key={evidence.toolCallId} raw={evidence.resourceVersion} />)}
    </Stack>)}
    {checkReceipts.map(row => <Stack key={row.checkId} spacing={1}>
      <Typography variant="subtitle2">{row.dependencyNodeLabel || '系统检查'} → {row.receiverNodeLabel || '接收节点'}</Typography>
      <StoredText label="收到的检查结果" text={row.summary} />
      {row.evidence && <StoredText label="检查证据" text={row.evidence} />}
    </Stack>)}
    {!starting && agent?.RESULT && !event.EVENT_TYPE?.startsWith('HANDOFF') && !tool && !model
      && <StoredText label="Agent 实例保存的结果" text={agent.RESULT} />}
    {loading && <LinearProgress aria-label="加载关联记录" />}
    <TraceSection title="数据库原记录" subtitle={`${raw.length} 组可见字段`}>
      <Stack spacing={1.5}>
        <Typography variant="caption" color="text.secondary">关联记录显示数据库当前保存的字段，可能包含该事件之后的结算结果。</Typography>
        {raw.map(([label, record]) => <StoredText key={label} label={label} text={JSON.stringify(record, null, 2)} />)}
      </Stack>
    </TraceSection>
  </Stack>;
}

/** 按服务端事件序号回放，选中事件后读取该时刻关联的记录。 */
export default function RunLearningReplay({ view, detail, api, eventNames, onLocate }) {
  const [filter, setFilter] = useState('all');
  const [cursor, setCursor] = useState(null);
  const [playing, setPlaying] = useState(false);
  const [nodeDetail, setNodeDetail] = useState(null);
  const [error, setError] = useState(null);
  const events = (detail?.events ?? []).filter(event => filter === 'all' || group(event) === filter);
  const index = Math.max(0, events.findIndex(event => event.ID === cursor));
  const event = events[index];
  const nextId = events[index + 1]?.ID;
  const nodeId = event?.NODE_ID;
  const node = view.nodes?.find(item => item.id === nodeId);
  const matchingNodeDetail = nodeDetail?.runId === view.runId && nodeDetail?.node?.id === nodeId ? nodeDetail : null;

  useEffect(() => {
    if (!playing) return undefined;
    const timer = setTimeout(() => {
      if (nextId == null) setPlaying(false);
      else setCursor(nextId);
    }, 1300);
    return () => clearTimeout(timer);
  }, [playing, nextId]);

  useEffect(() => {
    setError(null);
    if (!nodeId || !event?.CHECK_ID && !event?.EVENT_TYPE?.startsWith('HANDOFF')) return undefined;
    let current = true;
    api(`/runs/${view.runId}/execution-view/nodes/${nodeId}`)
      .then(row => { if (current) setNodeDetail(row); })
      .catch(err => { if (current) setError(err.message); });
    return () => { current = false; };
  }, [api, view.runId, nodeId, event?.CHECK_ID, event?.EVENT_TYPE]);

  function choose(id) {
    setPlaying(false);
    setCursor(id);
  }

  return <Stack spacing={1.5}>
    <Paper variant="outlined" sx={{ p: 1.5 }}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1 }}>
        <ToggleButtonGroup exclusive size="small" aria-label="回放事件类别" value={filter}
          onChange={(_, value) => { if (value) { setFilter(value); choose(null); } }}>
          {choices.map(([value, name]) => <ToggleButton key={value} value={value}>{name}</ToggleButton>)}
        </ToggleButtonGroup>
        <Stack direction="row" sx={{ alignItems: 'center', gap: 0.5, flexWrap: 'wrap' }}>
          <Button size="small" startIcon={<ArrowBackRounded />} disabled={index === 0}
            onClick={() => choose(events[index - 1]?.ID)}>上一步</Button>
          <Button size="small" variant="outlined" startIcon={playing ? <PauseRounded /> : <PlayArrowRounded />}
            disabled={!playing && nextId == null} onClick={() => setPlaying(value => !value)}>
            {playing ? '暂停' : '播放'}
          </Button>
          <Button size="small" endIcon={<ArrowForwardRounded />} disabled={nextId == null}
            onClick={() => choose(nextId)}>下一步</Button>
        </Stack>
      </Stack>
    </Paper>
    {!event ? <Alert severity="info">当前类别下没有已记录的事件。</Alert> :
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0,1fr)', lg: 'minmax(190px,.72fr) minmax(0,1.55fr)' }, alignItems: 'start', gap: 1.5 }}>
        <Paper variant="outlined" sx={{ minWidth: 0 }}>
          <Typography component="h3" variant="subtitle2" sx={{ px: 1.5, py: 1.25 }}>
            事件顺序 · {index + 1} / {events.length}
          </Typography>
          <Divider />
          <List dense disablePadding aria-label="历史事件" sx={{ maxHeight: { xs: 180, lg: 510 }, overflow: 'auto', p: 0.6 }}>
            {events.map(item => <ListItemButton key={item.ID} selected={item.ID === event.ID}
              onClick={() => choose(item.ID)} sx={{ borderRadius: 1.5 }}>
              <ListItemText primary={eventNames[item.EVENT_TYPE] || item.EVENT_TYPE}
                secondary={`#${item.RUN_SEQ ?? item.ID} · ${time(item.CREATED_AT)}`} />
            </ListItemButton>)}
          </List>
        </Paper>
        <Paper variant="outlined" sx={{ minWidth: 0, p: { xs: 1.4, sm: 2 } }}>
          <Stack spacing={1.45}>
            <Stack direction="row" sx={{ alignItems: 'center', gap: 0.75, flexWrap: 'wrap' }}>
              <Chip size="small" color={group(event) === 'call' ? 'info' : 'primary'} variant="outlined" label={role(event)} />
              <Typography variant="caption" color="text.secondary">{time(event.CREATED_AT)}</Typography>
            </Stack>
            <Typography component="h3" variant="h6">{eventNames[event.EVENT_TYPE] || event.EVENT_TYPE}</Typography>
            <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>{event.MESSAGE || '未记录事件正文'}</Typography>
            {event.REASON_CODE && <Typography variant="caption" color="text.secondary">原因编码 {event.REASON_CODE}</Typography>}
            {node && <Button size="small" startIcon={<GpsFixedRounded />} onClick={() => onLocate(nodeId, event.EVENT_TYPE)}
              sx={{ alignSelf: 'flex-start' }}>在流程图定位「{node.label || node.key}」</Button>}
            {error && <Alert severity="warning">关联记录未读取成功：{error}</Alert>}
            <Divider />
            <Evidence event={event} run={detail} nodeDetail={matchingNodeDetail}
              loading={Boolean(nodeId && !matchingNodeDetail && !error && (event.CHECK_ID || event.EVENT_TYPE?.startsWith('HANDOFF')))} />
          </Stack>
        </Paper>
      </Box>}
  </Stack>;
}
