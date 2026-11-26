import { useMemo, useState } from 'react';
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Chip,
  CircularProgress,
  FormControl,
  IconButton,
  InputLabel,
  LinearProgress,
  MenuItem,
  Select,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography
} from '@mui/material';
import RefreshRounded from '@mui/icons-material/RefreshRounded';
import SpeedRounded from '@mui/icons-material/SpeedRounded';
import WarningAmberRounded from '@mui/icons-material/WarningAmberRounded';
import TokenRounded from '@mui/icons-material/TokenRounded';
import VerifiedUserOutlined from '@mui/icons-material/VerifiedUserOutlined';
import InsightsRounded from '@mui/icons-material/InsightsRounded';
import FilterAltOffRounded from '@mui/icons-material/FilterAltOffRounded';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useMonitoringAlerts, useMonitoringDashboard } from '../../hooks/useEvaluations';
import { evaluationApi } from '../../api/evaluations';
import { statusLabel, toolNameLabel, modelLabel } from '../../constants/labels';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';
import { dateTime, duration, oneLine, shortId } from '../../utils/format';
import StatusChip from '../../components/StatusChip';
import EmptyState from '../../components/EmptyState';
import RunDiagnosticsDrawer from '../runs/RunDiagnosticsDrawer';
import { DonutChart, GaugeArc, TrendChart, CHART_COLORS } from './monitoring/MonitoringCharts';

const RANGES = ['24h', '7d', '30d', 'all'];
const STATUS_OPTIONS = ['', 'COMPLETED', 'FAILED', 'BLOCKED', 'RUNNING'];
const TREND_MODES = ['runs', 'duration', 'tokens'];
const DONUT_MODES = ['taskStatus', 'model', 'tool'];

function PanelCard({ title, subtitle, action, children, sx }) {
  return (
    <Box
      sx={{
        bgcolor: '#FFFFFF',
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 4,
        p: 2.25,
        minWidth: 0,
        ...sx
      }}
    >
      {(title || action) && (
        <Stack
          direction="row"
          spacing={1}
          sx={{ mb: subtitle ? 0.4 : 1.2, alignItems: 'center', justifyContent: 'space-between' }}
        >
          <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{title}</Typography>
          {action}
        </Stack>
      )}
      {subtitle && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1.2 }}>
          {subtitle}
        </Typography>
      )}
      {children}
    </Box>
  );
}

function MetricValue({ children, color }) {
  return (
    <Typography
      sx={{ fontSize: 26, fontWeight: 500, lineHeight: 1.35, color: color || googleTokens.text }}
    >
      {children}
    </Typography>
  );
}

function formatNumber(value, locale = 'zh-CN') {
  const number = Number(value);
  return Number.isFinite(number) ? number.toLocaleString(locale) : '0';
}

function rateText(value) {
  const number = Number(value);
  return Number.isFinite(number) ? `${number.toFixed(1)}%` : '0.0%';
}

/** 类 Grafana 的细粒度运行监控看板：KPI、时序、圆盘、明细表与快捷排查入口。 */
export default function MonitoringPanel() {
  const { t, locale } = useI18n();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [range, setRange] = useState('7d');
  const [statusFilter, setStatusFilter] = useState('');
  const [modelFilter, setModelFilter] = useState('');
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [trendMode, setTrendMode] = useState('runs');
  const [donutMode, setDonutMode] = useState('taskStatus');
  const [anomaliesOnly, setAnomaliesOnly] = useState(false);
  const [diagnosticsRunId, setDiagnosticsRunId] = useState(null);
  // 任务筛选以 URL 为唯一真相：刷新页面或分享链接都不丢筛选状态。
  const selectedTaskId = searchParams.get('taskId') || '';

  const selectTask = taskId => {
    const next = new URLSearchParams(searchParams);
    if (taskId) next.set('taskId', taskId);
    else next.delete('taskId');
    setSearchParams(next, { replace: true });
  };

  const dashboard = useMonitoringDashboard({
    range,
    status: statusFilter || undefined,
    model: modelFilter || undefined,
    taskId: selectedTaskId || undefined,
    autoRefresh
  });
  const alerts = useMonitoringAlerts(autoRefresh);
  const audit = useQuery({
    queryKey: ['audit', 20],
    queryFn: () => evaluationApi.audit(20)
  });
  const [selfCheck, setSelfCheck] = useState(null);
  const [checking, setChecking] = useState(false);

  const data = dashboard.data;
  const summary = data?.summary;
  const recentRuns = useMemo(() => data?.rankings?.recentRuns ?? [], [data]);
  const visibleRuns = useMemo(
    () => (anomaliesOnly ? recentRuns.filter(run => run.hasAnomaly) : recentRuns),
    [anomaliesOnly, recentRuns]
  );
  const modelOptions = data?.distributions?.modelBreakdown ?? [];
  const taskOptions = useMemo(() => data?.taskOptions ?? [], [data]);
  const selectedTaskOption = useMemo(
    () => taskOptions.find(option => option.taskId === selectedTaskId) ?? null,
    [selectedTaskId, taskOptions]
  );
  const focusTask = taskId => {
    selectTask(taskId);
    const top = typeof document === 'undefined' ? null : document.getElementById('monitoring-top');
    if (top?.scrollIntoView) top.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  const donutSlices = useMemo(() => {
    if (!data) return [];
    if (donutMode === 'taskStatus') {
      return (data.distributions.taskStatusBreakdown ?? []).map(slice => ({
        key: slice.status,
        label: statusLabel(slice.status, locale),
        value: slice.count
      }));
    }
    if (donutMode === 'model') {
      return (data.distributions.modelBreakdown ?? []).map(slice => ({
        key: slice.model,
        label: modelLabel(slice.model, undefined, locale),
        value: slice.calls
      }));
    }
    return (data.distributions.toolBreakdown ?? []).map(slice => ({
      key: slice.tool,
      label: toolNameLabel(slice.tool, locale),
      value: slice.calls
    }));
  }, [data, donutMode, locale]);

  const runSelfCheck = async () => {
    setChecking(true);
    try {
      setSelfCheck(await evaluationApi.selfCheck());
    } catch (error) {
      setSelfCheck({
        status: 'ERROR',
        checks: [{ name: t('monitoring.requestFailed'), status: 'FAILED', detail: error.message }]
      });
    } finally {
      setChecking(false);
    }
  };

  const openRunNode = (runId, nodeId, glassbox = 'result') => {
    if (!runId) return;
    navigate(
      nodeId
        ? `/runs/${runId}?nodeId=${encodeURIComponent(nodeId)}&glassbox=${glassbox}`
        : `/runs/${runId}`
    );
  };

  const refreshAll = () => {
    dashboard.refetch();
    alerts.refetch();
  };

  return (
    <Stack spacing={2} id="monitoring-top" data-testid="monitoring-panel">
      <Box
        sx={{
          bgcolor: '#FFFFFF',
          border: `1px solid ${googleTokens.outline}`,
          borderRadius: 4,
          p: 1.5,
          display: 'flex',
          flexWrap: 'wrap',
          gap: 1.25,
          alignItems: 'center',
          justifyContent: 'space-between'
        }}
      >
        <Stack
          direction="row"
          spacing={1}
          sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 1 }}
        >
          <ToggleButtonGroup
            exclusive
            size="small"
            value={range}
            onChange={(_, value) => value && setRange(value)}
            aria-label={t('monitoring.rangeAria')}
          >
            {RANGES.map(item => (
              <ToggleButton key={item} value={item}>
                {t(`monitoring.range.${item}`)}
              </ToggleButton>
            ))}
          </ToggleButtonGroup>

          <FormControl size="small" sx={{ minWidth: 128 }}>
            <InputLabel id="monitor-status-label">{t('common.status')}</InputLabel>
            <Select
              labelId="monitor-status-label"
              label={t('common.status')}
              value={statusFilter}
              onChange={event => setStatusFilter(event.target.value)}
            >
              {STATUS_OPTIONS.map(item => (
                <MenuItem key={item || 'all'} value={item}>
                  {item ? statusLabel(item, locale) : t('monitoring.allStatuses')}
                </MenuItem>
              ))}
            </Select>
          </FormControl>

          <FormControl size="small" sx={{ minWidth: 150 }}>
            <InputLabel id="monitor-model-label">{t('common.model')}</InputLabel>
            <Select
              labelId="monitor-model-label"
              label={t('common.model')}
              value={modelFilter}
              onChange={event => setModelFilter(event.target.value)}
            >
              <MenuItem value="">{t('monitoring.allModels')}</MenuItem>
              {modelOptions.map(item => (
                <MenuItem key={item.model} value={item.model}>
                  {modelLabel(item.model, undefined, locale)}
                </MenuItem>
              ))}
            </Select>
          </FormControl>

          <Autocomplete
            size="small"
            data-testid="monitoring-task-filter"
            sx={{ minWidth: 250, maxWidth: 340 }}
            options={taskOptions}
            value={selectedTaskOption}
            onChange={(_, option) => selectTask(option?.taskId ?? '')}
            getOptionLabel={option => option?.goal || option?.taskId || ''}
            isOptionEqualToValue={(option, value) => option.taskId === value.taskId}
            filterOptions={(options, state) => {
              const query = state.inputValue.trim().toLowerCase();
              if (!query) return options;
              return options.filter(option =>
                `${option.goal ?? ''} ${option.taskId}`.toLowerCase().includes(query)
              );
            }}
            renderOption={(props, option) => (
              <li {...props} key={option.taskId}>
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" noWrap>
                    {oneLine(option.goal || option.taskId, 44)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {statusLabel(option.latestStatus, locale)} ·{' '}
                    {t('monitoring.taskFilter.runCount', { count: option.runCount })}
                  </Typography>
                </Box>
              </li>
            )}
            renderInput={params => (
              <TextField
                {...params}
                label={t('monitoring.taskFilter.label')}
                placeholder={t('monitoring.taskFilter.placeholder')}
              />
            )}
          />
        </Stack>

        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
          <Stack direction="row" spacing={0.4} sx={{ alignItems: 'center' }}>
            <Typography variant="caption" color="text.secondary">
              {t('monitoring.autoRefresh')}
            </Typography>
            <Switch
              size="small"
              checked={autoRefresh}
              onChange={event => setAutoRefresh(event.target.checked)}
              inputProps={{ 'aria-label': t('monitoring.autoRefresh') }}
            />
          </Stack>
          <Tooltip title={t('monitoring.refreshNow')}>
            <span>
              <IconButton
                size="small"
                onClick={refreshAll}
                disabled={dashboard.isFetching}
                aria-label={t('monitoring.refreshNow')}
              >
                {dashboard.isFetching ? (
                  <CircularProgress size={16} />
                ) : (
                  <RefreshRounded fontSize="small" />
                )}
              </IconButton>
            </span>
          </Tooltip>
          <Typography variant="caption" color="text.secondary">
            {data?.generatedAt
              ? t('monitoring.updatedAt', { time: dateTime(data.generatedAt, locale) })
              : t('monitoring.waitingData')}
          </Typography>
        </Stack>
      </Box>

      {dashboard.isError && (
        <Alert severity="error">
          {t('monitoring.loadFailed', { message: dashboard.error.message })}
        </Alert>
      )}

      {selectedTaskId && (
        <Alert
          severity="info"
          data-testid="monitoring-focus-banner"
          icon={<InsightsRounded fontSize="small" />}
          action={
            <Button
              color="inherit"
              size="small"
              data-testid="monitoring-focus-clear"
              startIcon={<FilterAltOffRounded fontSize="small" />}
              onClick={() => selectTask('')}
              sx={{ whiteSpace: 'nowrap' }}
            >
              {t('monitoring.taskFilter.clear')}
            </Button>
          }
        >
          <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
            {t('monitoring.taskFilter.focusedTitle')}
          </Typography>
          <Typography variant="body2">
            {oneLine(data?.selectedTaskGoal || selectedTaskOption?.goal || selectedTaskId, 120)}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {t('monitoring.taskFilter.focusedMeta', {
              runs: summary?.totalRuns ?? 0,
              rounds: selectedTaskOption?.runCount ?? 0,
              status: selectedTaskOption?.latestStatus
                ? statusLabel(selectedTaskOption.latestStatus, locale)
                : t('common.unknown'),
              id: shortId(selectedTaskId)
            })}
          </Typography>
        </Alert>
      )}

      {dashboard.isLoading && !data && (
        <Stack
          direction="row"
          spacing={1}
          sx={{ py: 6, alignItems: 'center', justifyContent: 'center' }}
        >
          <CircularProgress size={20} />
          <Typography variant="body2" color="text.secondary">
            {t('monitoring.loading')}
          </Typography>
        </Stack>
      )}

      {data && summary?.totalRuns === 0 && (
        <PanelCard>
          <EmptyState
            title={t('monitoring.emptyTitle')}
            description={t('monitoring.emptyDescription')}
            action={{ label: t('monitoring.viewAllHistory'), onClick: () => setRange('all') }}
          />
        </PanelCard>
      )}

      {data && summary?.totalRuns > 0 && (
        <>
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: 'repeat(2, minmax(0,1fr))',
                md: 'repeat(3, minmax(0,1fr))',
                xl: 'repeat(5, minmax(0,1fr))'
              },
              gap: 1.5
            }}
          >
            <PanelCard title={t('monitoring.kpi.successTitle')}>
              <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                <GaugeArc value={summary.successRate} label={t('monitoring.successRate')} />
                <Stack spacing={0.3} sx={{ minWidth: 0 }}>
                  <Typography variant="caption" color="text.secondary">
                    {t('monitoring.kpi.completed', {
                      completed: formatNumber(summary.completedRuns, locale),
                      total: formatNumber(summary.totalRuns, locale)
                    })}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t('monitoring.kpi.deliveredFailed', {
                      delivered: formatNumber(summary.deliveredRuns, locale),
                      failed: formatNumber(summary.failedRuns, locale)
                    })}
                  </Typography>
                </Stack>
              </Stack>
            </PanelCard>

            <PanelCard title={t('monitoring.kpi.totalTitle')}>
              <MetricValue>{formatNumber(summary.totalRuns, locale)}</MetricValue>
              <Button
                size="small"
                variant="text"
                onClick={() => setAnomaliesOnly(true)}
                startIcon={<WarningAmberRounded sx={{ fontSize: 15 }} />}
                sx={{
                  mt: 0.5,
                  px: 0.5,
                  minHeight: 26,
                  fontSize: 12.5,
                  color: summary.anomalyRunCount > 0 ? googleTokens.errorFg : googleTokens.successFg
                }}
              >
                {t('monitoring.kpi.anomaly', {
                  count: formatNumber(summary.anomalyRunCount, locale)
                })}
              </Button>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                {t('monitoring.kpi.blockedFailed', {
                  blocked: formatNumber(summary.blockedRuns, locale),
                  failed: formatNumber(summary.failedRuns, locale)
                })}
              </Typography>
            </PanelCard>

            <PanelCard title={t('monitoring.kpi.durationTitle')}>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
                <SpeedRounded sx={{ fontSize: 18, color: googleTokens.infoFg, mt: 0.6 }} />
                <Box>
                  <MetricValue>{duration(summary.avgRunDurationMs)}</MetricValue>
                  <Typography variant="caption" color="text.secondary">
                    P95 {duration(summary.p95RunDurationMs)}
                  </Typography>
                </Box>
              </Stack>
            </PanelCard>

            <PanelCard title={t('monitoring.kpi.tokensTitle')}>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
                <TokenRounded sx={{ fontSize: 18, color: CHART_COLORS.purple, mt: 0.6 }} />
                <Box sx={{ minWidth: 0 }}>
                  <MetricValue>
                    {formatNumber(summary.totalInputTokens + summary.totalOutputTokens)}
                  </MetricValue>
                  <Typography variant="caption" color="text.secondary">
                    In {formatNumber(summary.totalInputTokens, locale)} · Out{' '}
                    {formatNumber(summary.totalOutputTokens, locale)}
                  </Typography>
                </Box>
              </Stack>
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ display: 'block', mt: 0.5 }}
              >
                {t('monitoring.kpi.modelCalls', {
                  count: formatNumber(summary.totalModelCalls, locale),
                  cost:
                    summary.estimatedCost == null
                      ? t('list.notRecorded')
                      : Number(summary.estimatedCost).toFixed(4)
                })}
              </Typography>
            </PanelCard>

            <PanelCard title={t('monitoring.kpi.gatewayTitle')}>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
                <VerifiedUserOutlined sx={{ fontSize: 18, color: CHART_COLORS.warning, mt: 0.6 }} />
                <Box>
                  <MetricValue
                    color={
                      summary.toolDenyCount > 0 ? googleTokens.errorFg : googleTokens.successFg
                    }
                  >
                    {formatNumber(summary.toolDenyCount)}
                  </MetricValue>
                  <Typography variant="caption" color="text.secondary">
                    {t('monitoring.kpi.denyRate', {
                      rate: rateText(summary.toolDenyRate),
                      calls: formatNumber(summary.totalToolCalls, locale)
                    })}
                  </Typography>
                </Box>
              </Stack>
            </PanelCard>
          </Box>

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', lg: 'minmax(0,1.65fr) minmax(300px,.95fr)' },
              gap: 2
            }}
          >
            <PanelCard
              title={t('monitoring.trendTitle')}
              subtitle={t('monitoring.trendSubtitle')}
              action={
                <ToggleButtonGroup
                  exclusive
                  size="small"
                  value={trendMode}
                  onChange={(_, value) => value && setTrendMode(value)}
                  aria-label={t('monitoring.trendTitle')}
                >
                  {TREND_MODES.map(item => (
                    <ToggleButton key={item} value={item}>
                      {t(`monitoring.trend.${item}`)}
                    </ToggleButton>
                  ))}
                </ToggleButtonGroup>
              }
            >
              <TrendChart points={data.timeSeries ?? []} metric={trendMode} />
            </PanelCard>

            <PanelCard
              title={t('monitoring.donutTitle')}
              subtitle={t('monitoring.donutSubtitle')}
              action={
                <ToggleButtonGroup
                  exclusive
                  size="small"
                  value={donutMode}
                  onChange={(_, value) => value && setDonutMode(value)}
                  aria-label={t('monitoring.donutTitle')}
                >
                  {DONUT_MODES.map(item => (
                    <ToggleButton key={item} value={item}>
                      {t(`monitoring.donut.${item}`)}
                    </ToggleButton>
                  ))}
                </ToggleButtonGroup>
              }
            >
              <DonutChart
                slices={donutSlices}
                totalLabel={
                  donutMode === 'taskStatus'
                    ? t('monitoring.donut.runs')
                    : donutMode === 'model'
                      ? t('monitoring.donut.calls')
                      : t('monitoring.donut.tools')
                }
                valueSuffix=""
              />
            </PanelCard>
          </Box>

          <PanelCard
            title={t('monitoring.detailTitle')}
            subtitle={t('monitoring.detailSubtitle')}
            action={
              <ToggleButtonGroup
                exclusive
                size="small"
                value={anomaliesOnly ? 'anomaly' : 'all'}
                onChange={(_, value) => value && setAnomaliesOnly(value === 'anomaly')}
                aria-label={t('monitoring.detailTitle')}
              >
                <ToggleButton value="all">{t('monitoring.all')}</ToggleButton>
                <ToggleButton value="anomaly">{t('monitoring.anomaliesOnly')}</ToggleButton>
              </ToggleButtonGroup>
            }
          >
            {visibleRuns.length === 0 ? (
              <EmptyState
                compact
                title={anomaliesOnly ? t('monitoring.emptyAnomalies') : t('monitoring.emptyRuns')}
                description={anomaliesOnly ? t('monitoring.emptyAnomaliesHint') : undefined}
              />
            ) : (
              <TableContainer sx={{ maxHeight: 520 }}>
                <Table stickyHeader size="small" sx={{ minWidth: 920 }}>
                  <TableHead>
                    <TableRow>
                      <TableCell>{t('monitoring.colTaskRun')}</TableCell>
                      <TableCell>{t('monitoring.colStatus')}</TableCell>
                      <TableCell>{t('common.model')}</TableCell>
                      <TableCell align="right">{t('list.colDuration')}</TableCell>
                      <TableCell align="right">Token (In / Out)</TableCell>
                      <TableCell align="right">{t('monitoring.colTools')}</TableCell>
                      <TableCell align="right">{t('common.actions')}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {visibleRuns.map(run => (
                      <TableRow key={run.runId} hover>
                        <TableCell sx={{ maxWidth: 260 }}>
                          <Typography
                            variant="body2"
                            sx={{
                              fontWeight: 500,
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap'
                            }}
                            title={run.taskTitle}
                          >
                            {run.hasAnomaly && (
                              <WarningAmberRounded
                                sx={{
                                  fontSize: 14,
                                  color: googleTokens.errorFg,
                                  mr: 0.5,
                                  verticalAlign: 'text-bottom'
                                }}
                              />
                            )}
                            {oneLine(run.taskTitle || t('common.unnamedTask'), 34)}
                          </Typography>
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{ fontFamily: googleTokens.mono }}
                          >
                            #{shortId(run.runId)}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Stack
                            direction="row"
                            spacing={0.5}
                            sx={{ flexWrap: 'wrap', rowGap: 0.5 }}
                          >
                            <StatusChip value={run.runStatus} />
                            <StatusChip value={run.taskStatus} />
                          </Stack>
                        </TableCell>
                        <TableCell>
                          <Typography variant="caption">
                            {run.model === 'mixed'
                              ? t('common.mixedModel')
                              : modelLabel(run.model, undefined, locale)}
                          </Typography>
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{ display: 'block' }}
                          >
                            {t('monitoring.nodeCount', { count: run.nodeCount })}
                          </Typography>
                        </TableCell>
                        <TableCell align="right">
                          <Typography variant="caption">{duration(run.durationMs)}</Typography>
                        </TableCell>
                        <TableCell align="right">
                          <Typography variant="caption" sx={{ fontFamily: googleTokens.mono }}>
                            {formatNumber(run.inputTokens)} / {formatNumber(run.outputTokens)}
                          </Typography>
                        </TableCell>
                        <TableCell align="right">
                          <Typography
                            variant="caption"
                            sx={{
                              fontFamily: googleTokens.mono,
                              color: run.toolDenies > 0 ? googleTokens.errorFg : 'inherit'
                            }}
                          >
                            {formatNumber(Math.max(0, run.toolCalls - run.toolDenies))} /{' '}
                            {formatNumber(run.toolDenies)}
                          </Typography>
                        </TableCell>
                        <TableCell align="right">
                          <Stack direction="row" spacing={0.5} sx={{ justifyContent: 'flex-end' }}>
                            <Button
                              size="small"
                              variant="text"
                              data-testid={`focus-task-${run.runId}`}
                              disabled={Boolean(run.taskId) && run.taskId === selectedTaskId}
                              onClick={() => focusTask(run.taskId)}
                              sx={{ minHeight: 28, fontSize: 12 }}
                            >
                              {t('monitoring.taskFilter.focus')}
                            </Button>
                            <Button
                              size="small"
                              variant="contained"
                              onClick={() => setDiagnosticsRunId(run.runId)}
                              sx={{ minHeight: 28, fontSize: 12 }}
                            >
                              {t('run.quickTriage')}
                            </Button>
                            <Button
                              size="small"
                              variant="text"
                              onClick={() => openRunNode(run.runId)}
                              sx={{ minHeight: 28, fontSize: 12 }}
                            >
                              {t('history.openRun')}
                            </Button>
                          </Stack>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </PanelCard>

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', lg: 'repeat(2, minmax(0,1fr))' },
              gap: 2
            }}
          >
            <PanelCard
              title="Top 慢节点"
              subtitle="按节点实际开始与结束时间排序；点击直接定位到该节点。"
            >
              {(data.rankings.slowestNodes ?? []).length === 0 ? (
                <Typography variant="body2" color="text.secondary">
                  暂无可计算的节点耗时。
                </Typography>
              ) : (
                <Stack spacing={1}>
                  {data.rankings.slowestNodes.map(item => {
                    const max = Math.max(
                      ...data.rankings.slowestNodes.map(node => Number(node.durationMs) || 0),
                      1
                    );
                    return (
                      <Box
                        key={`${item.runId}:${item.nodeId}`}
                        role="button"
                        tabIndex={0}
                        onClick={() => openRunNode(item.runId, item.nodeId, 'result')}
                        onKeyDown={event => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            openRunNode(item.runId, item.nodeId, 'result');
                          }
                        }}
                        sx={{
                          p: 1,
                          borderRadius: 2,
                          cursor: 'pointer',
                          '&:hover': { bgcolor: '#F8F9FA' }
                        }}
                      >
                        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.4 }}>
                          <Typography
                            variant="caption"
                            sx={{
                              flex: 1,
                              minWidth: 0,
                              fontWeight: 600,
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap'
                            }}
                          >
                            {item.nodeLabel || item.role || '未命名节点'}
                          </Typography>
                          <Typography
                            variant="caption"
                            sx={{ fontFamily: googleTokens.mono, color: googleTokens.errorFg }}
                          >
                            {duration(item.durationMs)}
                          </Typography>
                        </Stack>
                        <LinearProgress
                          variant="determinate"
                          value={Math.min(100, ((Number(item.durationMs) || 0) / max) * 100)}
                          sx={{
                            height: 5,
                            bgcolor: '#F1F3F4',
                            '& .MuiLinearProgress-bar': { bgcolor: CHART_COLORS.error }
                          }}
                        />
                        <Typography variant="caption" color="text.secondary">
                          #{shortId(item.runId)} ·{' '}
                          {item.model === 'mixed' ? '混合模型' : modelLabel(item.model)}
                        </Typography>
                      </Box>
                    );
                  })}
                </Stack>
              )}
            </PanelCard>

            <PanelCard
              title="ToolGateway 高频拦截安检门"
              subtitle="按 DENY 出现次数排序；点击样本可直接打开对应节点的工具安检门。"
              action={
                <Chip
                  size="small"
                  icon={<InsightsRounded sx={{ fontSize: 14 }} />}
                  label={`${data.distributions.gatewayDenyBreakdown?.length ?? 0} 类`}
                  sx={{ bgcolor: '#F1F3F4' }}
                />
              }
            >
              {(data.distributions.gatewayDenyBreakdown ?? []).length === 0 ? (
                <Typography variant="body2" color="text.secondary">
                  当前时间范围内没有工具安检拦截记录。
                </Typography>
              ) : (
                <Stack spacing={1}>
                  {data.distributions.gatewayDenyBreakdown.map(item => {
                    const max = Math.max(
                      ...data.distributions.gatewayDenyBreakdown.map(
                        slice => Number(slice.count) || 0
                      ),
                      1
                    );
                    return (
                      <Box
                        key={item.gate}
                        role="button"
                        tabIndex={0}
                        onClick={() => openRunNode(item.runId, item.nodeId, 'gateway')}
                        onKeyDown={event => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            openRunNode(item.runId, item.nodeId, 'gateway');
                          }
                        }}
                        sx={{
                          p: 1,
                          borderRadius: 2,
                          cursor: item.runId ? 'pointer' : 'default',
                          '&:hover': item.runId ? { bgcolor: '#F8F9FA' } : undefined
                        }}
                      >
                        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.4 }}>
                          <Typography
                            variant="caption"
                            sx={{
                              flex: 1,
                              minWidth: 0,
                              fontWeight: 600,
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap'
                            }}
                          >
                            {item.label || item.gate}
                          </Typography>
                          <Typography
                            variant="caption"
                            sx={{ fontFamily: googleTokens.mono, color: googleTokens.warningFg }}
                          >
                            {item.count} 次
                          </Typography>
                        </Stack>
                        <LinearProgress
                          variant="determinate"
                          value={Math.min(100, ((Number(item.count) || 0) / max) * 100)}
                          sx={{
                            height: 5,
                            bgcolor: '#F1F3F4',
                            '& .MuiLinearProgress-bar': { bgcolor: CHART_COLORS.warning }
                          }}
                        />
                        <Typography
                          variant="caption"
                          color="text.secondary"
                          sx={{ fontFamily: googleTokens.mono }}
                        >
                          {item.gate}
                          {item.runId ? ` · #${shortId(item.runId)}` : ''}
                        </Typography>
                      </Box>
                    );
                  })}
                </Stack>
              )}
            </PanelCard>
          </Box>
        </>
      )}

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', lg: 'repeat(2, minmax(0,1fr))' },
          gap: 2
        }}
      >
        <PanelCard title="告警" subtitle="每条告警都关联运行或受控动作；没有记录不代表系统健康。">
          <Stack spacing={1}>
            {(alerts.data ?? []).length === 0 && (
              <Typography variant="body2" color="text.secondary">
                暂无已记录告警。
              </Typography>
            )}
            {(alerts.data ?? []).slice(0, 5).map((alert, index) => (
              <Box
                key={`${alert.code}-${alert.targetId}-${index}`}
                sx={{ p: 1.1, borderRadius: 2.5, bgcolor: '#F8F9FA' }}
              >
                <Stack
                  direction="row"
                  spacing={0.75}
                  sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                >
                  <Chip
                    size="small"
                    label={alert.severity}
                    sx={{
                      bgcolor: alert.severity === 'CRITICAL' ? '#FCE8E6' : '#FEF7E0',
                      color: alert.severity === 'CRITICAL' ? '#C5221F' : '#B06000'
                    }}
                  />
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {alert.title}
                  </Typography>
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  {alert.detail}
                </Typography>
              </Box>
            ))}
          </Stack>
        </PanelCard>

        <PanelCard
          title="系统自检与最近审计"
          action={
            <Button variant="outlined" size="small" loading={checking} onClick={runSelfCheck}>
              运行自检
            </Button>
          }
        >
          {selfCheck ? (
            <Stack spacing={0.5}>
              <Typography variant="body2" sx={{ mb: 0.5 }}>
                总体：{selfCheck.status}
              </Typography>
              {(selfCheck.checks ?? []).map(check => (
                <Typography key={check.name} variant="caption" component="div">
                  {check.status === 'PASS' || check.status === 'OK' ? '通过' : check.status} ·{' '}
                  {check.name}
                  {check.detail ? `：${check.detail}` : ''}
                </Typography>
              ))}
            </Stack>
          ) : (
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              点击运行自检，检查本地数据库、目录与必要配置；只读检查，不会修改数据。
            </Typography>
          )}
          <Stack spacing={0.7} sx={{ mt: 1.2 }}>
            {(audit.data ?? []).slice(0, 4).map((row, index) => (
              <Stack key={row.id || index} direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
                <Typography variant="caption" color="text.secondary">
                  {dateTime(row.createdAt || row.CREATED_AT || row.at)}
                </Typography>
                <Typography variant="caption">
                  {oneLine(row.action || row.ACTION || row.summary || JSON.stringify(row), 100)}
                </Typography>
              </Stack>
            ))}
            {(audit.data ?? []).length === 0 && (
              <Typography variant="caption" color="text.secondary">
                暂无审计记录。
              </Typography>
            )}
          </Stack>
        </PanelCard>
      </Box>

      <RunDiagnosticsDrawer
        open={Boolean(diagnosticsRunId)}
        runId={diagnosticsRunId}
        onClose={() => setDiagnosticsRunId(null)}
      />
    </Stack>
  );
}
