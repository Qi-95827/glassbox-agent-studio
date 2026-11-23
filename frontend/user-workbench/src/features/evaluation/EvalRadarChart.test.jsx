import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import EvalRadarChart from './EvalRadarChart';

const categories = [
  { code: 'CODING_REFLECTION', label: 'Coding' },
  { code: 'TOOL_MCP', label: 'Tools' },
  { code: 'CONTEXT_COMPACTION', label: 'Compaction' },
  { code: 'LONG_TERM_MEMORY', label: 'Memory' },
  { code: 'PLANNING_MULTI_AGENT', label: 'Planning' },
  { code: 'SAFETY_INJECTION', label: 'Safety' }
];

describe('纯 SVG 六维能力雷达', () => {
  it('渲染六个维度并对越界分数做 0–100 收敛', () => {
    render(
      <EvalRadarChart
        categories={categories}
        ariaLabel="能力雷达"
        series={[
          {
            key: 'a',
            label: 'A',
            values: {
              CODING_REFLECTION: 150,
              TOOL_MCP: -20,
              CONTEXT_COMPACTION: 80,
              LONG_TERM_MEMORY: 60,
              PLANNING_MULTI_AGENT: 40,
              SAFETY_INJECTION: 100
            }
          }
        ]}
      />
    );
    expect(screen.getByLabelText('能力雷达')).toBeInTheDocument();
    categories.forEach(category => expect(screen.getByText(category.label)).toBeInTheDocument());
    expect(screen.queryByText(/NaN|Infinity/)).toBeNull();
  });

  it('A/B 双图层叠加并在悬停时显示精确百分比与样本数', () => {
    render(
      <EvalRadarChart
        categories={categories}
        ariaLabel="能力雷达对比"
        series={[
          {
            key: 'a',
            label: 'Baseline',
            color: '#1A73E8',
            values: Object.fromEntries(categories.map(category => [category.code, 50])),
            sampleCounts: Object.fromEntries(categories.map(category => [category.code, 4]))
          },
          {
            key: 'b',
            label: 'Candidate',
            color: '#B3261E',
            values: Object.fromEntries(categories.map(category => [category.code, 75])),
            sampleCounts: Object.fromEntries(categories.map(category => [category.code, 6]))
          }
        ]}
      />
    );
    expect(screen.getByText('Baseline')).toBeInTheDocument();
    expect(screen.getByText('Candidate')).toBeInTheDocument();
    const dot = document.querySelector('svg circle[r="3"]');
    expect(dot).not.toBeNull();
    fireEvent.mouseEnter(dot);
    expect(screen.getByText(/Baseline 50\.0% \(4\)/)).toBeInTheDocument();
    expect(screen.getByText(/Candidate 75\.0% \(6\)/)).toBeInTheDocument();
  });
});
