import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from '../../app/queryClient';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ToastProvider } from '../../components/ToastProvider';
import EvalRunDialog from './EvalRunDialog';

const startMock = vi.fn();

vi.mock('../../api/evalLab', () => ({
  evalLabApi: {
    start: (...args) => startMock(...args)
  }
}));

vi.mock('../../hooks/useModelConnections', () => ({
  useModelConnections: () => ({ data: { connections: [] } })
}));

vi.mock('../../hooks/useEvalLab', () => ({
  useEvalHub: () => ({ data: [] }),
  useEvalDatasets: () => ({ data: [] })
}));

const meta = {
  categories: [
    { code: 'CODING_REFLECTION', labelZh: '代码', labelEn: 'Coding', miniCount: 4 },
    { code: 'TOOL_MCP', labelZh: '工具', labelEn: 'Tool', miniCount: 4 },
    { code: 'CONTEXT_COMPACTION', labelZh: '压缩', labelEn: 'Compaction', miniCount: 4 },
    { code: 'LONG_TERM_MEMORY', labelZh: '记忆', labelEn: 'Memory', miniCount: 4 },
    { code: 'PLANNING_MULTI_AGENT', labelZh: '规划', labelEn: 'Planning', miniCount: 4 },
    { code: 'SAFETY_INJECTION', labelZh: '安全', labelEn: 'Safety', miniCount: 4 }
  ],
  maxRepeatK: 3,
  maxSampleLimit: 50
};

function renderDialog() {
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider initialLocale="zh-CN">
        <ToastProvider>
          <EvalRunDialog open meta={meta} onClose={() => {}} onCreated={() => {}} />
        </ToastProvider>
      </I18nProvider>
    </QueryClientProvider>
  );
}

describe('评测发起弹窗', () => {
  beforeEach(() => {
    startMock.mockReset();
    startMock.mockResolvedValue({ id: 'run-1' });
  });

  it('默认选中全部六类并以冻结开关启动模拟评测', async () => {
    renderDialog();
    expect(screen.getByText('新建评测实验')).toBeInTheDocument();
    expect(screen.getByText(/代码 \/ Coding/)).toBeInTheDocument();
    expect(screen.getByText(/安全 \/ Safety/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '开始评测' }));

    await waitFor(() => expect(startMock).toHaveBeenCalledTimes(1));
    const payload = startMock.mock.calls[0][0];
    expect(payload.sourceKind).toBe('MINI');
    expect(payload.categories).toHaveLength(6);
    expect(payload.modelMode).toBe('mock');
    expect(payload.enableCompaction).toBe(true);
    expect(payload.enableMcp).toBe(false);
    expect(payload.repeatK).toBe(1);
  });
});
