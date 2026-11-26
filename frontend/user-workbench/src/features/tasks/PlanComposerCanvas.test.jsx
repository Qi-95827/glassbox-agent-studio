import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import PlanComposerCanvas, { draftFromCandidate } from './PlanComposerCanvas';
import { plansApi } from '../../api/plans';
import { setModuleLocale } from '../../i18n';

const candidate = {
  schemaVersion: 1,
  nodes: [
    {
      key: 'analyze',
      title: '分析授权文件',
      role: 'ANALYZE',
      objective: '读完再定方案',
      dependsOn: [],
      readFiles: ['src/App.jsx'],
      writeFiles: []
    },
    {
      key: 'implement',
      title: '按授权修改',
      role: 'IMPLEMENT',
      objective: '改代码',
      dependsOn: ['analyze'],
      readFiles: ['src/App.jsx'],
      writeFiles: ['src/App.jsx']
    }
  ],
  revisionEdges: [{ fromReviewerNodeKey: 'implement', toTargetNodeKey: 'analyze', maxRevisions: 2 }]
};

const renderComposer = (props = {}) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PlanComposerCanvas candidate={candidate} {...props} />
    </QueryClientProvider>
  );
};

describe('draftFromCandidate', () => {
  it('把服务端候选投影成可编辑草稿，并保留反思回边', () => {
    const draft = draftFromCandidate(candidate);
    expect(draft.nodes).toHaveLength(2);
    expect(draft.nodes[1].dependsOn).toEqual(['analyze']);
    // 兼容后端字段名（fromReviewerNodeKey/toTargetNodeKey）与旧前端字段名。
    expect(draft.revisionEdges[0]).toEqual({
      fromReviewerNodeKey: 'implement',
      toTargetNodeKey: 'analyze',
      maxRevisions: 2
    });
    expect(draftFromCandidate({ nodes: [] }).revisionEdges).toEqual([]);
  });
});

describe('PlanComposerCanvas', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
    vi.spyOn(plansApi, 'validateDraft').mockResolvedValue({
      valid: true,
      errors: [],
      conflicts: [],
      conflictingNodeKeys: []
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('渲染原始节点；没有应用回调时只做校验、不出现落库按钮', async () => {
    renderComposer();
    expect(await screen.findByTestId('plan-composer')).toBeInTheDocument();
    expect(screen.getByTestId('composer-node-analyze')).toBeInTheDocument();
    expect(screen.getByTestId('composer-node-implement')).toBeInTheDocument();
    expect(screen.getByText(/改动只有点「应用画布到计划」才会写回候选/)).toBeInTheDocument();
    expect(screen.queryByTestId('composer-apply')).not.toBeInTheDocument();
  });

  it('新增节点后节点数增加并立即触发一次真实校验', async () => {
    renderComposer();
    await screen.findByTestId('composer-node-analyze');

    fireEvent.click(screen.getByRole('button', { name: '加审查节点' }));

    await waitFor(() => expect(screen.getByTestId('composer-node-review-3')).toBeInTheDocument());
    await waitFor(() => expect(plansApi.validateDraft).toHaveBeenCalled());
    const payload = plansApi.validateDraft.mock.calls.at(-1)[0];
    expect(payload.nodes.map(node => node.key)).toContain('review-3');
  });

  it('服务端返回写集冲突时把冲突码与重叠文件挂到对应节点上', async () => {
    plansApi.validateDraft.mockResolvedValue({
      valid: false,
      errors: [],
      conflicts: [
        {
          code: 'WRITE_CONFLICT',
          nodeKeys: ['analyze', 'implement'],
          paths: ['src/app.jsx'],
          detail: ''
        }
      ],
      conflictingNodeKeys: ['analyze', 'implement'],
      byNodeKey: {
        analyze: [
          {
            code: 'WRITE_CONFLICT',
            nodeKeys: ['analyze', 'implement'],
            paths: ['src/app.jsx'],
            detail: ''
          }
        ],
        implement: [
          {
            code: 'WRITE_CONFLICT',
            nodeKeys: ['analyze', 'implement'],
            paths: ['src/app.jsx'],
            detail: ''
          }
        ]
      }
    });

    renderComposer();

    const node = await screen.findByTestId('composer-node-analyze');
    await waitFor(() => expect(node).toHaveAttribute('data-conflict', 'WRITE_CONFLICT'));
    // 冲突会同时挂在两个节点上。
    expect(screen.getAllByTestId('composer-conflict-WRITE_CONFLICT').length).toBeGreaterThan(1);
    expect(screen.getByTestId('composer-verdict')).toHaveTextContent('1 处冲突');
    expect(screen.getByTestId('composer-conflicts')).toHaveTextContent('src/app.jsx');
  });

  it('成环时给出环上的节点键，而不是一句泛化错误', async () => {
    plansApi.validateDraft.mockResolvedValue({
      valid: false,
      errors: [],
      conflicts: [{ code: 'CYCLE', nodeKeys: ['analyze', 'implement'], paths: [], detail: '' }],
      conflictingNodeKeys: ['analyze', 'implement'],
      byNodeKey: {
        analyze: [{ code: 'CYCLE', nodeKeys: ['analyze', 'implement'], paths: [], detail: '' }],
        implement: [{ code: 'CYCLE', nodeKeys: ['analyze', 'implement'], paths: [], detail: '' }]
      }
    });

    renderComposer();

    const verdict = await screen.findByTestId('composer-verdict');
    await waitFor(() => expect(verdict).toHaveTextContent('1 处冲突'));
    expect(screen.getAllByTestId('composer-conflict-CYCLE').length).toBeGreaterThan(0);
    expect(screen.getByTestId('composer-conflicts')).toHaveTextContent('analyze / implement');
  });

  it('选中节点后可修改写入集合，改动会进入下一次校验请求', async () => {
    renderComposer();
    const node = await screen.findByTestId('composer-node-implement');
    fireEvent.click(node);

    const writeField = await screen.findByLabelText('写入文件（每行一个）');
    fireEvent.change(writeField, { target: { value: 'src/App.jsx\nsrc/extra.js' } });

    await waitFor(() => {
      const payload = plansApi.validateDraft.mock.calls.at(-1)?.[0];
      const implement = payload?.nodes?.find(item => item.key === 'implement');
      return implement?.writeFiles.includes('src/extra.js');
    });
  });
});

describe('PlanComposerCanvas 应用闭环', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
    vi.spyOn(plansApi, 'validateDraft').mockResolvedValue({
      valid: true,
      errors: [],
      conflicts: [],
      conflictingNodeKeys: []
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('校验通过后点「应用画布到计划」把草稿交给回调，并展示新摘要', async () => {
    const onApply = vi.fn().mockResolvedValue({
      applied: true,
      errors: [],
      candidate: { candidateSha256: 'abcdef123456'.padEnd(64, '0') }
    });
    renderComposer({ onApply });

    const apply = await screen.findByTestId('composer-apply');
    await waitFor(() => expect(apply).toBeEnabled());
    fireEvent.click(apply);

    await waitFor(() => expect(onApply).toHaveBeenCalledTimes(1));
    const draft = onApply.mock.calls[0][0];
    expect(draft.nodes.map(node => node.key)).toEqual(['analyze', 'implement']);
    // 反思回边与依赖都要原样带回，否则应用一次就会丢掉画布上看到的结构。
    expect(draft.revisionEdges[0].toTargetNodeKey).toBe('analyze');
    expect(draft.nodes[1].dependsOn).toEqual(['analyze']);
    expect(await screen.findByTestId('composer-applied')).toHaveTextContent('abcdef123456');
  });

  it('服务端拒绝时不显示成功，逐条列出拒绝原因', async () => {
    const onApply = vi.fn().mockResolvedValue({
      applied: false,
      errors: ['候选当前状态为 CONSUMED，不能修改。'],
      candidate: null
    });
    renderComposer({ onApply });

    const apply = await screen.findByTestId('composer-apply');
    await waitFor(() => expect(apply).toBeEnabled());
    fireEvent.click(apply);

    expect(await screen.findByTestId('composer-apply-errors')).toHaveTextContent('CONSUMED');
    expect(screen.queryByTestId('composer-applied')).not.toBeInTheDocument();
  });

  it('回调抛出时把消息展示出来，不伪装成已应用', async () => {
    const onApply = vi.fn().mockRejectedValue(new Error('候选已被确认或过期，请刷新后重新修改。'));
    renderComposer({ onApply });

    const apply = await screen.findByTestId('composer-apply');
    await waitFor(() => expect(apply).toBeEnabled());
    fireEvent.click(apply);

    expect(await screen.findByTestId('composer-apply-errors')).toHaveTextContent(
      '候选已被确认或过期'
    );
    expect(screen.queryByTestId('composer-applied')).not.toBeInTheDocument();
  });

  it('结构校验未通过时应用按钮保持禁用，点击不会落库', async () => {
    plansApi.validateDraft.mockResolvedValue({
      valid: false,
      errors: [],
      conflicts: [{ code: 'CYCLE', nodeKeys: ['analyze', 'implement'], paths: [], detail: '' }],
      conflictingNodeKeys: ['analyze', 'implement']
    });
    const onApply = vi.fn();
    renderComposer({ onApply });

    const apply = await screen.findByTestId('composer-apply');
    await waitFor(() => expect(apply).toBeDisabled());
    fireEvent.click(apply);
    expect(onApply).not.toHaveBeenCalled();
  });
});
