import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import ContextMeterBar from './ContextMeterBar';

describe('ContextMeterBar', () => {
  it('展示压缩等级、分段 Token 与节省比例', () => {
    render(
      <ContextMeterBar
        context={{
          modelContextLimit: 1000,
          beforeTokens: 800,
          totalEstimatedTokens: 520,
          savedPercent: 35,
          appliedTier: 'L1_TOOL_OFFLOAD',
          segmentTokens: { SYSTEM: 120, TOOL_OUTPUTS: 300, HISTORY: 100 },
          offloadedToolCallIds: ['tool-1'],
          summaryPreview: '已确认事实：测试通过'
        }}
      />
    );
    expect(screen.getByText('L1 · 工具输出卸载')).toBeInTheDocument();
    expect(screen.getByText(/上限 1000 tokens/)).toBeInTheDocument();
    expect(screen.getByText(/节省 35%/)).toBeInTheDocument();
    expect(screen.getByText(/工具结果 300/)).toBeInTheDocument();
    expect(screen.getByText(/已卸载 1 条大体积工具结果/)).toBeInTheDocument();
  });

  it('没有上下文快照时不渲染', () => {
    const { container } = render(<ContextMeterBar context={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});
