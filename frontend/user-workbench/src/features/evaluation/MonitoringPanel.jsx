import { useMemo, useState } from 'react';
import {
  Alert,
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
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useMonitoringAlerts, useMonitoringDashboard } from '../../hooks/useEvaluations';
import { evaluationApi } from '../../api/evaluations';
import { RUN_STATES, TOOL_NAMES, modelLabel } from '../../constants/labels';
import { googleTokens } from '../../theme/googleTheme';
import { dateTime, duration, oneLine, shortId } from '../../utils/format';
import StatusChip from '../../components/StatusChip';
import EmptyState from '../../components/EmptyState';
import RunDiagnosticsDrawer from '../runs/RunDiagnosticsDrawer';
import { DonutChart, GaugeArc, TrendChart, CHART_COLORS } from './monitoring/MonitoringCharts';

const RANGES = [
  { value: '24h', label: '近 24 小时' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
  { value: 'all', label: '全部' }
];

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'FAILED', label: '失败' },
  { value: 'BLOCKED', label: '阻塞' },
  { value: 'RUNNING', label: '运行中' }
];

const TREND_MODES = [
  { value: 'runs', label: '运行量' },
  { value: 'duration', label: '平均耗时' },
  { value: 'tokens', label: 'Token 消耗' }
];

const DONUT_MODES = [
  { value: 'taskStatus', label: '任务状态' },
  { value: 'model', label: '模型占比' },
  { value: 'tool', label: '工具分布' }
];

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

function formatNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number.toLocaleString('zh-CN') : '0';
}

function rateText(value) {
  const number = Number(value);
  return Number.isFinite(number) ? `${number.toFixed(1)}%` : '0.0%';
}

/** 类 Grafana 的细粒度运行监控看板：KPI、时序、圆盘、明细表与快捷排查入口。 */
export default function MonitoringPanel() {
  const navigate = useNavigate();
  const [range, setRange] = useState('7d');
  const [statusFilter, setStatusFilter] = useState('');
  const [modelFilter, setModelFilter] = useState('');
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [trendMode, setTrendMode] = useState('runs');
  const [donutMode, setDonutMode] = useState('taskStatus');
  const [anomaliesOnly, setAnomaliesOnly] = useState(false);
  const [diagnosticsRunId, setDiagnosticsRunId] = useState(null);

  const dashboard = useMonitoringDashboard({
    range,
    status: statusFilter || undefined,
    model: modelFilter || undefined,
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

  const donutSlices = useMemo(() => {
    if (!data) return [];
    if (donutMode === 'taskStatus') {
      return (data.distributions.taskStatusBreakdown ?? []).map(slice => ({
        key: slice.status,
        label: RUN_STATES[slice.status] || slice.status,
        value: slice.count
      }));
    }
    if (donutMode === 'model') {
      return (data.distributions.modelBreakdown ?? []).map(slice => ({
        key: slice.model,
        label: modelLabel(slice.model),
        value: slice.calls
      }));
    }
    return (data.distributions.toolBreakdown ?? []).map(slice => ({
      key: slice.tool,
      label: TOOL_NAMES[slice.tool] || slice.tool,
      value: slice.calls
    }));
  }, [data, donutMode]);

  const runSelfCheck = async () => {
    setChecking(true);
    try {
      setSelfCheck(await evaluationApi.selfCheck());
    } catch (error) {
      setSelfCheck({
        status: 'ERROR',
        checks: [{ name: '请求失败', status: 'FAILED', detail: error.message }]
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
    <Stack spacing={2}>
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
            aria-label="监控时间范围"
          >
            {RANGES.map(item => (
              <ToggleButton key={item.value} value={item.value}>
                {item.label}
              </ToggleButton>
            ))}
          </ToggleButtonGroup>

          <FormControl size="small" sx={{ minWidth: 128 }}>
            <InputLabel id="monitor-status-label">状态</InputLabel>
            <Select
              labelId="monitor-status-label"
              label="状态"
              value={statusFilter}
              onChange={event => setStatusFilter(event.target.value)}
            >
              {STATUS_OPTIONS.map(item => (
                <MenuItem key={item.value || 'all'} value={item.value}>
                  {item.label}
                </MenuItem>
              ))}
            </Select>
          </FormControl>

          <FormControl size="small" sx={{ minWidth: 150 }}>
            <InputLabel id="monitor-model-label">模型</InputLabel>
            <Select
              labelId="monitor-model-label"
              label="模型"
              value={modelFilter}
              onChange={event => setModelFilter(event.target.value)}
            >
              <MenuItem value="">全部模型</MenuItem>
              {modelOptions.map(item => (
                <MenuItem key={item.model} value={item.model}>
                  {modelLabel(item.model)}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </Stack>

        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
          <Stack direction="row" spacing={0.4} sx={{ alignItems: 'center' }}>
            <Typography variant="caption" color="text.secondary">
              自动刷新（30s）
            </Typography>
            <Switch
              size="small"
              checked={autoRefresh}
              onChange={event => setAutoRefresh(event.target.checked)}
              inputProps={{ 'aria-label': '自动刷新开关' }}
            />
          </Stack>
          <Tooltip title="立即刷新看板与告警">
            <span>
              <IconButton
                size="small"
                onClick={refreshAll}
                disabled={dashboard.isFetching}
                aria-label="手动刷新监控"
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
            {data?.generatedAt ? `更新于 ${dateTime(data.generatedAt)}` : '等待数据'}
          </Typography>
        </Stack>
      </Box>

      {dashboard.isError && <Alert severity="error">监控读取失败：{dashboard.error.message}</Alert>}

      {dashboard.isLoading && !data && (
        <Stack
          direction="row"
          spacing={1}
          sx={{ py: 6, alignItems: 'center', justifyContent: 'center' }}
        >
          <CircularProgress size={20} />
          <Typography variant="body2" color="text.secondary">
            正在聚合运行监控数据…
          </Typography>
        </Stack>
      )}

      {data && summary?.totalRuns === 0 && (
        <PanelCard>
          <EmptyState
            title="该时间段暂无运行数据"
            description="可以切换到「全部」查看历史运行；任务开始执行后，KPI、趋势与节点排行会自动出现。"
            action={{ label: '查看全部历史', onClick: () => setRange('all') }}
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
            <PanelCard title="任务成功率">
              <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                <GaugeArc value={summary.successRate} label="成功率" />
                <Stack spacing={0.3} sx={{ minWidth: 0 }}>
                  <Typography variant="caption" color="text.secondary">
                    完成 {formatNumber(summary.completedRuns)} / 总运行{' '}
                    {formatNumber(summary.totalRuns)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    已交付 {formatNumber(summary.deliveredRuns)} · 失败{' '}
                    {formatNumber(summary.failedRuns)}
                  </Typography>
                </Stack>
              </Stack>
            </PanelCard>

            <PanelCard title="总运行数 / 异常数">
              <MetricValue>{formatNumber(summary.totalRuns)}</MetricValue>
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
                异常 {formatNumber(summary.anomalyRunCount)} 次 · 点击筛选
              </Button>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                阻塞 {formatNumber(summary.blockedRuns)} · 失败 {formatNumber(summary.failedRuns)}
              </Typography>
            </PanelCard>

            <PanelCard title="平均耗时 / P95 耗时">
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

            <PanelCard title="Token 消耗（已知）">
              <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
                <TokenRounded sx={{ fontSize: 18, color: CHART_COLORS.purple, mt: 0.6 }} />
                <Box sx={{ minWidth: 0 }}>
                  <MetricValue>
                    {formatNumber(summary.totalInputTokens + summary.totalOutputTokens)}
                  </MetricValue>
                  <Typography variant="caption" color="text.secondary">
                    In {formatNumber(summary.totalInputTokens)} · Out{' '}
                    {formatNumber(summary.totalOutputTokens)}
                  </Typography>
                </Box>
              </Stack>
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ display: 'block', mt: 0.5 }}
              >
                模型调用 {formatNumber(summary.totalModelCalls)} 次 · 费用台账{' '}
                {summary.estimatedCost == null
                  ? '未记录'
                  : Number(summary.estimatedCost).toFixed(4)}
              </Typography>
            </PanelCard>

            <PanelCard title="ToolGateway 拦截">
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
                    拦截率 {rateText(summary.toolDenyRate)} · 工具调用{' '}
                    {formatNumber(summary.totalToolCalls)}
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
              title="时序趋势"
              subtitle="运行量、平均耗时与 Token 消耗共用同一时间分桶；悬停查看单桶明细。"
              action={
                <ToggleButtonGroup
                  exclusive
                  size="small"
                  value={trendMode}
                  onChange={(_, value) => value && setTrendMode(value)}
                  aria-label="趋势指标切换"
                >
                  {TREND_MODES.map(item => (
                    <ToggleButton key={item.value} value={item.value}>
                      {item.label}
                    </ToggleButton>
                  ))}
                </ToggleButtonGroup>
              }
            >
              <TrendChart points={data.timeSeries ?? []} metric={trendMode} />
            </PanelCard>

            <PanelCard
              title="分布圆盘"
              subtitle="在任务状态、模型调用与工具调用之间切换。"
              action={
                <ToggleButtonGroup
                  exclusive
                  size="small"
                  value={donutMode}
                  onChange={(_, value) => value && setDonutMode(value)}
                  aria-label="圆盘指标切换"
                >
                  {DONUT_MODES.map(item => (
                    <ToggleButton key={item.value} value={item.value}>
                      {item.label}
                    </ToggleButton>
                  ))}
                </ToggleButtonGroup>
              }
            >
              <DonutChart
                slices={donutSlices}
                totalLabel={
                  donutMode === 'taskStatus' ? '运行' : donutMode === 'model' ? '调用' : '工具'
                }
                valueSuffix=""
              />
            </PanelCard>
          </Box>

          <PanelCard
            title="细粒度运行监控"
            subtitle="按任务名、双层状态、模型、耗时、Token 与工具安检结果定位异常；一键排查会打开服务端诊断向导。"
            action={
              <ToggleButtonGroup
                exclusive
                size="small"
                value={anomaliesOnly ? 'anomaly' : 'all'}
                onChange={(_, value) => value && setAnomaliesOnly(value === 'anomaly')}
                aria-label="运行异常筛选"
              >
                <ToggleButton value="all">全部</ToggleButton>
                <ToggleButton value="anomaly">仅看异常</ToggleButton>
              </ToggleButtonGroup>
            }
          >
            {visibleRuns.length === 0 ? (
              <EmptyState
                compact
                title={anomaliesOnly ? '当前筛选下没有异常运行' : '暂无近期运行明细'}
                description={
                  anomaliesOnly ? '可以切回「全部」，或放宽时间范围后再次查看。' : undefined
                }
              />
            ) : (
              <TableContainer sx={{ maxHeight: 520 }}>
                <Table stickyHeader size="small" sx={{ minWidth: 920 }}>
                  <TableHead>
                    <TableRow>
                      <TableCell>任务 / 运行</TableCell>
                      <TableCell>双层状态</TableCell>
                      <TableCell>模型</TableCell>
                      <TableCell align="right">耗时</TableCell>
                      <TableCell align="right">Token（In / Out）</TableCell>
                      <TableCell align="right">工具（通过 / 拦截）</TableCell>
                      <TableCell align="right">操作</TableCell>
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
                            {oneLine(run.taskTitle || '未命名任务', 34)}
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
                            {run.model === 'mixed' ? '混合模型' : modelLabel(run.model)}
                          </Typography>
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{ display: 'block' }}
                          >
                            {run.nodeCount} 个节点
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
                              variant="contained"
                              onClick={() => setDiagnosticsRunId(run.runId)}
                              sx={{ minHeight: 28, fontSize: 12 }}
                            >
                              一键排查
                            </Button>
                            <Button
                              size="small"
                              variant="text"
                              onClick={() => openRunNode(run.runId)}
                              sx={{ minHeight: 28, fontSize: 12 }}
                            >
                              查看运行
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
