import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { setModuleLocale } from '../../i18n';
import MonitoringPanel from './MonitoringPanel';

const useMonitoringDashboard = vi.fn();
const useMonitoringAlerts = vi.fn();
const audit = vi.fn();
const selfCheck = vi.fn();

vi.mock('../../hooks/useEvaluations', () => ({
  useMonitoringDashboard: (...args) => useMonitoringDashboard(...args),
  useMonitoringAlerts: (...args) => useMonitoringAlerts(...args)
}));

vi.mock('../../api/evaluations', () => ({
  evaluationApi: {
    audit: (...args) => audit(...args),
    selfCheck: (...args) => selfCheck(...args)
  }
}));

vi.mock('../runs/RunDiagnosticsDrawer', () => ({ default: () => null }));
vi.mock('./monitoring/MonitoringCharts', () => ({
  CHART_COLORS: { error: '#C5221F', warning: '#B06000', purple: '#7B1FA2' },
  DonutChart: () => <div data-testid="donut-chart" />,
  GaugeArc: () => <div data-testid="gauge-arc" />,
  TrendChart: () => <div data-testid="trend-chart" />
}));

const taskOptions = [
  { taskId: 'task-a', goal: '实现单文件番茄钟', latestStatus: 'COMPLETED', runCount: 3 },
  { taskId: 'task-b', goal: '教学剧本：工具限额演示', latestStatus: 'BLOCKED', runCount: 1 }
];

function dashboardFor({ taskId } = {}) {
  const scoped = Boolean(taskId);
  return {
    range: '7d',
    generatedAt: '2026-01-01T00:00:00Z',
    summary: {
      totalRuns: scoped ? 2 : 3,
      completedRuns: 1,
      deliveredRuns: 0,
      failedRuns: 1,
      blockedRuns: 1,
      anomalyRunCount: 1,
      successRate: 33.3,
      avgRunDurationMs: 1200,
      p95RunDurationMs: 2000,
      totalModelCalls: 3,
      totalInputTokens: 100,
      totalOutputTokens: 40,
      estimatedCost: null,
      totalToolCalls: 2,
      toolDenyCount: 1,
      toolDenyRate: 50
    },
    timeSeries: [
      {
        bucketLabel: '01-01',
        bucketStart: '2026-01-01T00:00:00Z',
        runCount: 2,
        successCount: 1,
        failedCount: 1,
        avgDurationMs: 1000,
        totalTokens: 10
      }
    ],
    distributions: {
      taskStatusBreakdown: [{ status: 'COMPLETED', count: 1, percent: 50 }],
      modelBreakdown: [
        { model: 'mock', calls: 3, totalTokens: 140, avgLatencyMs: 100, failedCalls: 0 }
      ],
      toolBreakdown: [
        { tool: 'WORKSPACE_WRITE', calls: 2, passedCalls: 1, failedCalls: 1, avgDurationMs: 20 }
      ],
      gatewayDenyBreakdown: []
    },
    rankings: {
      recentRuns: [
        {
          runId: 'run-a2',
          taskId: 'task-a',
          taskTitle: '实现单文件番茄钟',
          taskStatus: 'COMPLETED',
          runStatus: 'COMPLETED',
          stopCause: null,
          model: 'mock',
          nodeCount: 3,
          durationMs: 1000,
          inputTokens: 60,
          outputTokens: 20,
          toolCalls: 1,
          toolDenies: 0,
          hasAnomaly: false,
          createdAt: '2026-01-01T00:00:00Z'
        },
        {
          runId: 'run-b1',
          taskId: 'task-b',
          taskTitle: '教学剧本：工具限额演示',
          taskStatus: 'BLOCKED',
          runStatus: 'BLOCKED',
          stopCause: 'PATH_REJECTED',
          model: 'mock',
          nodeCount: 2,
          durationMs: 2000,
          inputTokens: 40,
          outputTokens: 20,
          toolCalls: 1,
          toolDenies: 1,
          hasAnomaly: true,
          createdAt: '2026-01-01T00:00:00Z'
        }
      ],
      slowestNodes: [],
      highTokenRuns: []
    },
    selectedTaskId: taskId ?? null,
    selectedTaskGoal: taskId
      ? (taskOptions.find(item => item.taskId === taskId)?.goal ?? null)
      : null,
    taskOptions
  };
}

function renderPanel(path = '/monitoring') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/monitoring" element={<MonitoringPanel />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('MonitoringPanel per-task focus', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
    useMonitoringDashboard.mockImplementation(args => ({
      data: dashboardFor(args),
      isLoading: false,
      isFetching: false,
      isError: false,
      error: null,
      refetch: vi.fn()
    }));
    useMonitoringAlerts.mockReturnValue({ data: [], refetch: vi.fn() });
    audit.mockResolvedValue([]);
    selfCheck.mockResolvedValue({ status: 'PASS', checks: [] });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('URL 带 taskId 时按单任务查询并展示聚焦横幅', async () => {
    renderPanel('/monitoring?taskId=task-a');
    await waitFor(() =>
      expect(useMonitoringDashboard).toHaveBeenCalledWith(
        expect.objectContaining({ taskId: 'task-a' })
      )
    );
    const banner = await screen.findByTestId('monitoring-focus-banner');
    expect(banner).toHaveTextContent('单任务监控聚焦');
    expect(banner).toHaveTextContent('实现单文件番茄钟');
    expect(screen.getByTestId('monitoring-focus-clear')).toBeInTheDocument();
  });

  it('选择任务后查询收敛到该任务并把筛选写进 URL', async () => {
    renderPanel('/monitoring');
    await waitFor(() =>
      expect(useMonitoringDashboard).toHaveBeenCalledWith(
        expect.objectContaining({ taskId: undefined })
      )
    );
    expect(screen.queryByTestId('monitoring-focus-banner')).not.toBeInTheDocument();

    const combo = screen.getByRole('combobox', { name: '按任务筛选' });
    await userEvent.click(combo);
    await userEvent.click(await screen.findByRole('option', { name: /教学剧本：工具限额演示/ }));

    await waitFor(() =>
      expect(useMonitoringDashboard).toHaveBeenLastCalledWith(
        expect.objectContaining({ taskId: 'task-b' })
      )
    );
    expect(await screen.findByTestId('monitoring-focus-banner')).toHaveTextContent(
      '教学剧本：工具限额演示'
    );
  });

  it('返回全部任务会清除筛选与横幅', async () => {
    renderPanel('/monitoring?taskId=task-a');
    await screen.findByTestId('monitoring-focus-banner');
    await userEvent.click(screen.getByTestId('monitoring-focus-clear'));
    await waitFor(() =>
      expect(useMonitoringDashboard).toHaveBeenLastCalledWith(
        expect.objectContaining({ taskId: undefined })
      )
    );
    expect(screen.queryByTestId('monitoring-focus-banner')).not.toBeInTheDocument();
  });

  it('明细行「聚焦此任务」把看板收敛到该行的任务', async () => {
    renderPanel('/monitoring');
    await screen.findByText('实现单文件番茄钟');
    await userEvent.click(screen.getByTestId('focus-task-run-b1'));
    await waitFor(() =>
      expect(useMonitoringDashboard).toHaveBeenLastCalledWith(
        expect.objectContaining({ taskId: 'task-b' })
      )
    );
    expect(await screen.findByTestId('monitoring-focus-banner')).toHaveTextContent('task-b');
  });

  it('任务选择器支持关键词过滤', async () => {
    renderPanel('/monitoring');
    const combo = screen.getByRole('combobox', { name: '按任务筛选' });
    await userEvent.click(combo);
    expect(await screen.findByRole('option', { name: /实现单文件番茄钟/ })).toBeInTheDocument();
    await userEvent.type(combo, '限额');
    await waitFor(() => {
      const options = screen.queryAllByRole('option');
      expect(options).toHaveLength(1);
      expect(within(options[0]).getByText(/教学剧本：工具限额演示/)).toBeInTheDocument();
    });
  });
});
