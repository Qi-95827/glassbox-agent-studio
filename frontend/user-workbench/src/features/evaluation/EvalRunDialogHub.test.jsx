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
  evalLabApi: { start: (...args) => startMock(...args) }
}));

vi.mock('../../hooks/useModelConnections', () => ({
  useModelConnections: () => ({ data: { connections: [] } })
}));

vi.mock('../../hooks/useEvalLab', () => {
  const packs = [
    ['agentdojo-security', 'SAFETY_INJECTION'],
    ['bfcl-v3-simple', 'TOOL_MCP'],
    ['gaia-dev-l1', 'PLANNING_MULTI_AGENT'],
    ['longmemeval-s', 'LONG_TERM_MEMORY'],
    ['ruler-niah-compact', 'CONTEXT_COMPACTION'],
    ['swe-bench-lite', 'CODING_REFLECTION']
  ].map(([packId, category]) => ({
    packId,
    category,
    displayName: packId,
    sampleCount: packId === 'swe-bench-lite' ? 1 : 3,
    previewOnly: packId === 'swe-bench-lite',
    status: 'IMPORTED'
  }));
  return {
    useEvalHub: () => ({ data: packs, isLoading: false }),
    useEvalDatasets: () => ({ data: [] })
  };
});

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

function renderHubDialog() {
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider initialLocale="zh-CN">
        <ToastProvider>
          <EvalRunDialog
            open
            meta={meta}
            initialSource="HUB"
            initialSourceKey="all-ready"
            onClose={() => {}}
            onCreated={() => {}}
          />
        </ToastProvider>
      </I18nProvider>
    </QueryClientProvider>
  );
}

describe('评测发起弹窗 · 公开集多选', () => {
  beforeEach(() => {
    startMock.mockReset();
    startMock.mockResolvedValue({ id: 'run-multi' });
  });

  it('全选已下载公开集、合并开关并提交排序后的联合题单', async () => {
    renderHubDialog();

    await waitFor(() => expect(screen.getByText(/已选 5 套/)).toBeInTheDocument());
    expect(screen.getByText(/每个公开集抽样 5 题 × 5 套 = 15 题/)).toBeInTheDocument();

    // 5 个维度对应开关并集：压缩、记忆、反思、MCP 全部可见。
    expect(screen.getByLabelText('开启四级上下文压缩')).toBeInTheDocument();
    expect(screen.getByLabelText('开启三层长期记忆')).toBeInTheDocument();
    expect(screen.getByLabelText('开启有界反思环')).toBeInTheDocument();
    expect(screen.getByLabelText('启用已配置 MCP 工具')).toBeInTheDocument();
    expect(screen.getByText(/需独立 Docker 环境/)).toBeInTheDocument();

    await userEvent.click(screen.getAllByRole('combobox')[1]);
    await userEvent.click(
      await screen.findByRole('option', { name: '模拟模型（免 Key，验证流程）' })
    );
    await userEvent.click(screen.getByRole('button', { name: '开始评测' }));

    await waitFor(() => expect(startMock).toHaveBeenCalledTimes(1));
    const payload = startMock.mock.calls[0][0];
    expect(payload.sourceKind).toBe('HUB');
    expect(payload.sourceKey).toBe(
      'agentdojo-security,bfcl-v3-simple,gaia-dev-l1,longmemeval-s,ruler-niah-compact'
    );
    expect(new Set(payload.categories)).toEqual(
      new Set([
        'SAFETY_INJECTION',
        'TOOL_MCP',
        'PLANNING_MULTI_AGENT',
        'LONG_TERM_MEMORY',
        'CONTEXT_COMPACTION'
      ])
    );
    expect(payload.sampleLimit).toBe(5);
    expect(payload.repeatK).toBe(1);
    expect(payload.sourceKey).not.toContain('swe-bench-lite');
  });

  it('“仅选单项”立即收敛为一个公开集', async () => {
    renderHubDialog();
    await waitFor(() => expect(screen.getByText(/已选 5 套/)).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: '仅选单项' }));
    expect(screen.getByText(/已选 1 套/)).toBeInTheDocument();
  });
});
