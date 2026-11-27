import { useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography
} from '@mui/material';
import CompareArrowsRounded from '@mui/icons-material/CompareArrowsRounded';
import { evalLabApi } from '../../api/evalLab';
import { useEvalLabRuns } from '../../hooks/useEvalLab';
import { useToast } from '../../components/ToastProvider';
import EmptyState from '../../components/EmptyState';
import { useI18n } from '../../i18n/I18nProvider';
import { shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import EvalRadarChart from './EvalRadarChart';

const STATUS_COLOR = {
  IMPROVED: 'success',
  REGRESSED: 'error',
  UNCHANGED: 'default'
};

const METRIC_LABELS = {
  totalTokens: 'evalLab.compare.totalTokens',
  totalCostUsd: 'evalLab.compare.totalCost',
  avgPreparedInput: 'evalLab.compare.avgPreparedInput',
  avgActualInput: 'evalLab.compare.avgActualInput',
  passRate: 'evalLab.compare.passRate',
  safetyBlockRate: 'evalLab.compare.safetyBlockRate',
  score: 'evalLab.kpi.score',
  avgNeedleRetention: 'evalLab.compare.retention',
  avgRecallAtK: 'evalLab.compare.recall',
  avgCostUsd: 'evalLab.compare.cost',
  p95DurationMs: 'evalLab.compare.p95'
};

function deltaColor(value) {
  if (value > 0) return '#188038';
  if (value < 0) return '#B3261E';
  return googleTokens.textSecondary;
}

/** A/B 消融对照：配置差异 + 双图层雷达 + 指标 Δ + 逐题 Diff。 */
export default function EvalAbComparePanel({ meta }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const runs = useEvalLabRuns();
  const completed = useMemo(
    () =>
      (runs.data ?? []).filter(run => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(run.status)),
    [runs.data]
  );
  const [baseline, setBaseline] = useState('');
  const [candidate, setCandidate] = useState('');
  const [comparison, setComparison] = useState(null);
  const [busy, setBusy] = useState(false);

  const categories = useMemo(
    () =>
      (meta?.categories ?? []).map(item => ({
        code: item.code,
        label: locale === 'en-US' ? item.labelEn : item.labelZh
      })),
    [meta, locale]
  );

  const radarSeries = useMemo(() => {
    if (!comparison) return [];
    const categoryRows = comparison.categoryComparison ?? [];
    const values = (score, count) =>
      Object.fromEntries(
        categoryRows.filter(row => row[count] > 0).map(row => [row.category, row[score]])
      );
    const counts = seriesName =>
      Object.fromEntries(categoryRows.map(row => [row.category, row[seriesName]]));
    return [
      {
        key: 'baseline',
        label: `A · ${shortId(comparison.baseline?.id)}`,
        color: '#1A73E8',
        values: values('baselineScore', 'baselineSampleCount'),
        sampleCounts: counts('baselineSampleCount')
      },
      {
        key: 'candidate',
        label: `B · ${shortId(comparison.candidate?.id)}`,
        color: '#B3261E',
        values: values('candidateScore', 'candidateSampleCount'),
        sampleCounts: counts('candidateSampleCount')
      }
    ];
  }, [comparison]);

  const runCompare = async () => {
    if (!baseline || !candidate || baseline === candidate) {
      toast.error(t('evalLab.compare.description'));
      return;
    }
    setBusy(true);
    try {
      setComparison(await evalLabApi.compare(baseline, candidate));
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack spacing={2.5}>
      <Box>
        <Typography sx={{ fontSize: 16, fontWeight: 600 }}>{t('evalLab.compare.title')}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          {t('evalLab.compare.description')}
        </Typography>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={1.5}
          sx={{ alignItems: { sm: 'center' } }}
        >
          <FormControl size="small" sx={{ minWidth: 260 }}>
            <InputLabel>{t('evalLab.compare.baseline')}</InputLabel>
            <Select
              label={t('evalLab.compare.baseline')}
              value={baseline}
              onChange={event => setBaseline(event.target.value)}
            >
              {completed.map(run => (
                <MenuItem key={run.id} value={run.id}>
                  #{shortId(run.id)} · {run.sourceKind} · {run.status}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          <CompareArrowsRounded sx={{ color: googleTokens.textSecondary }} />
          <FormControl size="small" sx={{ minWidth: 260 }}>
            <InputLabel>{t('evalLab.compare.candidate')}</InputLabel>
            <Select
              label={t('evalLab.compare.candidate')}
              value={candidate}
              onChange={event => setCandidate(event.target.value)}
            >
              {completed.map(run => (
                <MenuItem key={run.id} value={run.id}>
                  #{shortId(run.id)} · {run.sourceKind} · {run.status}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          <Button
            variant="contained"
            onClick={runCompare}
            disabled={busy || !baseline || !candidate || baseline === candidate}
            startIcon={busy ? <CircularProgress size={15} /> : <CompareArrowsRounded />}
          >
            {t('evalLab.compare.action')}
          </Button>
        </Stack>
        {!completed.length && (
          <EmptyState title={t('evalLab.emptyTitle')} description={t('evalLab.emptyDescription')} />
        )}
      </Box>

      {comparison && (
        <>
          {!comparison.comparable && (
            <Alert severity="warning">{t('evalLab.compare.datasetMismatch')}</Alert>
          )}
          {(comparison.attributionWarnings?.length > 0 ||
            comparison.fullyAttributable === false) && (
            <Alert severity="warning" data-testid="compare-attribution-warning">
              <Typography sx={{ fontSize: 12.5, fontWeight: 600 }}>
                {t('evalLab.compare.attributionWarningTitle')}
              </Typography>
              {(comparison.attributionWarnings ?? []).map((note, idx) => (
                <Typography key={idx} variant="caption" sx={{ display: 'block', mt: 0.35 }}>
                  {note}
                </Typography>
              ))}
            </Alert>
          )}
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
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
              <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>
                {t('evalLab.radar.title')}
              </Typography>
              <EvalRadarChart
                categories={categories}
                series={radarSeries}
                ariaLabel={t('evalLab.radar.title')}
              />
            </Box>
            <Box
              sx={{
                p: 2,
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 3,
                bgcolor: '#fff'
              }}
            >
              <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>
                {t('evalLab.compare.metricDiff')}
              </Typography>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>{t('common.name')}</TableCell>
                    <TableCell>A</TableCell>
                    <TableCell>B</TableCell>
                    <TableCell>Δ</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {(comparison.metricDeltas ?? []).map(row => (
                    <TableRow key={row.metric}>
                      <TableCell sx={{ fontSize: 12 }}>
                        {METRIC_LABELS[row.metric] ? t(METRIC_LABELS[row.metric]) : row.metric}
                      </TableCell>
                      <TableCell sx={{ fontSize: 12 }}>
                        {row.baseline == null ? '—' : Number(row.baseline).toFixed(4)}
                      </TableCell>
                      <TableCell sx={{ fontSize: 12 }}>
                        {row.candidate == null ? '—' : Number(row.candidate).toFixed(4)}
                      </TableCell>
                      <TableCell
                        sx={{ fontSize: 12, color: deltaColor(row.delta), fontWeight: 600 }}
                      >
                        {row.delta == null
                          ? '—'
                          : (row.delta > 0 ? '+' : '') + Number(row.delta).toFixed(4)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Box>
          </Box>

          <Box sx={{ overflowX: 'auto' }}>
            <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>
              {t('evalLab.compare.categoryDiff')}
            </Typography>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t('common.name')}</TableCell>
                  <TableCell>A</TableCell>
                  <TableCell>B</TableCell>
                  <TableCell>Δ</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {categories.map(category => {
                  const row = (comparison.categoryComparison ?? []).find(
                    item => item.category === category.code
                  );
                  const bothMeasured =
                    row?.baselineSampleCount > 0 && row?.candidateSampleCount > 0;
                  return (
                    <TableRow key={category.code}>
                      <TableCell>{category.label}</TableCell>
                      <TableCell>
                        {row?.baselineSampleCount > 0 ? row.baselineScore.toFixed(1) : '—'}
                      </TableCell>
                      <TableCell>
                        {row?.candidateSampleCount > 0 ? row.candidateScore.toFixed(1) : '—'}
                      </TableCell>
                      <TableCell sx={{ color: deltaColor(row?.delta) }}>
                        {bothMeasured && row.delta != null
                          ? (row.delta > 0 ? '+' : '') + row.delta.toFixed(1)
                          : '—'}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </Box>
          <Box>
            <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>
              {t('evalLab.compare.configDiff')}
            </Typography>
            <Stack direction="row" spacing={0.8} sx={{ flexWrap: 'wrap', gap: 0.8 }}>
              {(comparison.configDiff ?? []).length === 0 && (
                <Typography variant="caption" color="text.secondary">
                  {t('evalLab.compare.unchanged')}
                </Typography>
              )}
              {(comparison.configDiff ?? []).map(row => {
                const unapplied = row.attributable === false;
                const appliedLabel = value =>
                  value === true
                    ? t('evalLab.compare.appliedYes')
                    : value === false
                      ? t('evalLab.compare.appliedNo')
                      : t('evalLab.compare.appliedUnknown');
                const detail =
                  row.note ||
                  t('evalLab.compare.appliedSummary', {
                    baseline: String(row.baseline ?? '—'),
                    candidate: String(row.candidate ?? '—'),
                    baseApplied: appliedLabel(row.baselineApplied),
                    candApplied: appliedLabel(row.candidateApplied)
                  });
                const label = `${row.field}: ${String(row.baseline ?? '—')} → ${String(row.candidate ?? '—')}${
                  unapplied ? ` (${t('evalLab.compare.notAppliedBadge')})` : ''
                }`;
                return (
                  <Tooltip key={row.field} title={detail}>
                    <Chip
                      data-testid={`config-diff-${row.field}`}
                      data-attributable={unapplied ? 'false' : 'true'}
                      size="small"
                      variant={unapplied ? 'filled' : 'outlined'}
                      color={unapplied ? 'warning' : 'default'}
                      label={label}
                    />
                  </Tooltip>
                );
              })}
            </Stack>
          </Box>

          <Box>
            <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>
              {t('evalLab.compare.caseDiff')}
            </Typography>
            <Box sx={{ overflowX: 'auto' }}>
              <Table size="small" sx={{ minWidth: 640 }}>
                <TableHead>
                  <TableRow>
                    <TableCell>{t('common.name')}</TableCell>
                    <TableCell>{t('evalLab.compare.baseline')}</TableCell>
                    <TableCell>{t('evalLab.compare.candidate')}</TableCell>
                    <TableCell>Δ</TableCell>
                    <TableCell>{t('common.status')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {(comparison.caseDiff ?? []).map(row => (
                    <TableRow key={row.key}>
                      <TableCell>
                        <Typography sx={{ fontSize: 12 }}>
                          {locale === 'en-US' ? row.titleEn : row.titleZh}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          {row.category} · {row.sampleId}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        {row.baselineSampleCount === 0 ? '—' : Number(row.baselineScore).toFixed(1)}
                      </TableCell>
                      <TableCell>
                        {row.candidateSampleCount === 0
                          ? '—'
                          : Number(row.candidateScore).toFixed(1)}
                      </TableCell>
                      <TableCell sx={{ color: deltaColor(row.candidateScore - row.baselineScore) }}>
                        {row.candidateScore - row.baselineScore > 0 ? '+' : ''}
                        {(row.candidateScore - row.baselineScore).toFixed(1)}
                      </TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          color={STATUS_COLOR[row.status] ?? 'default'}
                          label={t(`evalLab.compare.${row.status.toLowerCase()}`)}
                        />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Box>
          </Box>
        </>
      )}
    </Stack>
  );
}
