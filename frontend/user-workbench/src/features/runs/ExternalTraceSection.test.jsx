import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ExternalTraceSection from './ExternalTraceSection';
import { runsApiExtras } from '../../api/traceExport';
import { setModuleLocale } from '../../i18n';

const run = {
  id: 'ext-1',
  name: 'my-agent-run',
  source: 'python',
  createdAt: '2026-11-26T09:00:00Z',
  spanCount: 2
};

const detail = {
  run,
  spans: [
    {
      id: 's1',
      kind: 'NODE',
      name: 'plan',
      nodeKey: 'plan',
      status: 'COMPLETED',
      startedAt: '2026-11-26T09:00:00Z',
      endedAt: '2026-11-26T09:00:01Z',
      attributes: {}
    },
    {
      id: 's2',
      kind: 'MODEL',
      name: 'deepseek-chat',
      nodeKey: 'plan',
      status: 'COMPLETED',
      startedAt: '2026-11-26T09:00:01Z',
      endedAt: '2026-11-26T09:00:02Z',
      attributes: {}
    }
  ],
  limitations: [
    '外部 Trace 没有计划、工具授权与检查点，因此只能只读透视，不能恢复、分叉或重新执行。'
  ]
};

const renderSection = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ExternalTraceSection />
    </QueryClientProvider>
  );
};

describe('ExternalTraceSection', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
    vi.spyOn(runsApiExtras, 'externalTraces').mockResolvedValue([]);
    vi.spyOn(runsApiExtras, 'externalTrace').mockResolvedValue(detail);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('没有外部 Trace 时给空状态与指南入口', async () => {
    renderSection();
    expect(await screen.findByTestId('external-empty')).toBeInTheDocument();
    expect(screen.getByTestId('external-guide-open')).toBeInTheDocument();
  });

  it('指南抽屉给出真实端点与限制，而不是泛泛而谈', async () => {
    renderSection();
    fireEvent.click(screen.getByTestId('external-guide-open'));
    const guide = await screen.findByTestId('external-guide');
    expect(guide).toHaveTextContent('/api/external-traces/runs');
    expect(guide).toHaveTextContent('ISO-8601');
    expect(guide).toHaveTextContent('只读透视');
  });

  it('点开外部运行后按 Span 只读展示时间线与明细', async () => {
    runsApiExtras.externalTraces.mockResolvedValue([run]);
    renderSection();
    fireEvent.click(await screen.findByTestId('external-run-ext-1'));

    await waitFor(() => expect(runsApiExtras.externalTrace).toHaveBeenCalledWith('ext-1'));
    const viewer = await screen.findByTestId('external-viewer');
    expect(viewer).toHaveTextContent('plan');
    expect(viewer).toHaveTextContent('deepseek-chat');
    expect(viewer).toHaveTextContent('只读透视');
    expect(await screen.findByTestId('external-gantt')).toBeInTheDocument();
  });
});
