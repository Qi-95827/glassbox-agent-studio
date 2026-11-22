import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { DonutChart, GaugeArc, TrendChart } from './MonitoringCharts';

describe('监控看板轻量 SVG 图表', () => {
  it('渲染成功率仪表盘且对异常值做边界收敛', () => {
    const { rerender } = render(<GaugeArc value={82.4} label="任务成功率" />);
    expect(screen.getByText('82.4%')).toBeInTheDocument();
    expect(screen.getByLabelText(/任务成功率 82.4%/)).toBeInTheDocument();

    rerender(<GaugeArc value={180} label="任务成功率" />);
    expect(screen.getByText('100.0%')).toBeInTheDocument();
  });

  it('趋势图在空数据与正常分桶下都不会出现 NaN/Infinity', () => {
    const { rerender } = render(<TrendChart points={[]} />);
    expect(screen.getByText('该时间段暂无趋势样本。')).toBeInTheDocument();

    rerender(
      <TrendChart
        metric="runs"
        points={[
          {
            bucketLabel: '11-21',
            runCount: 3,
            successCount: 2,
            failedCount: 1,
            avgDurationMs: 1200,
            totalTokens: 300
          },
          {
            bucketLabel: '11-22',
            runCount: 1,
            successCount: 1,
            failedCount: 0,
            avgDurationMs: null,
            totalTokens: 80
          }
        ]}
      />
    );
    expect(screen.getByLabelText('运行趋势')).toBeInTheDocument();
    expect(screen.getByText('11-21')).toBeInTheDocument();
    expect(screen.queryByText(/NaN|Infinity/)).toBeNull();

    const chart = screen.getByLabelText('运行趋势');
    const hoverArea = chart.querySelectorAll('rect');
    fireEvent.mouseEnter(hoverArea[hoverArea.length - 1]);
    expect(screen.getByText(/运行 1 · 成功 1 · 失败 0/)).toBeInTheDocument();
  });

  it('圆盘图展示占比与图例，空分布给出优雅提示', () => {
    const { rerender } = render(
      <DonutChart
        slices={[
          { key: 'COMPLETED', label: '已完成', value: 3 },
          { key: 'FAILED', label: '失败', value: 1 }
        ]}
        totalLabel="运行"
      />
    );
    expect(screen.getByText('已完成')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.getByText(/75.0%/)).toBeInTheDocument();

    rerender(<DonutChart slices={[]} />);
    expect(screen.getByText('暂无分布数据。')).toBeInTheDocument();
  });
});
