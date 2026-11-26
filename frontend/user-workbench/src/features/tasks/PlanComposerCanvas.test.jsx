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

const renderComposer = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PlanComposerCanvas candidate={candidate} />
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

  it('渲染原始节点并标注边界：本地演练不修改计划', async () => {
    renderComposer();
    expect(await screen.findByTestId('plan-composer')).toBeInTheDocument();
    expect(screen.getByTestId('composer-node-analyze')).toBeInTheDocument();
    expect(screen.getByTestId('composer-node-implement')).toBeInTheDocument();
    expect(screen.getByText(/不会修改服务端计划/)).toBeInTheDocument();
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
