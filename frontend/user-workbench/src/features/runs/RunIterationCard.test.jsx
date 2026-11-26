import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import RunIterationCard from './RunIterationCard';
import { runsApi } from '../../api/runs';
import { setModuleLocale } from '../../i18n';

vi.mock('../../api/runs', () => ({
  runsApi: { iterate: vi.fn() },
  reportUrl: () => '/api/report'
}));

vi.mock('../../components/ToastProvider', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() })
}));

vi.mock('../auth/AuthGate', () => ({
  useAuth: () => ({ user: { role: 'USER' } })
}));

const completedRun = { ID: 'run-1', STATUS: 'COMPLETED', RESULT: '交付完成' };

describe('RunIterationCard', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
    runsApi.iterate.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('运行未结束时只提示不可用，不提供提交入口', () => {
    render(<RunIterationCard run={{ ID: 'run-1', STATUS: 'RUNNING' }} taskId="task-1" />);
    expect(screen.getByText('只有已结束的运行可以发起调改迭代。')).toBeInTheDocument();
    expect(screen.queryByTestId('run-iteration-submit')).not.toBeInTheDocument();
  });

  it('提交调改意见后调用迭代接口并带上新一轮轮次', async () => {
    runsApi.iterate.mockResolvedValue({
      runId: 'run-2',
      taskId: 'task-1',
      roundIndex: 2,
      parentRunId: 'run-1'
    });
    const onStarted = vi.fn();
    render(
      <RunIterationCard run={completedRun} taskId="task-1" roundIndex={1} onStarted={onStarted} />
    );

    const feedback = screen.getByTestId('run-iteration-feedback');
    fireEvent.change(feedback, { target: { value: '把按钮文案改成中文，并补充空状态提示' } });
    fireEvent.click(screen.getByTestId('run-iteration-submit'));

    await waitFor(() =>
      expect(runsApi.iterate).toHaveBeenCalledWith('run-1', {
        feedback: '把按钮文案改成中文，并补充空状态提示'
      })
    );
    await waitFor(() =>
      expect(onStarted).toHaveBeenCalledWith(expect.objectContaining({ runId: 'run-2' }))
    );
  });

  it('空意见不发起请求，失败时如实展示服务端原因', async () => {
    runsApi.iterate.mockRejectedValue(new Error('该任务已有进行中的运行'));
    render(<RunIterationCard run={completedRun} taskId="task-1" />);

    const submit = screen.getByTestId('run-iteration-submit');
    expect(submit).toBeDisabled();

    fireEvent.change(screen.getByTestId('run-iteration-feedback'), {
      target: { value: '补充回滚步骤' }
    });
    fireEvent.click(submit);
    expect(await screen.findByTestId('run-iteration-error')).toHaveTextContent(
      '该任务已有进行中的运行'
    );
  });

  it('普通用户看不到宿主机 DeepSeek 选项', () => {
    render(<RunIterationCard run={completedRun} taskId="task-1" />);
    fireEvent.mouseDown(screen.getByRole('combobox'));
    expect(screen.getByText('内置模拟模型')).toBeInTheDocument();
    expect(screen.queryByText('宿主机 DeepSeek（仅管理员）')).not.toBeInTheDocument();
  });
});
