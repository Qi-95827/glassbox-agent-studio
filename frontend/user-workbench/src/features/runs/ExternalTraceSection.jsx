import { useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogContent,
  DialogTitle,
  Divider,
  Drawer,
  IconButton,
  Stack,
  Typography
} from '@mui/material';
import CloseRounded from '@mui/icons-material/CloseRounded';
import { useQuery } from '@tanstack/react-query';
import { runsApiExtras } from '../../api/traceExport';
import { useI18n } from '../../i18n/I18nProvider';

/** 把外部 Span 的 ISO 时间换成毫秒，供只读甘特复用同一套窗口算法。 */
function toMillis(value) {
  if (!value) return null;
  const time = Date.parse(value);
  return Number.isFinite(time) ? time : null;
}

/** 只读甘特：外部 Trace 没有节点/工具语义，按 kind 直接铺色块，不做任何推断。 */
function ExternalGantt({ spans }) {
  const positioned = spans.filter(span => toMillis(span.startedAt) != null);
  const window = useMemo(() => {
    if (!positioned.length) return null;
    const from = Math.min(...positioned.map(span => toMillis(span.startedAt)));
    const to = Math.max(
      ...positioned.map(span => toMillis(span.endedAt) ?? toMillis(span.startedAt))
    );
    return { from, to: to === from ? from + 1 : to };
  }, [positioned]);
  if (window == null) return null;
  const total = window.to - window.from;
  return (
    <Box
      data-testid="external-gantt"
      sx={{ position: 'relative', height: 26, bgcolor: 'action.hover', borderRadius: 1, my: 1 }}
    >
      {positioned.map(span => {
        const start = toMillis(span.startedAt);
        const end = toMillis(span.endedAt) ?? start;
        const left = ((start - window.from) / total) * 100;
        const width = Math.max(((end - start) / total) * 100, 0.4);
        return (
          <Box
            key={span.id}
            title={`${span.kind} · ${span.name}`}
            sx={{
              position: 'absolute',
              left: `${left}%`,
              width: `${width}%`,
              top: 5,
              height: 16,
              minWidth: 3,
              borderRadius: 0.75,
              bgcolor:
                span.kind === 'MODEL' ? '#12A4A4' : span.kind === 'TOOL' ? '#E8710A' : '#1A73E8'
            }}
          />
        );
      })}
    </Box>
  );
}

/**
 * 外部 Agent 框架接入区：列出外部 Trace、给出接入指南、并提供只读透视。
 *
 * <p>外部 Trace 没有计划与工具授权，因此这里只读展示时间线与 Span 明细，不提供执行、恢复或分叉入口。
 */
export default function ExternalTraceSection() {
  const { t } = useI18n();
  const [guideOpen, setGuideOpen] = useState(false);
  const [openId, setOpenId] = useState(null);

  const list = useQuery({
    queryKey: ['external-traces'],
    queryFn: () => runsApiExtras.externalTraces()
  });
  const detail = useQuery({
    queryKey: ['external-trace', openId],
    queryFn: () => runsApiExtras.externalTrace(openId),
    enabled: Boolean(openId)
  });

  const runs = list.data ?? [];

  return (
    <Box data-testid="external-traces" sx={{ mb: 2 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.75 }}>
        <Typography variant="subtitle2">{t('externalTrace.title')}</Typography>
        <Chip size="small" label={t('externalTrace.count', { count: runs.length })} />
        <Box sx={{ flex: 1 }} />
        <Button
          size="small"
          variant="outlined"
          data-testid="external-guide-open"
          onClick={() => setGuideOpen(true)}
        >
          {t('externalTrace.guide')}
        </Button>
      </Stack>

      {list.error && <Alert severity="error">{list.error.message}</Alert>}
      {!list.isLoading && !runs.length && (
        <Alert severity="info" data-testid="external-empty">
          {t('externalTrace.empty')}
        </Alert>
      )}

      <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
        {runs.map(run => (
          <Box
            key={run.id}
            data-testid={`external-run-${run.id}`}
            onClick={() => setOpenId(run.id)}
            sx={{
              border: '1px solid',
              borderColor: 'divider',
              borderRadius: 2,
              p: 1,
              minWidth: 220,
              cursor: 'pointer'
            }}
          >
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {run.name}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {run.source} · {t('externalTrace.spans', { count: run.spanCount })}
            </Typography>
          </Box>
        ))}
      </Stack>

      <Drawer
        anchor="right"
        open={guideOpen}
        onClose={() => setGuideOpen(false)}
        slotProps={{ paper: { sx: { width: '100%', maxWidth: 520 } } }}
      >
        <Box sx={{ p: 2 }} data-testid="external-guide">
          <Stack direction="row" sx={{ alignItems: 'center', mb: 1 }}>
            <Typography variant="subtitle1" sx={{ flex: 1 }}>
              {t('externalTrace.guideTitle')}
            </Typography>
            <IconButton size="small" onClick={() => setGuideOpen(false)}>
              <CloseRounded fontSize="small" />
            </IconButton>
          </Stack>
          <Alert severity="info" sx={{ mb: 1 }}>
            {t('externalTrace.guideBoundary')}
          </Alert>
          <Typography variant="caption" color="text.secondary">
            {t('externalTrace.guideStep1')}
          </Typography>
          <Box
            component="pre"
            sx={{ bgcolor: 'action.hover', p: 1, borderRadius: 1, fontSize: 12, overflow: 'auto' }}
          >
            {`curl -X POST http://127.0.0.1:8080/api/external-traces/runs \\
  -H 'Content-Type: application/json' \\
  -d '{"name":"my-agent-run","source":"python"}'`}
          </Box>
          <Divider sx={{ my: 1 }} />
          <Typography variant="caption" color="text.secondary">
            {t('externalTrace.guideStep2')}
          </Typography>
          <Box
            component="pre"
            sx={{ bgcolor: 'action.hover', p: 1, borderRadius: 1, fontSize: 12, overflow: 'auto' }}
          >
            {`curl -X POST http://127.0.0.1:8080/api/external-traces/runs/<RUN_ID>/spans \\
  -H 'Content-Type: application/json' \\
  -d '[{"kind":"NODE","name":"plan","nodeKey":"plan","status":"COMPLETED",
        "startedAt":"2026-11-26T09:00:00Z","endedAt":"2026-11-26T09:00:01Z",
        "attributes":{"step":1}}]'`}
          </Box>
          <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
            {t('externalTrace.guideLimits')}
          </Typography>
        </Box>
      </Drawer>

      <Dialog open={Boolean(openId)} onClose={() => setOpenId(null)} maxWidth="md" fullWidth>
        <DialogTitle>{detail.data?.run?.name ?? t('externalTrace.readonly')}</DialogTitle>
        <DialogContent data-testid="external-viewer">
          {detail.error && <Alert severity="error">{detail.error.message}</Alert>}
          {detail.data && (
            <>
              <Stack direction="row" spacing={1} sx={{ mb: 0.5 }}>
                <Chip size="small" label={detail.data.run.source} />
                <Chip
                  size="small"
                  label={t('externalTrace.spans', { count: detail.data.spans.length })}
                />
                <Chip size="small" color="default" label={t('externalTrace.readonly')} />
              </Stack>
              <ExternalGantt spans={detail.data.spans} />
              <Stack spacing={0.5}>
                {detail.data.spans.map(span => (
                  <Stack key={span.id} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    <Typography variant="caption" sx={{ width: 82, color: 'text.secondary' }}>
                      {span.kind}
                    </Typography>
                    <Typography variant="body2" sx={{ flex: 1 }}>
                      {span.name}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {span.status ?? '—'}
                    </Typography>
                  </Stack>
                ))}
              </Stack>
              {(detail.data.limitations ?? []).map(item => (
                <Typography
                  key={item}
                  variant="caption"
                  color="text.secondary"
                  sx={{ display: 'block', mt: 0.5 }}
                >
                  · {item}
                </Typography>
              ))}
            </>
          )}
        </DialogContent>
      </Dialog>
    </Box>
  );
}
