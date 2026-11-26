import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import RunCompareDiffView from './RunCompareDiffView';
import CodeDiffView, { diffLines } from './CodeDiffView';
import { runsApi } from '../../api/runs';
import { setModuleLocale } from '../../i18n';

const diff = {
  schemaVersion: 1,
  baseRunId: 'aaaaaaaa-1111',
  targetRunId: 'bbbbbbbb-2222',
  base: {
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
  target: {
    status: 'FAILED',
    elapsedMs: 6100,
    rounds: 3,
    tools: 4,
    modelCalls: 3,
    toolCalls: 4,
    inputTokens: 150,
    outputTokens: 90,
    completedNodes: 1,
    totalNodes: 2
  },
  nodes: [
    {
      key: 'implement',
      title: '按授权修改',
      role: 'IMPLEMENT',
      base: {
        status: 'COMPLETED',
        elapsedMs: 2000,
        output: '写入 sort.js\n完成',
        modelCalls: 1,
        toolCalls: 1,
        files: ['sort.js']
      },
      target: {
        status: 'FAILED',
        elapsedMs: 1200,
        output: '写入 sort.js\n失败：缺少依赖',
        modelCalls: 2,
        toolCalls: 2,
        files: ['sort.js']
      }
    }
  ],
  onlyInBase: ['style.css'],
  onlyInTarget: [],
  limitations: [
    '历史上没有保存交付文件的逐版本内容，因此这里对照的是声明写入范围与节点产出，而不是代码逐行 diff。'
  ]
};

const renderCompare = props => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <RunCompareDiffView runId="aaaaaaaa-1111" {...props} />
    </QueryClientProvider>
  );
};

describe('diffLines', () => {
  it('相同行保持相等、左侧独有标记删除、右侧独有标记新增', () => {
    const rows = diffLines('a\nb\nc', 'a\nc\nd');
    expect(rows.filter(row => row.kind === 'same').map(row => row.left)).toEqual(['a', 'c']);
    expect(rows.some(row => row.kind === 'removed' && row.left === 'b')).toBe(true);
    expect(rows.some(row => row.kind === 'added' && row.right === 'd')).toBe(true);
  });

  it('完全相同时只有相同行', () => {
    expect(diffLines('x\ny', 'x\ny').every(row => row.kind === 'same')).toBe(true);
  });

  it('一侧为空时全部按新增或删除处理，不抛异常', () => {
    expect(diffLines('', 'a').some(row => row.kind === 'added')).toBe(true);
    expect(diffLines('a', '').some(row => row.kind === 'removed')).toBe(true);
  });
});

describe('CodeDiffView', () => {
  beforeEach(() => setModuleLocale('zh-CN'));

  it('并排渲染两侧并标记差异行', () => {
    render(<CodeDiffView left={'a\nb'} right={'a\nc'} leftLabel="旧" rightLabel="新" />);
    expect(screen.getByTestId('code-diff')).toBeInTheDocument();
    expect(screen.getByText('旧')).toBeInTheDocument();
    expect(screen.getByText('新')).toBeInTheDocument();
    expect(document.querySelectorAll('[data-diff-row="removed"]').length).toBe(1);
    expect(document.querySelectorAll('[data-diff-row="added"]').length).toBe(1);
  });
});

describe('RunCompareDiffView', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
    vi.spyOn(runsApi, 'diff').mockResolvedValue(diff);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('没有分支时给出可操作的空状态', () => {
    renderCompare({ candidates: [] });
    expect(screen.getByTestId('compare-empty')).toBeInTheDocument();
    expect(runsApi.diff).not.toHaveBeenCalled();
  });

  it('对照两条运行的指标与逐节点产出，并显式写出文件内容限制', async () => {
    renderCompare({ candidates: [{ runId: 'bbbbbbbb-2222', label: 'implement' }] });

    expect(await screen.findByTestId('run-compare-diff')).toBeInTheDocument();
    await waitFor(() =>
      expect(runsApi.diff).toHaveBeenCalledWith('aaaaaaaa-1111', 'bbbbbbbb-2222')
    );

    expect(screen.getAllByTestId('diff-base')[0]).toHaveTextContent('COMPLETED');
    expect(screen.getAllByTestId('diff-target')[0]).toHaveTextContent('FAILED');
    expect(screen.getByTestId('compare-node-implement')).toBeInTheDocument();
    expect(screen.getByTestId('node-diff-implement')).toBeInTheDocument();
    expect(screen.getByText(/而不是代码逐行 diff/)).toBeInTheDocument();
  });

  it('可以切换对照的分支并打开它', async () => {
    const onOpenRun = vi.fn();
    renderCompare({
      candidates: [
        { runId: 'bbbbbbbb-2222', label: 'implement' },
        { runId: 'cccccccc-3333', label: 'review' }
      ],
      onOpenRun
    });
    await screen.findByTestId('run-compare-diff');
    fireEvent.click(screen.getByText('打开这条分支'));
    expect(onOpenRun).toHaveBeenCalledWith('bbbbbbbb-2222');
  });
});
