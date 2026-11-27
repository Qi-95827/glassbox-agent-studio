import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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
const diff = vi.fn();

const revisionEdge = {
  reviewerNodeId: 'review',
  targetNodeId: 'write',
  revisionCount: 1,
  maxRevisions: 2,
  lastVerdictJson: '{"decision":"REVISE_REQUIRED","summary":"缺少证据"}'
};

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
    diff: (...args) => diff(...args),
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
  default: props => (
    <div data-testid="run-header">
      <button
        type="button"
        data-testid="switch-timeline"
        onClick={() => props.onTab?.('timeline')}
      />
      <button type="button" data-testid="switch-replay" onClick={() => props.onTab?.('replay')} />
      <button type="button" data-testid="switch-list" onClick={() => props.onTab?.('list')} />
    </div>
  )
}));
vi.mock('./RunTraceActions', () => ({ default: () => null }));
vi.mock('./RunRoundsBar', () => ({ default: () => null }));
vi.mock('./ToolApprovalDrawer', () => ({ default: () => null }));
vi.mock('./RunDiagnosticsDrawer', () => ({ default: () => null }));
vi.mock('../workflows/RunFlowGraph', () => ({
  default: props => (
    <div data-testid="run-flow-graph">
      <button
        type="button"
        data-testid="click-revision-edge"
        onClick={() => props.onSelectRevisionEdge?.(props.revisionEdges[0])}
      />
    </div>
  )
}));
// 只保留页面的状态接线：真实 NodeInspector 的视觉细节不在本用例范围内。
vi.mock('./NodeInspector', () => ({
  default: props => (
    <div data-testid="node-inspector">
      <span data-testid="inspector-node">{props.node?.id ?? 'none'}</span>
      <span data-testid="inspector-revision">
        {props.revisionEdge
          ? `${props.revisionEdge.reviewerNodeId}->${props.revisionEdge.targetNodeId}:${props.revisionEdge.revisionCount}/${props.revisionEdge.maxRevisions}`
          : 'none'}
      </span>
    </div>
  )
}));
vi.mock('../checkpoints/ExecutionTimelinePanel', () => ({
  default: props => (
    <div data-testid="execution-timeline-panel">
      <button
        type="button"
        data-testid="timeline-open-review"
        onClick={() => props.onOpenNode?.('review')}
      />
    </div>
  )
}));
vi.mock('../checkpoints/CheckpointTimeTravelPanel', () => ({ default: () => null }));
vi.mock('./RunReplayPanel', () => ({
  default: props => (
    <button type="button" data-testid="replay-locate" onClick={() => props.onLocate?.('review')} />
  )
}));
vi.mock('./RunListView', () => ({
  default: props => (
    <button
      type="button"
      data-testid="list-select"
      onClick={() => props.onSelectNode?.('review')}
    />
  )
}));
vi.mock('./RunOutputsPanel', () => ({ default: () => null }));
vi.mock('../checkpoints/RunCompareDiffView', () => ({ default: () => null }));

const detail = {
  run: { ID: 'r1', STATUS: 'COMPLETED', RESULT: '已交付' },
  task: { ID: 'task-1', GOAL: '修改一个文件', TASK_STATUS: 'COMPLETED' },
  agentAssemblies: []
};

const view = {
  runId: 'r1',
  taskId: 'task-1',
  runStatus: 'COMPLETED',
  nodes: [
    { id: 'write', label: '修改', role: 'CODER', status: 'COMPLETED', kind: 'AGENT' },
    { id: 'review', label: '审查', role: 'REVIEWER', status: 'COMPLETED', kind: 'AGENT' }
  ],
  edges: [],
  revisionEdges: [revisionEdge],
  focus: { active: [] },
  availableActions: [],
  generatedAt: '2026-01-01T00:00:00Z'
};

function renderPage(entry = '/runs/r1') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/runs/:runId" element={<RunDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('RunDetailPage 反思回边点击', () => {
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
    useNodeDetail.mockReturnValue({
      data: { attempts: [{ number: 1, models: [], tools: [] }], dependencies: [] },
      isLoading: false,
      error: null
    });
    useRunDelivery.mockReturnValue({ data: null, isLoading: false });
    forks.mockResolvedValue({ forks: [] });
    rounds.mockResolvedValue([]);
    diff.mockResolvedValue(null);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('点击反思回边不会崩溃，并定位写节点、把真实轮次传给检查器', async () => {
    renderPage();
    const button = await screen.findByTestId('click-revision-edge');

    // 崩溃回归：旧实现把 undefined 传进页面并立即读取 edge.toNodeId。
    await userEvent.click(button);

    await waitFor(() =>
      expect(screen.getByTestId('inspector-revision')).toHaveTextContent('review->write:1/2')
    );
    expect(screen.getByTestId('inspector-node')).toHaveTextContent('write');
  });

  it('X 光深链的 nodeId 参数会打开对应节点', async () => {
    renderPage('/runs/r1?nodeId=review&glassbox=assembly');
    await waitFor(() => expect(screen.getByTestId('inspector-node')).toHaveTextContent('review'));
  });

  it('历史 node 参数仍兼容，不会静默落空', async () => {
    renderPage('/runs/r1?node=review');
    await waitFor(() => expect(screen.getByTestId('inspector-node')).toHaveTextContent('review'));
  });

  it('先点反思回边再从时间轴切到另一节点时，必须清空旧回边避免错挂', async () => {
    renderPage();
    await userEvent.click(await screen.findByTestId('click-revision-edge'));
    await waitFor(() =>
      expect(screen.getByTestId('inspector-revision')).toHaveTextContent('review->write:1/2')
    );

    // 切到时间轴 Tab，再从时间轴打开 review 节点：旧反思回边必须立即清空。
    fireEvent.click(screen.getByTestId('switch-timeline'));
    fireEvent.click(await screen.findByTestId('timeline-open-review'));

    await waitFor(() => expect(screen.getByTestId('inspector-node')).toHaveTextContent('review'));
    expect(screen.getByTestId('inspector-revision')).toHaveTextContent('none');
  });

  it('从事件回放定位节点时同样必须清空旧反思回边', async () => {
    renderPage();
    await userEvent.click(await screen.findByTestId('click-revision-edge'));
    await waitFor(() =>
      expect(screen.getByTestId('inspector-revision')).toHaveTextContent('review->write:1/2')
    );

    fireEvent.click(screen.getByTestId('switch-replay'));
    fireEvent.click(await screen.findByTestId('replay-locate'));

    await waitFor(() => expect(screen.getByTestId('inspector-node')).toHaveTextContent('review'));
    expect(screen.getByTestId('inspector-revision')).toHaveTextContent('none');
  });

  it('从列表视图定位节点时同样必须清空旧反思回边', async () => {
    renderPage();
    await userEvent.click(await screen.findByTestId('click-revision-edge'));
    await waitFor(() =>
      expect(screen.getByTestId('inspector-revision')).toHaveTextContent('review->write:1/2')
    );

    fireEvent.click(screen.getByTestId('switch-list'));
    fireEvent.click(await screen.findByTestId('list-select'));

    await waitFor(() => expect(screen.getByTestId('inspector-node')).toHaveTextContent('review'));
    expect(screen.getByTestId('inspector-revision')).toHaveTextContent('none');
  });
});
