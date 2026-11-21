import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import StatusChip from './StatusChip';

describe('StatusChip', () => {
  it('把运行状态映射为中文语义标签', () => {
    render(<StatusChip value="RUNNING" />);
    expect(screen.getByText('运行中')).toBeInTheDocument();
  });

  it('已完成显示对勾图标与自定义标签', () => {
    render(<StatusChip value="COMPLETED" label="写入成功" />);
    expect(screen.getByText('写入成功')).toBeInTheDocument();
  });

  it('未知状态原样显示', () => {
    render(<StatusChip value="SOMETHING_NEW" />);
    expect(screen.getByText('SOMETHING_NEW')).toBeInTheDocument();
  });
});
