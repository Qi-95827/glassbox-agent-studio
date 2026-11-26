import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ContextMicroscopePanel, { buildWaterLevelSeries } from './ContextMicroscopePanel';
import { contextArtifactsApi } from '../../../api/contextArtifacts';
import { setModuleLocale } from '../../../i18n';

const contextOf = overrides => ({
  modelContextLimit: 1000,
  beforeTokens: 900,
  totalEstimatedTokens: 500,
  segmentTokens: { SYSTEM: 300, HISTORY: 200 },
  appliedTier: 'L1_TOOL_OFFLOAD',
  savedTokens: 400,
  savedPercent: 44,
  artifactRefs: [],
  offloadedToolCallIds: [],
  summaryPreview: '',
  retainedExcerptPreview: '',
  structuredAnchors: '',
  detail: {
    offloads: [],
    mergedFrom: -1,
    mergedTo: -1,
    mergedMessageCount: 0,
    protectedToolCallIds: []
  },
  ...overrides
});

const models = [
  { id: 'm1', name: 'deepseek-flash', payload: { context: contextOf() } },
  {
    id: 'm2',
    name: 'deepseek-flash',
    payload: {
      context: contextOf({
        segmentTokens: { SYSTEM: 300, HISTORY: 400, TOOL_OUTPUTS: 200 },
        appliedTier: 'L2_SLIDING_SUMMARY',
        summaryPreview: '已把中间三轮压成四段锚点。',
        detail: {
          offloads: [
            {
              toolCallId: 'call-1',
              artifactRef: 'art-abc123',
              originalChars: 5000,
              retainedChars: 800
            }
          ],
          mergedFrom: 1,
          mergedTo: 6,
          mergedMessageCount: 5,
          protectedToolCallIds: ['call-2', 'call-3']
        }
      })
    }
  }
];

const wrapper = client =>
  function Wrapper({ children }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };

const renderPanel = (props = { models }) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<ContextMicroscopePanel {...props} />, { wrapper: wrapper(client) });
};

describe('buildWaterLevelSeries', () => {
  it('只保留带上下文快照的调用，并计算占窗口比例', () => {
    const series = buildWaterLevelSeries([
      { id: 'a', payload: { context: contextOf() } },
      { id: 'b', payload: null },
      { id: 'c', payload: { context: contextOf({ segmentTokens: { HISTORY: 1200 } }) } }
    ]);
    expect(series).toHaveLength(2);
    expect(series[0].total).toBe(500);
    expect(series[0].ratio).toBeCloseTo(0.5);
    // 超限时比例被夹住，避免把图画出画布外。
    expect(series[1].ratio).toBeLessThanOrEqual(1.35);
  });

  it('没有可用快照时返回空序列', () => {
    expect(buildWaterLevelSeries([{ id: 'a' }])).toEqual([]);
    expect(buildWaterLevelSeries(null)).toEqual([]);
  });
});

describe('ContextMicroscopePanel', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('渲染六层堆叠面积图与三条阈值线', () => {
    renderPanel();
    expect(screen.getByTestId('water-level-chart')).toBeInTheDocument();
    expect(screen.getByTestId('segment-band-SYSTEM')).toBeInTheDocument();
    expect(screen.getByTestId('segment-band-HISTORY')).toBeInTheDocument();
    expect(screen.getByTestId('threshold-L1')).toBeInTheDocument();
    expect(screen.getByTestId('threshold-L2')).toBeInTheDocument();
    expect(screen.getByTestId('threshold-L3')).toBeInTheDocument();
  });

  it('展示最后一次调用的 L1 / L2 / L3 压缩明细', () => {
    renderPanel();
    expect(screen.getByTestId('microscope-offloads')).toHaveTextContent('call-1');
    expect(screen.getByText(/5000 字符 → 原位保留 800 字符/)).toBeInTheDocument();
    expect(screen.getByTestId('microscope-l2')).toHaveTextContent('#1');
    expect(screen.getByTestId('microscope-l2')).toHaveTextContent('共 5 条');
    expect(screen.getByTestId('microscope-l3')).toHaveTextContent('call-2');
  });

  it('点击产物引用时按引用拉取真实原文', async () => {
    const spy = vi
      .spyOn(contextArtifactsApi, 'read')
      .mockResolvedValue({ charCount: 5000, title: '工具输出', content: '真实产物正文' });
    renderPanel();

    fireEvent.click(screen.getByTestId('artifact-open-art-abc123'));

    await waitFor(() => expect(spy).toHaveBeenCalledWith('art-abc123'));
    expect(await screen.findByText('真实产物正文')).toBeInTheDocument();
  });

  it('没有上下文快照时明确提示而不是画空图', () => {
    renderPanel({ models: [{ id: 'm1', payload: null }] });
    expect(screen.getByTestId('microscope-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('water-level-chart')).toBeNull();
  });
});
