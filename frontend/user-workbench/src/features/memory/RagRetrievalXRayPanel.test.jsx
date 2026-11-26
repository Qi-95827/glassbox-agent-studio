import { describe, expect, it, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import RagRetrievalXRayPanel from './RagRetrievalXRayPanel';
import { setModuleLocale } from '../../i18n';

const evidence = {
  query: '排序边界',
  vectorEnabled: true,
  selectedCount: 1,
  injectedTokens: 42,
  items: [
    {
      chunkId: 'c-1',
      documentId: 'd-1',
      title: '排序设计',
      sourcePath: 'docs/sort.md',
      sectionTitle: '边界处理',
      content: '数组越界需要先校验长度。',
      tokenEstimate: 42,
      bm25: 1.8,
      vector: 0.61,
      rrf: 0.033,
      score: 0.44,
      selected: true,
      droppedReason: ''
    },
    {
      chunkId: 'c-2',
      documentId: 'd-1',
      title: '排序设计',
      sourcePath: 'docs/sort.md',
      sectionTitle: '性能',
      content: '大数据量下使用归并排序。',
      tokenEstimate: 37,
      bm25: 0.02,
      vector: 0.05,
      rrf: 0.001,
      score: 0.004,
      selected: false,
      droppedReason: 'BELOW_THRESHOLD'
    },
    {
      chunkId: 'c-3',
      documentId: 'd-1',
      title: '排序设计',
      sourcePath: 'docs/sort.md',
      sectionTitle: '测试',
      content: '补充随机用例。',
      tokenEstimate: 20,
      bm25: 0.9,
      vector: 0.3,
      rrf: 0.02,
      score: 0.2,
      selected: false,
      droppedReason: 'OVER_LIMIT'
    }
  ]
};

describe('RagRetrievalXRayPanel', () => {
  beforeEach(() => setModuleLocale('zh-CN'));

  it('分别渲染词法 Top-K 与向量 Top-K', () => {
    render(<RagRetrievalXRayPanel evidence={evidence} />);
    expect(screen.getByTestId('xray-lexical')).toHaveTextContent('边界处理');
    // 只有向量分大于 0 的候选出现在向量列；c-2 的 bm25 极低但仍在词法列。
    expect(screen.getByTestId('xray-vector')).toHaveTextContent('边界处理');
  });

  it('融合列表区分注入与裁剪，并给出裁剪原因', () => {
    render(<RagRetrievalXRayPanel evidence={evidence} />);
    expect(screen.getByTestId('xray-fused')).toHaveTextContent('边界处理');
    expect(screen.getByText('相关度低于阈值')).toBeInTheDocument();
    expect(screen.getByText('超出注入条数上限')).toBeInTheDocument();
    expect(screen.getAllByTestId('xray-injected')).toHaveLength(1);
    expect(screen.getAllByTestId('xray-dropped')).toHaveLength(2);
  });

  it('向量通道未启用时明确标注只有词法', () => {
    render(<RagRetrievalXRayPanel evidence={{ ...evidence, vectorEnabled: false }} />);
    expect(screen.getByText('仅词法通道（未配置 Embedding）')).toBeInTheDocument();
  });

  it('没有检索结果时给出引导而不是空面板', () => {
    render(<RagRetrievalXRayPanel evidence={null} />);
    expect(screen.getByTestId('xray-empty')).toBeInTheDocument();
  });

  it('候选为空时区分「没有候选」与「还没检索」', () => {
    render(<RagRetrievalXRayPanel evidence={{ ...evidence, items: [] }} />);
    expect(screen.getByText('本次检索没有任何候选分块。')).toBeInTheDocument();
  });
});
