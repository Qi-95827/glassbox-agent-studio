import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { setModuleLocale } from '../../i18n';
import RunDetailPage from './RunDetailPage';

const useRunExecution = vi.fn();
const useRunEvents = vi.fn();
const useNodeDetail = vi.fn();
const useRunDelivery = vi.fn();
const rounds = vi.fn();
const forks = vi.fn();

vi.mock('../../hooks/useRunExecution', () => ({
  useRunExecution: (...args) => useRunExecution(...args),
  useRunEvents: (...args) => useRunEvents(...args),
  useNodeDetail: (...args) => useNodeDetail(...args),
  useRunDelivery: (...args) => useRunDelivery(...args)
}));

vi.mock('../../api/runs', () => ({
  runsApi: {
    rounds: (...args) => rounds(...args),
    forks: (...args) => forks(...args),
    iterate: vi.fn(),
    approvePlan: vi.fn(),
    pause: vi.fn(),
    resume: vi.fn(),
    cancel: vi.fn(),
    takeHandover: vi.fn(),
    returnHandover: vi.fn(),
    recover: vi.fn(),
    submitSupplement: vi.fn(),
    updateDebugControl: vi.fn()
  },
  reportUrl: () => '/api/report'
}));

vi.mock('../../layout/pageChrome', () => ({ usePageChrome: () => {} }));
vi.mock('../../components/ToastProvider', () => ({
  useToast: () => ({ success: () => {}, error: () => {}, info: () => {}, warning: () => {} })
}));
vi.mock('./RunHeader', () => ({
  default: ({ onTab }) => <div data-testid="run-header" onClick={() => onTab?.('flow')} />
}));
vi.mock('./RunTraceActions', () => ({ default: () => <div data-testid="run-trace-actions" /> }));
vi.mock('./ToolApprovalDrawer', () => ({ default: () => null }));
vi.mock('./RunDiagnosticsDrawer', () => ({ default: () => null }));
vi.mock('./NodeInspector', () => ({ default: () => null }));
vi.mock('./RunFlowGraph', () => ({ default: () => <div data-testid="run-flow-graph" /> }));
vi.mock('../workflows/RunFlowGraph', () => ({
  default: () => <div data-testid="run-flow-graph" />
}));
vi.mock('../checkpoints/ExecutionTimelinePanel', () => ({ default: () => null }));
vi.mock('../checkpoints/CheckpointTimeTravelPanel', () => ({ default: () => null }));
vi.mock('./RunReplayPanel', () => ({ default: () => null }));
vi.mock('./RunListView', () => ({ default: () => null }));
vi.mock('./RunOutputsPanel', () => ({ default: () => null }));
vi.mock('../checkpoints/RunCompareDiffView', () => ({ default: () => null }));

const detail = {
  run: { ID: 'r2', STATUS: 'COMPLETED', RESULT: '本轮交付：准备发布' },
  task: { ID: 'task-1', GOAL: '输出发布清单', TASK_STATUS: 'COMPLETED' },
  agentAssemblies: []
};

const view = {
  runId: 'r2',
  taskId: 'task-1',
  runStatus: 'COMPLETED',
  nodes: [{ id: 'n1', label: '分析', status: 'COMPLETED' }],
  edges: [],
  focus: { active: [] },
  availableActions: [],
  generatedAt: '2026-01-01T00:00:00Z'
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/runs/r2']}>
        <Routes>
          <Route path="/runs/:runId" element={<RunDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('RunDetailPage rounds bar', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
    useRunExecution.mockReturnValue({
      detail,
      view,
      live: false,
      streamConnected: false,
      streamNodeId: null,
      streamsByNode: {},
      loading: false,
      error: null,
      refetch: () => {}
    });
    useRunEvents.mockReturnValue({ data: [], isLoading: false });
    useNodeDetail.mockReturnValue({ data: null, isLoading: false, error: null });
    useRunDelivery.mockReturnValue({ data: null, isLoading: false });
    forks.mockResolvedValue({ forks: [] });
    rounds.mockResolvedValue([
      { runId: 'r1', roundIndex: 1, parentRunId: null, status: 'COMPLETED', createdAt: 't1' },
      { runId: 'r2', roundIndex: 2, parentRunId: 'r1', status: 'COMPLETED', createdAt: 't2' }
    ]);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('多轮时在顶部展示轮次条并标出当前轮', async () => {
    renderPage();
    expect(await screen.findByTestId('run-rounds-bar')).toBeInTheDocument();
    expect(rounds).toHaveBeenCalledWith('task-1');
    const second = await screen.findByTestId('run-round-2');
    expect(second.textContent).toContain('第 2 轮');
    expect(second.textContent).toContain('当前');
    expect(screen.getByTestId('run-round-1').textContent).toContain('第 1 轮');
    expect(screen.getByTestId('run-rounds-compare')).toBeInTheDocument();
  });

  it('点击上一轮会切换到那条运行的完整视图', async () => {
    renderPage();
    const first = await screen.findByTestId('run-round-1');
    expect(first.textContent).not.toContain('当前');
    await userEvent.click(first);
    // 切换后路由指向第 1 轮，轮次条把第 1 轮标为当前轮。
    await waitFor(() => expect(screen.getByTestId('run-round-1').textContent).toContain('当前'));
  });

  it('只有一轮时不展示轮次条', async () => {
    rounds.mockResolvedValue([
      { runId: 'r2', roundIndex: 1, parentRunId: null, status: 'COMPLETED', createdAt: 't2' }
    ]);
    renderPage();
    await waitFor(() => expect(rounds).toHaveBeenCalled());
    expect(screen.queryByTestId('run-rounds-bar')).not.toBeInTheDocument();
  });
});
