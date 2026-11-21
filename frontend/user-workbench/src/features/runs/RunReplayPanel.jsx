import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  List,
  ListItemButton,
  ListItemText,
  Paper,
  Stack,
  ToggleButton,
  ToggleButtonGroup,
  Typography
} from '@mui/material';
import ArrowBackRounded from '@mui/icons-material/ArrowBackRounded';
import ArrowForwardRounded from '@mui/icons-material/ArrowForwardRounded';
import PlayArrowRounded from '@mui/icons-material/PlayArrowRounded';
import PauseRounded from '@mui/icons-material/PauseRounded';
import GpsFixedRounded from '@mui/icons-material/GpsFixedRounded';
import { runsApi } from '../../api/runs';
import { TraceSection, Readout } from '../../components/WorkbenchDialog';
import { clock } from '../../utils/format';

const filters = [
  ['all', '全部'],
  ['control', '判断与交接'],
  ['call', '模型与工具']
];

const group = event => (/^(MODEL|TOOL)_/.test(event.EVENT_TYPE) ? 'call' : 'control');

const roleOf = event => {
  if (event.EVENT_TYPE?.startsWith('MODEL_')) return '模型调用';
  if (event.EVENT_TYPE?.startsWith('TOOL_')) return '工具调用';
  if (event.EVENT_TYPE?.startsWith('CHECK_')) return '系统检查';
  if (event.EVENT_TYPE?.startsWith('HANDOFF')) return 'Agent 交接';
  if (event.EVENT_TYPE?.startsWith('AGENT_')) return 'Agent 实例';
  return '运行控制';
};

const findById = (items, id) => (id ? items?.find(item => item.ID === id) : undefined);

function Evidence({ event, run, nodeDetail }) {
  const tool = findById(run?.tools, event.TOOL_CALL_ID);
  const model = findById(run?.models, event.MODEL_CALL_ID);
  const agent = findById(run?.agents, event.AGENT_ID);
  const check = event.CHECK_ID
    ? nodeDetail?.checks?.find(item => item.id === event.CHECK_ID)
    : undefined;
  const handoffs =
    event.EVENT_TYPE?.startsWith('HANDOFF') && event.ATTEMPT_ID
      ? (nodeDetail?.inboundHandoffs?.filter(row => row.receiverAttemptId === event.ATTEMPT_ID) ??
        [])
      : [];
  const starting = event.EVENT_TYPE?.endsWith('_STARTED');

  return (
    <Stack spacing={1.5}>
      {tool && (
        <Box>
          <Typography variant="subtitle2">
            {tool.TOOL_NAME} · {starting ? '开始调用' : tool.STATUS}
          </Typography>
          {!starting && tool.RESULT && <Readout>{tool.RESULT}</Readout>}
          {!starting && tool.ERROR_MESSAGE && <Readout>{tool.ERROR_MESSAGE}</Readout>}
          {!starting && tool.RESOURCE_VERSION && (
            <TraceSection title="记录的资源版本" dense>
              <Readout mono>{tool.RESOURCE_VERSION}</Readout>
            </TraceSection>
          )}
        </Box>
      )}
      {model && (
        <Box>
          <Typography variant="subtitle2">
            {model.MODEL_NAME} · {starting ? '开始调用' : model.STATUS}
          </Typography>
          {!starting && (
            <Typography variant="body2">
              输入 {model.INPUT_TOKENS ?? '未知'} / 输出 {model.OUTPUT_TOKENS ?? '未知'} Token
            </Typography>
          )}
          {!starting && model.ERROR_MESSAGE && <Readout>{model.ERROR_MESSAGE}</Readout>}
        </Box>
      )}
      {check && (
        <Box>
          <Typography variant="subtitle2">
            {check.checkId} · {starting ? '开始检查' : check.status}
          </Typography>
          {!starting && check.summary && <Readout>{check.summary}</Readout>}
          {!starting && check.evidence && <Readout>{check.evidence}</Readout>}
        </Box>
      )}
      {handoffs.map(row => (
        <Box key={`${row.receiverAttemptId}:${row.dependencyNodeId}`}>
          <Typography variant="subtitle2">
            {row.senderNodeLabel || row.dependencyNodeLabel || '来源节点'} →{' '}
            {row.receiverNodeLabel || '接收节点'}
          </Typography>
          <Readout>{row.sourceResult || '交接正文未记录。'}</Readout>
          {row.toolEvidence?.map(evidence => (
            <Typography
              key={evidence.toolCallId}
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block' }}
            >
              {evidence.tool} v{evidence.version} · {evidence.toolCallId}
            </Typography>
          ))}
        </Box>
      ))}
      {!starting && agent?.RESULT && !tool && !model && (
        <Box>
          <Typography variant="subtitle2">Agent 实例保存的结果</Typography>
          <Readout>{agent.RESULT}</Readout>
        </Box>
      )}
      {!tool && !model && !check && handoffs.length === 0 && !agent?.RESULT && (
        <Typography variant="body2" color="text.secondary">
          该事件没有关联的模型、工具或检查正文；可展开数据库原记录查看字段。
        </Typography>
      )}
      <TraceSection title="数据库原记录" subtitle="可能包含事件之后的结算结果">
        <Readout mono maxHeight={220}>
          {JSON.stringify(
            [
              ['事件记录', event],
              ['工具调用记录', tool],
              ['模型调用记录', model],
              ['检查记录', check],
              ['Agent 实例记录', agent]
            ].filter(([, record]) => record),
            null,
            2
          )}
        </Readout>
      </TraceSection>
    </Stack>
  );
}

/** 只保留当前运行的事件；历史事件缺少运行标识时按兼容处理保留。 */
export function scopeEvents(events, runId) {
  if (!runId) return [];
  return (events ?? []).filter(event => !event.RUN_ID || event.RUN_ID === runId);
}

/** 按服务端事件序号逐步回放；事件与运行快照来自真实持久化记录。 */
export default function RunReplayPanel({
  view,
  detail,
  events,
  eventsLoading = false,
  eventNames,
  onLocate
}) {
  const [filter, setFilter] = useState('all');
  const [selectedId, setSelectedId] = useState(null);
  const [playing, setPlaying] = useState(false);
  const [nodeDetail, setNodeDetail] = useState(null);
  const [error, setError] = useState(null);

  const ready = Boolean(detail?.run && view?.runId);
  const scopedEvents = scopeEvents(events, view?.runId);

  const visible = scopedEvents.filter(event => filter === 'all' || group(event) === filter);
  const index = Math.max(
    0,
    visible.findIndex(event => event.ID === selectedId)
  );
  const event = visible[index];
  const nextId = visible[index + 1]?.ID;
  const nodeId = event?.NODE_ID;

  useEffect(() => {
    if (!playing) return undefined;
    const timer = setTimeout(() => {
      if (nextId == null) setPlaying(false);
      else setSelectedId(nextId);
    }, 1200);
    return () => clearTimeout(timer);
  }, [playing, nextId]);

  useEffect(() => {
    setSelectedId(null);
    setPlaying(false);
    setNodeDetail(null);
    setError(null);
  }, [view?.runId]);

  useEffect(() => {
    setError(null);
    if (
      !view?.runId ||
      !nodeId ||
      (event?.RUN_ID && event.RUN_ID !== view.runId) ||
      (!event?.CHECK_ID && !event?.EVENT_TYPE?.startsWith('HANDOFF'))
    )
      return undefined;
    let active = true;
    runsApi
      .nodeDetail(view.runId, nodeId)
      .then(row => {
        if (active) setNodeDetail(row);
      })
      .catch(failure => {
        if (active) setError(failure.message);
      });
    return () => {
      active = false;
    };
  }, [view?.runId, nodeId, event?.RUN_ID, event?.CHECK_ID, event?.EVENT_TYPE]);

  if (!ready) {
    return (
      <Box sx={{ height: '100%', display: 'grid', placeItems: 'center', px: 3 }}>
        <Stack spacing={1.2} sx={{ alignItems: 'center' }}>
          <CircularProgress size={22} />
          <Typography variant="body2" color="text.secondary">
            正在读取当前运行的执行记录…
          </Typography>
        </Stack>
      </Box>
    );
  }

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Stack spacing={2} sx={{ maxWidth: 1180, mx: 'auto', px: { xs: 2, sm: 3 }, py: 2.5 }}>
        <Paper
          variant="outlined"
          sx={{
            p: 1.5,
            borderRadius: 3,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 1.5,
            flexWrap: 'wrap'
          }}
        >
          <ToggleButtonGroup
            exclusive
            size="small"
            value={filter}
            onChange={(_, value) => {
              if (!value) return;
              setFilter(value);
              setSelectedId(null);
              setPlaying(false);
            }}
          >
            {filters.map(([value, label]) => (
              <ToggleButton key={value} value={value}>
                {label}
              </ToggleButton>
            ))}
          </ToggleButtonGroup>
          <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
            <Typography variant="caption" color="text.secondary" sx={{ mr: 0.5 }}>
              {visible.length ? index + 1 : 0} / {visible.length}
            </Typography>
            <Button
              size="small"
              startIcon={<ArrowBackRounded />}
              disabled={index === 0}
              onClick={() => {
                setPlaying(false);
                setSelectedId(visible[index - 1]?.ID);
              }}
            >
              上一步
            </Button>
            <Button
              size="small"
              variant="outlined"
              startIcon={playing ? <PauseRounded /> : <PlayArrowRounded />}
              disabled={!playing && nextId == null}
              onClick={() => setPlaying(value => !value)}
            >
              {playing ? '暂停' : '播放'}
            </Button>
            <Button
              size="small"
              endIcon={<ArrowForwardRounded />}
              disabled={nextId == null}
              onClick={() => {
                setPlaying(false);
                setSelectedId(nextId);
              }}
            >
              下一步
            </Button>
          </Stack>
        </Paper>

        {!event ? (
          <Alert severity="info">
            {eventsLoading
              ? '正在读取当前运行的事件…'
              : scopedEvents.length
                ? '当前类别下没有已记录的事件。'
                : '当前运行没有已记录的事件。'}
          </Alert>
        ) : (
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', lg: '260px minmax(0, 1fr)' },
              gap: 2,
              alignItems: 'start'
            }}
          >
            <Paper variant="outlined" sx={{ borderRadius: 3, overflow: 'hidden' }}>
              <Typography sx={{ px: 1.5, py: 1.2, fontSize: 13, fontWeight: 600 }}>
                事件顺序
              </Typography>
              <Divider />
              <List
                dense
                disablePadding
                sx={{ maxHeight: { xs: 220, lg: 540 }, overflowY: 'auto', p: 0.6 }}
              >
                {visible.map(item => (
                  <ListItemButton
                    key={item.ID}
                    selected={item.ID === event.ID}
                    onClick={() => {
                      setPlaying(false);
                      setSelectedId(item.ID);
                    }}
                    sx={{ borderRadius: 2 }}
                  >
                    <ListItemText
                      primary={eventNames[item.EVENT_TYPE] || item.EVENT_TYPE}
                      secondary={`#${item.RUN_SEQ ?? item.ID} · ${clock(item.CREATED_AT)}`}
                      slotProps={{ primary: { fontSize: 13 }, secondary: { fontSize: 11.5 } }}
                    />
                  </ListItemButton>
                ))}
              </List>
            </Paper>

            <Paper variant="outlined" sx={{ p: { xs: 1.75, sm: 2.25 }, borderRadius: 3 }}>
              <Stack spacing={1.4}>
                <Stack
                  direction="row"
                  spacing={0.75}
                  sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                >
                  <Chip
                    size="small"
                    label={roleOf(event)}
                    sx={{ bgcolor: '#E8F0FE', color: '#1967D2' }}
                  />

                  <Typography variant="caption" color="text.secondary">
                    {clock(event.CREATED_AT)} · 事件 #{event.RUN_SEQ ?? '—'}
                  </Typography>
                </Stack>
                <Typography sx={{ fontSize: 16, fontWeight: 500 }}>
                  {eventNames[event.EVENT_TYPE] || event.EVENT_TYPE}
                </Typography>
                <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>
                  {event.MESSAGE || '未记录事件正文'}
                </Typography>
                {event.REASON_CODE && (
                  <Typography variant="caption" color="text.secondary">
                    原因编码 {event.REASON_CODE}
                  </Typography>
                )}
                {nodeId && (
                  <Button
                    size="small"
                    startIcon={<GpsFixedRounded />}
                    onClick={() => onLocate?.(nodeId, event.EVENT_TYPE)}
                    sx={{ alignSelf: 'flex-start' }}
                  >
                    在流程图定位
                  </Button>
                )}
                {error && <Alert severity="warning">关联记录未读取成功：{error}</Alert>}
                <Divider />
                <Evidence event={event} run={detail} nodeDetail={nodeDetail} />
              </Stack>
            </Paper>
          </Box>
        )}
      </Stack>
    </Box>
  );
}
