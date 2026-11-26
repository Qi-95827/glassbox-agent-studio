import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MemoryAndChunksPanel from './MemoryAndChunksPanel';
import { memoryApi } from '../../api/memory';
import { ToastProvider } from '../../components/ToastProvider';
import { setModuleLocale } from '../../i18n';

const memories = [
  {
    id: 'mem-1',
    memoryType: 'SEMANTIC',
    status: 'ACTIVE',
    content: '项目统一使用 M3 主题令牌。',
    version: 3,
    hitCount: 7,
    sourceRunId: 'run-abcdef12',
    updatedAt: '2026-11-26T10:00:00Z'
  },
  {
    id: 'mem-2',
    memoryType: 'EPISODIC',
    status: 'CANDIDATE',
    content: '上次越界写入被 Gate 5 拦截。',
    version: 1,
    hitCount: 0,
    updatedAt: '2026-11-26T11:00:00Z'
  }
];

const documents = [{ id: 'doc-1', title: '排序设计', chunkCount: 2, sourcePath: 'docs/sort.md' }];

const chunks = [
  {
    index: 0,
    sectionTitle: '边界处理',
    tokenEstimate: 40,
    charCount: 160,
    preview: '数组越界需要先校验长度。',
    previewTruncated: false
  },
  {
    index: 1,
    sectionTitle: '性能',
    tokenEstimate: 55,
    charCount: 900,
    preview: '大数据量下使用归并排序……',
    previewTruncated: true
  }
];

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <MemoryAndChunksPanel workspaceId="ws-1" />
      </ToastProvider>
    </QueryClientProvider>
  );
}

describe('MemoryAndChunksPanel', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
    vi.spyOn(memoryApi, 'memories').mockResolvedValue(memories);
    vi.spyOn(memoryApi, 'knowledgeDocuments').mockResolvedValue(documents);
    vi.spyOn(memoryApi, 'knowledgeChunks').mockResolvedValue(chunks);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('按三层记忆分组展示条目与命中次数', async () => {
    renderPanel();
    expect(await screen.findByTestId('memory-row-mem-1')).toBeInTheDocument();
    expect(screen.getByTestId('memory-row-mem-2')).toBeInTheDocument();
    // 语义层与情节层各自的分组标题都应出现。
    expect(screen.getByText(/语义记忆/)).toBeInTheDocument();
    expect(screen.getByText(/情景经验/)).toBeInTheDocument();
    expect(screen.getByText('命中 7 次')).toBeInTheDocument();
  });

  it('冻结操作带上版本号回调服务端，防止并发覆盖', async () => {
    const freeze = vi.spyOn(memoryApi, 'ignoreMemory').mockResolvedValue({});
    renderPanel();
    const row = await screen.findByTestId('memory-row-mem-1');

    // 两条记忆各有一个「冻结」按钮：点第一条（mem-1）。
    fireEvent.click(screen.getAllByRole('button', { name: '冻结' })[0]);

    await waitFor(() => expect(freeze).toHaveBeenCalledTimes(1));
    // 冻结必须带上服务端的当前版本号，否则并发修改会被静默覆盖。
    expect(freeze.mock.calls[0][1]).toMatch(/^mem-/);
    expect(freeze.mock.calls[0][2]).toBeGreaterThan(0);
    expect(row).toBeInTheDocument();
  });

  it('放行操作调用 accept 接口', async () => {
    const accept = vi.spyOn(memoryApi, 'acceptMemory').mockResolvedValue({});
    renderPanel();
    await screen.findByTestId('memory-row-mem-2');

    fireEvent.click(screen.getAllByRole('button', { name: '放行' })[1]);

    await waitFor(() => expect(accept).toHaveBeenCalledWith('ws-1', 'mem-2', 1));
  });

  it('切换到代码切片后按文档展开分块边界', async () => {
    renderPanel();
    await screen.findByTestId('memory-row-mem-1');

    fireEvent.click(screen.getByRole('tab', { name: '代码切片' }));
    fireEvent.click(await screen.findByRole('button', { name: '查看切片' }));

    await waitFor(() => expect(memoryApi.knowledgeChunks).toHaveBeenCalledWith('ws-1', 'doc-1'));
    expect(await screen.findByTestId('chunk-list-doc-1')).toHaveTextContent('边界处理');
    expect(screen.getByText('预览已截断')).toBeInTheDocument();
  });
});
