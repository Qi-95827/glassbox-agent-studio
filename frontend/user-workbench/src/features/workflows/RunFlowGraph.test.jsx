import { describe, expect, it } from 'vitest';
import { edgeSelection } from './RunFlowGraph';

const dependency = { fromNodeId: 'analyze', toNodeId: 'write' };
const revisionEdge = {
  reviewerNodeId: 'review',
  targetNodeId: 'write',
  revisionCount: 1,
  maxRevisions: 2,
  lastVerdictJson: '{"decision":"REVISE_REQUIRED"}'
};

describe('RunFlowGraph 边点击载荷契约', () => {
  it('依赖边返回依赖投影，反思回边返回结构化回边且不带依赖字段', () => {
    expect(edgeSelection({ data: { edge: dependency } })).toEqual({
      revisionEdge: null,
      edge: dependency
    });

    const picked = edgeSelection({ data: { revisionEdge } });
    expect(picked.revisionEdge).toBe(revisionEdge);
    // 关键回归：反思回边没有 data.edge，绝不能返回 undefined 让页面去读 toNodeId 而抛 TypeError。
    expect(picked.edge).toBeNull();
  });

  it('空载荷不会伪造依赖或回边', () => {
    expect(edgeSelection(undefined)).toEqual({ revisionEdge: null, edge: null });
    expect(edgeSelection({ data: {} })).toEqual({ revisionEdge: null, edge: null });
  });
});
