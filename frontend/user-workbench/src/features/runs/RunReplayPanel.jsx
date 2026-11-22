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
import { useI18n } from '../../i18n/I18nProvider';

const filters = ['all', 'control', 'call'];

const group = event => (/^(MODEL|TOOL)_/.test(event.EVENT_TYPE) ? 'call' : 'control');

const roleKey = event => {
  if (event.EVENT_TYPE?.startsWith('MODEL_')) return 'replay.role.model';
  if (event.EVENT_TYPE?.startsWith('TOOL_')) return 'replay.role.tool';
  if (event.EVENT_TYPE?.startsWith('CHECK_')) return 'replay.role.check';
  if (event.EVENT_TYPE?.startsWith('HANDOFF')) return 'replay.role.handoff';
  if (event.EVENT_TYPE?.startsWith('AGENT_')) return 'replay.role.agent';
  return 'replay.role.control';
};

const findById = (items, id) => (id ? items?.find(item => item.ID === id) : undefined);

function Evidence({ event, run, nodeDetail }) {
  const { t } = useI18n();
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
            {tool.TOOL_NAME} · {starting ? t('replay.started') : tool.STATUS}
          </Typography>
          {!starting && tool.RESULT && <Readout>{tool.RESULT}</Readout>}
          {!starting && tool.ERROR_MESSAGE && <Readout>{tool.ERROR_MESSAGE}</Readout>}
          {!starting && tool.RESOURCE_VERSION && (
            <TraceSection title={t('replay.resourceVersion')} dense>
              <Readout mono>{tool.RESOURCE_VERSION}</Readout>
            </TraceSection>
          )}
        </Box>
      )}
      {model && (
        <Box>
          <Typography variant="subtitle2">
            {model.MODEL_NAME} · {starting ? t('replay.started') : model.STATUS}
          </Typography>
          {!starting && (
            <Typography variant="body2">
              {t('replay.tokens', {
                input: model.INPUT_TOKENS ?? t('common.unknown'),
                output: model.OUTPUT_TOKENS ?? t('common.unknown')
              })}
            </Typography>
          )}
          {!starting && model.ERROR_MESSAGE && <Readout>{model.ERROR_MESSAGE}</Readout>}
        </Box>
      )}
      {check && (
        <Box>
          <Typography variant="subtitle2">
            {check.checkId} · {starting ? t('replay.checkStarted') : check.status}
          </Typography>
          {!starting && check.summary && <Readout>{check.summary}</Readout>}
          {!starting && check.evidence && <Readout>{check.evidence}</Readout>}
        </Box>
      )}
      {handoffs.map(row => (
        <Box key={`${row.receiverAttemptId}:${row.dependencyNodeId}`}>
          <Typography variant="subtitle2">
            {row.senderNodeLabel || row.dependencyNodeLabel || t('glassbox.sender')} →{' '}
            {row.receiverNodeLabel || t('glassbox.receiver')}
          </Typography>
          <Readout>{row.sourceResult || t('glassbox.noBody')}</Readout>
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
          <Typography variant="subtitle2">{t('replay.agentResult')}</Typography>
          <Readout>{agent.RESULT}</Readout>
        </Box>
      )}
      {!tool && !model && !check && handoffs.length === 0 && !agent?.RESULT && (
        <Typography variant="body2" color="text.secondary">
          {t('replay.noEvidence')}
        </Typography>
      )}
      <TraceSection title={t('replay.rawRecord')} subtitle={t('replay.rawRecordHint')}>
        <Readout mono maxHeight={220}>
          {JSON.stringify(
            [
              [t('replay.raw.event'), event],
              [t('replay.raw.tool'), tool],
              [t('replay.raw.model'), model],
              [t('replay.raw.check'), check],
              [t('replay.raw.agent'), agent]
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
  const { t, locale } = useI18n();
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
            {t('replay.loadingRun')}
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
            {filters.map(value => (
              <ToggleButton key={value} value={value}>
                {t(`replay.filter.${value}`)}
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
              {t('replay.previous')}
            </Button>
            <Button
              size="small"
              variant="outlined"
              startIcon={playing ? <PauseRounded /> : <PlayArrowRounded />}
              disabled={!playing && nextId == null}
              onClick={() => setPlaying(value => !value)}
            >
              {playing ? t('replay.pause') : t('replay.play')}
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
              {t('replay.next')}
            </Button>
          </Stack>
        </Paper>

        {!event ? (
          <Alert severity="info">
            {eventsLoading
              ? t('replay.loadingEvents')
              : scopedEvents.length
                ? t('replay.emptyCategory')
                : t('replay.emptyRun')}
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
                {t('replay.order')}
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
                      secondary={`#${item.RUN_SEQ ?? item.ID} · ${clock(item.CREATED_AT, locale)}`}
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
                    label={t(roleKey(event))}
                    sx={{ bgcolor: '#E8F0FE', color: '#1967D2' }}
                  />

                  <Typography variant="caption" color="text.secondary">
                    {clock(event.CREATED_AT, locale)} ·{' '}
                    {t('replay.eventNumber', { id: event.RUN_SEQ ?? '—' })}
                  </Typography>
                </Stack>
                <Typography sx={{ fontSize: 16, fontWeight: 500 }}>
                  {eventNames[event.EVENT_TYPE] || event.EVENT_TYPE}
                </Typography>
                <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>
                  {event.MESSAGE || t('replay.noMessage')}
                </Typography>
                {event.REASON_CODE && (
                  <Typography variant="caption" color="text.secondary">
                    {t('replay.reasonCode', { code: event.REASON_CODE })}
                  </Typography>
                )}
                {nodeId && (
                  <Button
                    size="small"
                    startIcon={<GpsFixedRounded />}
                    onClick={() => onLocate?.(nodeId, event.EVENT_TYPE)}
                    sx={{ alignSelf: 'flex-start' }}
                  >
                    {t('replay.locate')}
                  </Button>
                )}
                {error && (
                  <Alert severity="warning">{t('replay.relatedFailed', { message: error })}</Alert>
                )}
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
