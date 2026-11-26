import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import GatePipeline, { defaultGateIndex } from './GatePipeline';
import { setModuleLocale } from '../../../i18n';

const checks = [
  { gate: 'OWNERSHIP_AND_SCOPE', label: '身份与归属鉴权', status: 'PASS', detail: '归属校验通过' },
  {
    gate: 'TOOL_REGISTRY_GRANT',
    label: '工具注册与节点权限',
    status: 'PASS',
    detail: '命中授权工具集'
  },
  {
    gate: 'PATH_WHITELIST',
    label: '路径白名单与读写隔离',
    status: 'DENY',
    detail: '../etc/passwd 越界'
  },
  { gate: 'CANCEL_STATE', label: '取消与暂停检查', status: 'SKIP', detail: '前置校验未通过' }
];

describe('GatePipeline', () => {
  it('按服务端记录顺序渲染关卡并保留原始状态', () => {
    setModuleLocale('zh-CN');
    render(<GatePipeline checks={checks} selected={-1} />);
    const stages = screen.getAllByTestId(/^gate-stage-/);
    expect(stages.map(node => node.getAttribute('data-status'))).toEqual([
      'PASS',
      'PASS',
      'DENY',
      'SKIP'
    ]);
    expect(stages[0]).toHaveAttribute('data-testid', 'gate-stage-OWNERSHIP_AND_SCOPE');
  });

  it('默认选中的是被拦截的那道门，而不是最后一道', () => {
    // 用户最关心「为什么停在这里」，所以缺省焦点必须落在 DENY 上。
    expect(defaultGateIndex(checks)).toBe(2);
    expect(defaultGateIndex([{ status: 'PASS' }, { status: 'PASS' }])).toBe(1);
    expect(defaultGateIndex([])).toBe(-1);
  });

  it('点击关卡展开该关的判定详情与检查目的', () => {
    setModuleLocale('zh-CN');
    const onSelect = vi.fn();
    render(<GatePipeline checks={checks} selected={2} onSelect={onSelect} />);

    const detail = screen.getByTestId('gate-detail');
    expect(detail).toHaveTextContent('DENY');
    expect(detail).toHaveTextContent('../etc/passwd 越界');

    // 点另一道门：父组件据回传下标切换展开项。
    fireEvent.click(screen.getByTestId('gate-stage-OWNERSHIP_AND_SCOPE'));
    expect(onSelect).toHaveBeenCalledWith(0);
  });

  it('再次点击已展开的关卡时收起（回传 -1）', () => {
    setModuleLocale('zh-CN');
    const onSelect = vi.fn();
    render(<GatePipeline checks={checks} selected={2} onSelect={onSelect} />);
    fireEvent.click(screen.getByTestId('gate-stage-PATH_WHITELIST'));
    expect(onSelect).toHaveBeenCalledWith(-1);
  });

  it('没有落库判定时明确说明未采集，而不是渲染空流水线', () => {
    setModuleLocale('zh-CN');
    render(<GatePipeline checks={[]} />);
    expect(screen.queryByTestId('gate-pipeline')).toBeNull();
    expect(screen.getByText('历史运行未采集网关逐项检查明细。')).toBeInTheDocument();
  });
});
