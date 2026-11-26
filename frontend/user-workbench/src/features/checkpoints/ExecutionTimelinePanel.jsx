import { useMemo, useState } from 'react';
import { Alert, Box, Chip, Stack, Tooltip, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { runsApi } from '../../api/runs';
import { useI18n } from '../../i18n/I18nProvider';

/** 一条泳道：节点、尝试、模型、工具、6 号门审批、压缩标记与检查点各占一层。 */
const LANES = [
  { kind: 'NODE', labelKey: 'timeline.lane.node' },
  { kind: 'ATTEMPT', labelKey: 'timeline.lane.attempt' },
  { kind: 'MODEL', labelKey: 'timeline.lane.model' },
  { kind: 'TOOL', labelKey: 'timeline.lane.tool' },
  { kind: 'APPROVAL', labelKey: 'timeline.lane.approval' },
  { kind: 'COMPACTION', labelKey: 'timeline.lane.compaction' },
  { kind: 'CHECKPOINT', labelKey: 'timeline.lane.checkpoint' }
];

const KIND_COLOR = {
  NODE: '#1A73E8',
  ATTEMPT: '#7B3FE4',
  MODEL: '#12A4A4',
  TOOL: '#E8710A',
  APPROVAL: '#C5221F',
  COMPACTION: '#5F6368',
  CHECKPOINT: '#188038'
};

const STATUS_TONE = {
  RUNNING: '#12A4A4',
  PENDING: '#9AA0A6',
  PAUSED: '#F9AB00',
  COMPLETED: '#188038',
  SUCCEEDED: '#188038',
  FAILED: '#C5221F',
  CANCELLED: '#9AA0A6',
  LIMITED: '#E8710A',
  BLOCKED: '#C5221F',
  INTERRUPTED: '#E8710A'
};

/** 时间轴窗口：只按真实记录的起止时间算，缺起点的 Span 无法定位，也不参与拉伸。 */
export function timelineWindow(spans) {
  const positioned = spans.filter(span => Number.isFinite(span.startMs));
  if (!positioned.length) return null;
  const from = Math.min(...positioned.map(span => span.startMs));
  const to = Math.max(
    ...positioned.map(span => (Number.isFinite(span.endMs) ? span.endMs : span.startMs))
  );
  return { from, to: to === from ? from + 1 : to };
}

function width(span, window) {
  const start = Number.isFinite(span.startMs) ? span.startMs : null;
  if (start == null) return null;
  const end = Number.isFinite(span.endMs) ? Math.max(span.endMs, start) : start;
  const total = window.to - window.from;
  return {
    left: ((start - window.from) / total) * 100,
    width: Math.max(((end - start) / total) * 100, 0.35)
  };
}

/**
 * 执行时间轴火焰图：把一次运行的真实记录压成横向甘特。
 *
 * <p>只渲染后端确实返回的 Span：没有起止时间的 Span 落成零宽标记，而不是按相邻记录猜一个区间。
 * 点击任意色块会把对应节点交给上层，直接打开该节点的玻璃盒。
 */
export default function ExecutionTimelinePanel({ runId, onOpenNode }) {
  const { t } = useI18n();
  const [focus, setFocus] = useState(null);
  const timeline = useQuery({
    queryKey: ['run-timeline', runId],
    queryFn: () => runsApi.timeline(runId),
    enabled: Boolean(runId)
  });

  const spans = useMemo(() => timeline.data?.spans ?? [], [timeline.data]);
  const window = useMemo(() => timelineWindow(spans), [spans]);
  const lanes = useMemo(
    () =>
      LANES.map(lane => ({
        ...lane,
        spans: spans.filter(span => span.kind === lane.kind)
      })).filter(lane => lane.spans.length),
    [spans]
  );

  if (timeline.isLoading) return <Typography variant="body2">…</Typography>;
  if (timeline.error) return <Alert severity="error">{timeline.error.message}</Alert>;
  if (!spans.length)
    return (
      <Alert severity="info" data-testid="timeline-empty">
        {t('timeline.empty')}
      </Alert>
    );

  const summary = timeline.data.summary;

  return (
    <Box data-testid="execution-timeline">
      <Stack direction="row" spacing={1} sx={{ mb: 1, flexWrap: 'wrap', rowGap: 0.5 }}>
        <Chip size="small" label={t('timeline.summaryNodes', { count: summary.totalNodes })} />
        <Chip size="small" label={t('timeline.summaryModels', { count: summary.modelCalls })} />
        <Chip size="small" label={t('timeline.summaryTools', { count: summary.toolCalls })} />
        <Chip
          size="small"
          label={t('timeline.summaryTokens', {
            input: summary.inputTokens,
            output: summary.outputTokens
          })}
        />
        <Chip size="small" label={t('timeline.summaryElapsed', { ms: summary.elapsedMs })} />
      </Stack>

      {window == null && <Alert severity="info">{t('timeline.noClock')}</Alert>}

      {window != null && (
        <Box sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2, p: 1 }}>
          {lanes.map(lane => (
            <Stack
              key={lane.kind}
              direction="row"
              spacing={1}
              sx={{ alignItems: 'center', py: 0.4 }}
            >
              <Typography
                variant="caption"
                sx={{ width: 96, flexShrink: 0, color: 'text.secondary' }}
              >
                {t(lane.labelKey)}
              </Typography>
              <Box
                sx={{
                  position: 'relative',
                  flex: 1,
                  height: 18,
                  bgcolor: 'action.hover',
                  borderRadius: 1
                }}
              >
                {lane.spans.map(span => {
                  const box = width(span, window);
                  if (!box) return null;
                  const selected = focus === span.id;
                  return (
                    <Tooltip
                      key={span.id}
                      title={`${span.label ?? span.kind} · ${span.status ?? '—'}`}
                    >
                      <Box
                        role="button"
                        tabIndex={0}
                        data-testid={`timeline-span-${span.kind}`}
                        data-span-id={span.id}
                        onClick={() => {
                          setFocus(span.id);
                          if (span.nodeId && onOpenNode) onOpenNode(span.nodeId);
                        }}
                        onKeyDown={event => {
                          if (event.key !== 'Enter') return;
                          setFocus(span.id);
                          if (span.nodeId && onOpenNode) onOpenNode(span.nodeId);
                        }}
                        sx={{
                          position: 'absolute',
                          left: `${box.left}%`,
                          width: `${box.width}%`,
                          minWidth: 3,
                          top: 3,
                          height: 12,
                          borderRadius: 0.75,
                          cursor: span.nodeId ? 'pointer' : 'default',
                          bgcolor: STATUS_TONE[span.status] ?? KIND_COLOR[span.kind],
                          outline: selected ? '2px solid #1A73E8' : 'none',
                          opacity: span.nodeId ? 1 : 0.75
                        }}
                      />
                    </Tooltip>
                  );
                })}
              </Box>
            </Stack>
          ))}
        </Box>
      )}

      {focus && (
        <Typography variant="caption" color="text.secondary" sx={{ mt: 0.75, display: 'block' }}>
          {t('timeline.focused', { id: focus })}
        </Typography>
      )}

      {(timeline.data.limitations ?? []).map(item => (
        <Typography key={item} variant="caption" color="text.secondary" sx={{ display: 'block' }}>
          · {item}
        </Typography>
      ))}
    </Box>
  );
}
