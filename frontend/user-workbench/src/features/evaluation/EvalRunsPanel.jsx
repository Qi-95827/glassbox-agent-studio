import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardActionArea,
  Chip,
  CircularProgress,
  Divider,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography
} from '@mui/material';
import AddRounded from '@mui/icons-material/AddRounded';
import RefreshRounded from '@mui/icons-material/RefreshRounded';
import StopCircleOutlined from '@mui/icons-material/StopCircleOutlined';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { evalLabApi } from '../../api/evalLab';
import { useEvalLabRuns, useEvalLabRun } from '../../hooks/useEvalLab';
import { useToast } from '../../components/ToastProvider';
import StatusChip from '../../components/StatusChip';
import EmptyState from '../../components/EmptyState';
import { useI18n } from '../../i18n/I18nProvider';
import { dateTime, duration, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import EvalRunDialog from './EvalRunDialog';
import EvalRadarChart from './EvalRadarChart';

const SOURCE_KEY = {
  MINI: 'evalLab.source.MINI',
  HUB: 'evalLab.source.HUB',
  CUSTOM: 'evalLab.source.CUSTOM'
};

function statusLabel(t, status) {
  return t(`evalLab.status.${status}`);
}

function Kpi({ label, value, hint }) {
  return (
    <Box
      sx={{
        p: 2,
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 3,
        bgcolor: '#fff',
        minWidth: 0
      }}
    >
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography sx={{ fontSize: 24, fontWeight: 500, lineHeight: 1.4 }}>{value}</Typography>
      {hint && (
        <Typography variant="caption" color="text.secondary">
          {hint}
        </Typography>
      )}
    </Box>
  );
}

function percent(value) {
  const number = Number(value);
  return Number.isFinite(number) ? `${(number * 100).toFixed(1)}%` : '—';
}

/** 评测批次列表 + 单批次 KPI 与逐题真实结果。 */
export default function EvalRunsPanel({ meta, onOpenCase, initialLaunchPack, onLaunchConsumed }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const runs = useEvalLabRuns();
  const [selectedId, setSelectedId] = useState('');
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogInit, setDialogInit] = useState({ source: 'MINI', key: 'mini' });
  const list = runs.data ?? [];
  const activeId = selectedId || list[0]?.id || '';
  const detail = useEvalLabRun(activeId);
  const run = detail.data?.run;
  const cases = detail.data?.cases ?? [];
  const overall = run?.summary?.overall;

  useEffect(() => {
    if (!initialLaunchPack?.source) return;
    setDialogInit({ source: initialLaunchPack.source, key: initialLaunchPack.key });
    setDialogOpen(true);
    onLaunchConsumed?.();
  }, [initialLaunchPack, onLaunchConsumed]);

  const openDialog = () => {
    setDialogInit({ source: 'MINI', key: 'mini' });
    setDialogOpen(true);
  };

  const cancel = async () => {
    try {
      await evalLabApi.cancel(activeId);
      await queryClient.invalidateQueries({ queryKey: ['eval-lab-run', activeId] });
      toast.success(t('evalLab.cancelled'));
    } catch (error) {
      toast.error(error.message);
    }
  };

  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '320px 1fr' }, gap: 2.5 }}>
      <Box>
        <Stack direction="row" spacing={1} sx={{ mb: 1.5 }}>
          <Button variant="contained" startIcon={<AddRounded />} onClick={openDialog}>
            {t('evalLab.newRun')}
          </Button>
          <Button
            onClick={() => queryClient.invalidateQueries({ queryKey: ['eval-lab-runs'] })}
            startIcon={<RefreshRounded />}
            sx={{ color: googleTokens.textSecondary }}
          >
            {t('evalLab.refresh')}
          </Button>
        </Stack>
        {runs.isLoading && <CircularProgress size={22} />}
        {runs.error && (
          <Alert severity="error">{t('evalLab.error.load', { message: runs.error.message })}</Alert>
        )}
        {!runs.isLoading && !list.length && (
          <EmptyState title={t('evalLab.emptyTitle')} description={t('evalLab.emptyDescription')} />
        )}
        <Stack spacing={1}>
          {list.map(item => {
            const selected = item.id === activeId;
            const summary = item.summary?.overall;
            return (
              <Card
                key={item.id}
                variant="outlined"
                sx={{
                  borderRadius: 3,
                  borderColor: selected ? googleTokens.primary : googleTokens.outline,
                  bgcolor: selected ? googleTokens.primarySoft : '#fff'
                }}
              >
                <CardActionArea onClick={() => setSelectedId(item.id)} sx={{ p: 1.5 }}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.5 }}>
                    <StatusChip value={item.status} label={statusLabel(t, item.status)} />
                    <Typography sx={{ fontSize: 13, fontWeight: 600 }}>
                      {item.config?.datasetName
                        ? item.config.datasetName + ' · #' + shortId(item.id)
                        : '#' + shortId(item.id)}
                    </Typography>
                  </Stack>
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                    {t('evalLab.run.meta', {
                      source: t(SOURCE_KEY[item.sourceKind] ?? item.sourceKind),
                      categories: item.categories?.length ?? 0,
                      cases: item.config?.plannedAttempts ?? summary?.total ?? 0,
                      time: dateTime(item.createdAt, locale)
                    })}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t('evalLab.run.counts', {
                      cases: summary?.total ?? 0,
                      passed: summary?.passed ?? 0
                    })}
                  </Typography>
                </CardActionArea>
              </Card>
            );
          })}
        </Stack>
      </Box>

      <Box sx={{ minWidth: 0 }}>
        {!run && (
          <EmptyState title={t('evalLab.emptyTitle')} description={t('evalLab.emptyDescription')} />
        )}
        {run && (
          <Stack spacing={2}>
            <Stack
              direction="row"
              spacing={1.5}
              sx={{ alignItems: 'center', justifyContent: 'space-between' }}
            >
              <Box>
                <Typography sx={{ fontSize: 18, fontWeight: 500 }}>
                  {run.config?.datasetName ??
                    '#' + shortId(run.id) + ' · ' + t(SOURCE_KEY[run.sourceKind] ?? run.sourceKind)}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {dateTime(run.createdAt, locale)} · {run.locale}
                </Typography>
              </Box>
              {['QUEUED', 'RUNNING'].includes(run.status) && (
                <Button color="inherit" startIcon={<StopCircleOutlined />} onClick={cancel}>
                  {t('evalLab.cancel')}
                </Button>
              )}
            </Stack>
            {run.errorMessage && <Alert severity="error">{run.errorMessage}</Alert>}
            {run.summary?.budgetStopped && (
              <Alert severity="warning">{t('evalLab.kpi.budgetStopped')}</Alert>
            )}
            <Box
              sx={{
                display: 'grid',
                gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(4, 1fr)' },
                gap: 1.5
              }}
            >
              <Kpi
                label={t('evalLab.kpi.passRate')}
                value={overall ? percent(overall.passRate) : '—'}
              />
              <Kpi
                label={t('evalLab.kpi.score')}
                value={overall ? `${Number(overall.score).toFixed(1)}` : '—'}
              />
              <Kpi
                label={t('evalLab.kpi.cost')}
                value={
                  overall &&
                  !(
                    run.sourceKind === 'HUB' &&
                    run.config?.modelConnectionId &&
                    run.summary?.knownModelCosts < overall.total
                  )
                    ? Number(overall.totalCostUsd).toFixed(4)
                    : '—'
                }
                hint={
                  t('evalLab.kpi.judgeCost') +
                  ' ' +
                  Number(run.summary?.judgeCostUsd ?? 0).toFixed(4)
                }
              />
              <Kpi label={t('evalLab.kpi.duration')} value={duration(run.totalDurationMs)} />
            </Box>

            <Divider />
            <Box
              sx={{
                display: 'grid',
                gridTemplateColumns: { xs: '1fr', md: '380px 1fr' },
                gap: 2,
                alignItems: 'start'
              }}
            >
              <Box
                sx={{
                  p: 2,
                  border: `1px solid ${googleTokens.outline}`,
                  borderRadius: 3,
                  bgcolor: '#fff'
                }}
              >
                <Typography sx={{ fontSize: 13, fontWeight: 600 }}>
                  {t('evalLab.radar.title')}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {t('evalLab.radar.subtitle')}
                </Typography>
                <EvalRadarChart
                  categories={(meta?.categories ?? []).map(category => ({
                    code: category.code,
                    label: locale === 'en-US' ? category.labelEn : category.labelZh
                  }))}
                  series={[
                    {
                      key: run.id,
                      label: `#${shortId(run.id)}`,
                      color: googleTokens.primary,
                      values: Object.fromEntries(
                        Object.entries(run.summary?.categories ?? {})
                          .filter(([, value]) => value?.total > 0)
                          .map(([code, value]) => [code, Number(value.score)])
                      ),
                      sampleCounts: Object.fromEntries(
                        Object.entries(run.summary?.categories ?? {}).map(([code, value]) => [
                          code,
                          value?.total ?? 0
                        ])
                      )
                    }
                  ]}
                  ariaLabel={t('evalLab.radar.title')}
                />
              </Box>
              <Box sx={{ minWidth: 0 }}>
                <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>
                  {t('evalLab.kpi.passRate')}
                </Typography>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>ID</TableCell>
                      <TableCell>{t('evalLab.kpi.passRate')}</TableCell>
                      <TableCell>{t('evalLab.kpi.score')}</TableCell>
                      <TableCell>Pass^k</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {(meta?.categories ?? []).map(category => {
                      const code = category.code;
                      const value = run.summary?.categories?.[code];
                      const measured = value?.total > 0;
                      return (
                        <TableRow key={code}>
                          <TableCell sx={{ fontSize: 12 }}>
                            {locale === 'en-US' ? category.labelEn : category.labelZh}
                          </TableCell>
                          <TableCell sx={{ fontSize: 12 }}>
                            {measured ? percent(value.passRate) : '—'}
                          </TableCell>
                          <TableCell sx={{ fontSize: 12 }}>
                            {measured ? Number(value.score).toFixed(1) : '—'}
                          </TableCell>
                          <TableCell sx={{ fontSize: 12 }}>
                            {measured ? percent(value.passHatK) : '—'}
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </Box>
            </Box>

            <Divider />
            <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
              {t('evalLab.case.title')}
            </Typography>
            <Box sx={{ overflowX: 'auto' }}>
              <Table size="small" sx={{ minWidth: 720 }}>
                <TableHead>
                  <TableRow>
                    <TableCell>ID</TableCell>
                    <TableCell>{t('common.name')}</TableCell>
                    <TableCell>{t('evalLab.case.repeat', { index: 'K' })}</TableCell>
                    <TableCell>{t('evalLab.kpi.score')}</TableCell>
                    <TableCell>{t('common.time')}</TableCell>
                    <TableCell align="right">{t('common.actions')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {cases.map(item => (
                    <TableRow key={item.id} hover>
                      <TableCell>
                        <Chip size="small" label={item.category} variant="outlined" />
                      </TableCell>
                      <TableCell>
                        <Typography sx={{ fontSize: 12.5 }}>
                          {locale === 'en-US' ? item.titleEn : item.titleZh}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          {item.sampleId}
                        </Typography>
                      </TableCell>
                      <TableCell>{item.repeatIndex}</TableCell>
                      <TableCell>
                        <Stack direction="row" spacing={0.8} sx={{ alignItems: 'center' }}>
                          <StatusChip
                            value={item.passed ? 'COMPLETED' : 'FAILED'}
                            label={
                              item.passed ? t('evalLab.case.passed') : t('evalLab.case.failed')
                            }
                          />
                          <Typography variant="caption">
                            {t('evalLab.case.score', {
                              score: (item.codeScore * 100).toFixed(0) + '%'
                            })}
                          </Typography>
                        </Stack>
                      </TableCell>
                      <TableCell>{duration(item.durationMs)}</TableCell>
                      <TableCell align="right">
                        <Stack direction="row" spacing={0.5} sx={{ justifyContent: 'flex-end' }}>
                          <Button size="small" onClick={() => onOpenCase?.(item)}>
                            {t('evalLab.case.viewDetail')}
                          </Button>
                          {item.linkedRunId && (
                            <Button
                              size="small"
                              onClick={() => navigate(`/runs/${item.linkedRunId}`)}
                            >
                              {t('evalLab.case.openRun')}
                            </Button>
                          )}
                        </Stack>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Box>
          </Stack>
        )}
      </Box>

      <EvalRunDialog
        open={dialogOpen}
        meta={meta}
        initialSource={dialogInit.source}
        initialSourceKey={dialogInit.key}
        onClose={() => setDialogOpen(false)}
        onCreated={id => setSelectedId(id)}
      />
    </Box>
  );
}
