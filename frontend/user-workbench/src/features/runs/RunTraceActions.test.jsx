import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import RunTraceActions from './RunTraceActions';
import { runsApiExtras } from '../../api/traceExport';
import { useAuth } from '../auth/AuthGate';
import { setModuleLocale } from '../../i18n';

vi.mock('../auth/AuthGate', () => ({ useAuth: vi.fn() }));

const preview = {
  datasetId: 'ds-1',
  datasetCode: 'run-distilled',
  sampleKey: 'run-abcdef12-345',
  titleZh: '运行沉淀： abcdef12-345',
  titleEn: 'Distilled from run abcdef12-345',
  prompt: '把排序网页做出来',
  setup: { sourceRunId: 'abcdef12-3456' },
  assertions: [
    { type: 'RUN_STATUS_MATCH', expected: 'COMPLETED' },
    { type: 'OUTPUT_CONTAINS', expected: '排序完成' }
  ],
  limitations: ['模型原始回复不会进入评测数据集。']
};

const renderActions = props => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <RunTraceActions runId="abcdef12-3456" {...props} />
    </QueryClientProvider>
  );
};

describe('RunTraceActions', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
    useAuth.mockReturnValue({ user: { role: 'USER' } });
    vi.spyOn(runsApiExtras, 'evalCasePreview').mockResolvedValue(preview);
    vi.spyOn(runsApiExtras, 'exportEvalCase').mockResolvedValue({
      datasetId: 'ds-1',
      datasetCode: 'run-distilled',
      sampleKey: 'run-abcdef12-345',
      total: '1 题'
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('两个导出按钮都指向服务端只读导出地址', () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    renderActions();
    fireEvent.click(screen.getByTestId('export-otel'));
    expect(open).toHaveBeenCalledWith(
      '/api/runs/abcdef12-3456/trace-export?format=otel',
      '_blank',
      'noopener'
    );
    fireEvent.click(screen.getByTestId('export-glassbox'));
    expect(open).toHaveBeenLastCalledWith(
      '/api/runs/abcdef12-3456/trace-export?format=glassbox',
      '_blank',
      'noopener'
    );
  });

  it('沉淀前先展示脱敏预览，确认后才写库', async () => {
    const onOpenEvalLab = vi.fn();
    renderActions({ onOpenEvalLab });
    fireEvent.click(screen.getByTestId('distill-eval-case'));

    expect(await screen.findByText('把排序网页做出来')).toBeInTheDocument();
    expect(screen.getByText(/RUN_STATUS_MATCH = COMPLETED/)).toBeInTheDocument();
    expect(screen.getByText(/模型原始回复不会进入评测数据集/)).toBeInTheDocument();
    expect(runsApiExtras.exportEvalCase).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('distill-eval-confirm'));
    await waitFor(() => expect(runsApiExtras.exportEvalCase).toHaveBeenCalledWith('abcdef12-3456'));
    fireEvent.click(await screen.findByText('去评测实验室'));
    expect(onOpenEvalLab).toHaveBeenCalledWith('ds-1');
  });

  it('沉淀失败时如实报错，不假装成功', async () => {
    runsApiExtras.exportEvalCase.mockRejectedValue(new Error('数据集写入失败'));
    renderActions();
    fireEvent.click(screen.getByTestId('distill-eval-case'));
    await screen.findByText('把排序网页做出来');
    fireEvent.click(screen.getByTestId('distill-eval-confirm'));
    expect(await screen.findByText('数据集写入失败')).toBeInTheDocument();
  });

  it('管理员可以从运行直达任务监控，并带上任务标识', () => {
    useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
    const onOpenMonitoring = vi.fn();
    renderActions({ taskId: 'task-1', onOpenMonitoring });
    fireEvent.click(screen.getByTestId('open-task-monitoring'));
    expect(onOpenMonitoring).toHaveBeenCalledWith('task-1');
  });

  it('普通用户看不到任务监控入口，避免点出 403', () => {
    useAuth.mockReturnValue({ user: { role: 'USER' } });
    renderActions({ taskId: 'task-1' });
    expect(screen.queryByTestId('open-task-monitoring')).not.toBeInTheDocument();
  });
});
