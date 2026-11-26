import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ExecutionTimelinePanel, { timelineWindow } from './ExecutionTimelinePanel';
import { runsApi } from '../../api/runs';
import { setModuleLocale } from '../../i18n';

const timeline = {
  schemaVersion: 1,
  runId: 'run-1',
  summary: {
    status: 'COMPLETED',
    elapsedMs: 5400,
    rounds: 2,
    tools: 3,
    modelCalls: 2,
    toolCalls: 3,
    inputTokens: 120,
    outputTokens: 80,
    completedNodes: 2,
    totalNodes: 2
  },
  spans: [
    {
      id: 'node:n1',
      kind: 'NODE',
      nodeId: 'n1',
      nodeKey: 'analyze',
      label: '分析',
      status: 'COMPLETED',
      startedAt: '2026-11-26T09:00:00Z',
      finishedAt: '2026-11-26T09:00:02Z',
      startMs: 1764147600000,
      endMs: 1764147602000
    },
    {
      id: 'model:m1',
      kind: 'MODEL',
      nodeId: 'n1',
      nodeKey: 'analyze',
      label: 'deepseek-chat',
      status: 'COMPLETED',
      startedAt: '2026-11-26T09:00:00Z',
      finishedAt: '2026-11-26T09:00:01Z',
      startMs: 1764147600000,
      endMs: 1764147601000
    },
    {
      id: 'approval:a1',
      kind: 'APPROVAL',
      nodeId: 'n2',
      nodeKey: 'implement',
      label: 'workspace_write',
      status: 'APPROVED',
      startedAt: '2026-11-26T09:00:03Z',
      finishedAt: '2026-11-26T09:00:05Z',
      startMs: 1764147603000,
      endMs: 1764147605000
    }
  ],
  limitations: ['部分模型调用没有记录耗时，时间轴只显示其开始时刻。']
};

const renderPanel = props => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ExecutionTimelinePanel runId="run-1" {...props} />
    </QueryClientProvider>
  );
};

describe('timelineWindow', () => {
  it('只按真实时间戳取窗口，缺时间的 Span 不参与拉伸', () => {
    const window = timelineWindow([
      { startMs: 100, endMs: 200 },
      { startMs: 300, endMs: null },
      { startMs: null, endMs: 900 }
    ]);
    expect(window).toEqual({ from: 100, to: 300 });
    expect(timelineWindow([{ startMs: null }])).toBeNull();
  });
});

describe('ExecutionTimelinePanel', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
    vi.spyOn(runsApi, 'timeline').mockResolvedValue(timeline);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('按真实 Span 渲染泳道与汇总，并列出限制', async () => {
    renderPanel();
    expect(await screen.findByTestId('execution-timeline')).toBeInTheDocument();
    expect(screen.getByTestId('timeline-span-NODE')).toBeInTheDocument();
    expect(screen.getByTestId('timeline-span-MODEL')).toBeInTheDocument();
    expect(screen.getByTestId('timeline-span-APPROVAL')).toBeInTheDocument();
    expect(screen.getByText('模型 2 次')).toBeInTheDocument();
    expect(screen.getByText(/部分模型调用没有记录耗时/)).toBeInTheDocument();
  });

  it('点击节点 Span 直接把该节点交给上层打开玻璃盒', async () => {
    const onOpenNode = vi.fn();
    renderPanel({ onOpenNode });
    const span = await screen.findByTestId('timeline-span-NODE');
    fireEvent.click(span);
    expect(onOpenNode).toHaveBeenCalledWith('n1');
  });

  it('没有记录时给出空状态而不是画一条时间轴', async () => {
    runsApi.timeline.mockResolvedValue({ ...timeline, spans: [], limitations: [] });
    renderPanel();
    await waitFor(() => expect(screen.getByTestId('timeline-empty')).toBeInTheDocument());
    expect(screen.queryByTestId('execution-timeline')).not.toBeInTheDocument();
  });
});
