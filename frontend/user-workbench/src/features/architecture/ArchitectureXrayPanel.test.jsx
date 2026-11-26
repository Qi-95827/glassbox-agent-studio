import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ArchitectureXrayPanel from './ArchitectureXrayPanel';
import { runsApi } from '../../api/runs';
import { tasksApi } from '../../api/tasks';
import { setModuleLocale } from '../../i18n';

const xray = {
  schemaVersion: 1,
  runId: 'run-1',
  summary: {
    status: 'COMPLETED',
    activeModules: 3,
    modelCalls: 2,
    toolCalls: 3,
    handoffs: 0,
    checkpoints: 1,
    inputTokens: 120,
    outputTokens: 80
  },
  modules: [
    {
      id: 'run-lifecycle',
      status: 'ACTIVE',
      callCount: 2,
      durationMs: 2000,
      compactionSavedPercent: null,
      gateBlocks: 0,
      anchorNodes: ['n1']
    },
    {
      id: 'tool-gateway',
      status: 'ACTIVE',
      callCount: 3,
      durationMs: 900,
      compactionSavedPercent: null,
      gateBlocks: 1,
      anchorNodes: ['n2']
    },
    {
      id: 'model-byok',
      status: 'ACTIVE',
      callCount: 2,
      durationMs: 500,
      compactionSavedPercent: 42,
      gateBlocks: 0,
      anchorNodes: ['n1']
    },
    {
      id: 'handoff-envelope',
      status: 'IDLE',
      callCount: 0,
      durationMs: 0,
      compactionSavedPercent: null,
      gateBlocks: 0,
      anchorNodes: []
    }
  ],
  steps: [
    {
      id: 'node:n1',
      atMs: 1000,
      kind: 'NODE',
      label: '分析',
      moduleId: 'run-lifecycle',
      nodeId: 'n1',
      glassboxTab: 'result'
    },
    {
      id: 'tool:t1',
      atMs: 2000,
      kind: 'TOOL',
      label: 'workspace_write',
      moduleId: 'tool-gateway',
      nodeId: 'n2',
      glassboxTab: 'gateway'
    }
  ],
  limitations: [
    '模块指标来自已落库的节点、尝试、模型、工具、审批与检查点记录；未走到的模块显示为空闲，不代表它不存在。'
  ]
};

const renderPanel = props => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ArchitectureXrayPanel {...props} />
    </QueryClientProvider>
  );
};

describe('ArchitectureXrayPanel', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
    vi.spyOn(runsApi, 'architectureXray').mockResolvedValue(xray);
    vi.spyOn(tasksApi, 'list').mockResolvedValue([
      { runId: 'run-recent-1', goal: '把排序网页做出来' },
      { runId: 'run-recent-2', goal: '补一个回归测试' }
    ]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('加载运行后按真实状态点亮模块，空闲模块保持空闲', async () => {
    renderPanel({ initialRunId: 'run-1' });
    expect(await screen.findByTestId('xray-module-run-lifecycle')).toHaveAttribute(
      'data-status',
      'ACTIVE'
    );
    expect(screen.getByTestId('xray-module-handoff-envelope')).toHaveAttribute(
      'data-status',
      'IDLE'
    );
    expect(runsApi.architectureXray).toHaveBeenCalledWith('run-1');
  });

  it('展示压缩节省率与安检拦截数，而不是只显示调用次数', async () => {
    renderPanel({ initialRunId: 'run-1' });
    await screen.findByTestId('xray-module-model-byok');
    expect(screen.getByText('上下文压缩节省 42%')).toBeInTheDocument();
    expect(screen.getByTestId('xray-blocks')).toHaveTextContent('安检拦截 1 次');
  });

  it('点击证据锚点跳回运行详情并带上节点与玻璃盒 Tab', async () => {
    const onOpenRun = vi.fn();
    renderPanel({ initialRunId: 'run-1', onOpenRun });
    const anchor = await screen.findByTestId('xray-anchor-tool-gateway');
    fireEvent.click(anchor);
    expect(onOpenRun).toHaveBeenCalledWith('run-1', 'n2', 'gateway', 'tool-gateway');
  });

  it('锚点按模块精准映射玻璃盒 Tab，而不是跟着当前回放步骤漂移', async () => {
    const onOpenRun = vi.fn();
    renderPanel({ initialRunId: 'run-1', onOpenRun });
    // 锚点落点由模块决定，与回放进度无关：模型模块永远落到 llm。
    const anchor = await screen.findByTestId('xray-anchor-model-byok');
    fireEvent.click(anchor);
    expect(onOpenRun).toHaveBeenCalledWith('run-1', 'n1', 'llm', 'model-byok');
  });

  it('可以从最近运行下拉里直接选一条运行加载', async () => {
    renderPanel();
    const select = await screen.findByTestId('xray-recent-runs');
    fireEvent.mouseDown(select.querySelector('[role="combobox"], input') ?? select);
    fireEvent.click(await screen.findByText('把排序网页做出来'));
    await waitFor(() => expect(runsApi.architectureXray).toHaveBeenCalledWith('run-recent-1'));
  });

  it('步进回放会裁剪已发生的步骤并打开当前步骤', async () => {
    const onOpenRun = vi.fn();
    renderPanel({ initialRunId: 'run-1', onOpenRun });
    await screen.findByTestId('xray-replay');
    expect(screen.getByText(/回放步骤 2\/2/)).toBeInTheDocument();

    // 回退一步后只剩节点步骤，此时「打开当前步骤」应指向节点而不是工具。
    fireEvent.keyDown(screen.getByRole('slider'), { key: 'ArrowLeft' });
    await waitFor(() => expect(screen.getByText(/回放步骤 1\/2/)).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('xray-open-step'));
    expect(onOpenRun).toHaveBeenCalledWith('run-1', 'n1', 'result', 'run-lifecycle');
  });

  it('没有运行 ID 时不发请求，只显示输入与加载按钮', () => {
    renderPanel();
    expect(runsApi.architectureXray).not.toHaveBeenCalled();
    expect(screen.getByTestId('xray-run-input')).toBeInTheDocument();
  });
});
