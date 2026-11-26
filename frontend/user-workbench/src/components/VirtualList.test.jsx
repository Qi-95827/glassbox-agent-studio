import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import VirtualList from './VirtualList';

const makeItems = count =>
  Array.from({ length: count }, (_, index) => ({ id: `e-${index}`, label: `item ${index}` }));

const renderList = (items, overrides = {}) =>
  render(
    <VirtualList
      items={items}
      rowHeight={40}
      height={400}
      overscan={4}
      getKey={item => item.id}
      renderRow={item => <span>{item.label}</span>}
      {...overrides}
    />
  );

describe('VirtualList', () => {
  it('千级数据只渲染可视窗口内的行', () => {
    const items = makeItems(1000);
    renderList(items);
    const list = screen.getByTestId('virtual-list');

    expect(list).toHaveAttribute('data-total', '1000');
    // 400/40 = 10 行视口 + 上下各 4 行 overscan，最多 19 行。
    const rendered = Number(list.getAttribute('data-rendered'));
    expect(rendered).toBeLessThanOrEqual(19);
    expect(rendered).toBeGreaterThan(0);

    expect(screen.getByText('item 0')).toBeInTheDocument();
    // 尾部数据在窗口之外，不能被渲染，否则虚拟滚动没有意义。
    expect(screen.queryByText('item 999')).toBeNull();
  });

  it('滚动后窗口移动到目标区间', () => {
    const items = makeItems(1000);
    renderList(items);
    const list = screen.getByTestId('virtual-list');
    // jsdom 没有布局，直接给滚动容器注入 scrollTop 再触发 scroll。
    Object.defineProperty(list, 'scrollTop', { value: 2000, writable: true, configurable: true });
    fireEvent.scroll(list);

    expect(screen.getByText('item 50')).toBeInTheDocument();
    // 起点行落在 2000/40 - 4 = 46，第一行不再渲染。
    expect(screen.queryByText('item 0')).toBeNull();
  });

  it('无数据时渲染空状态而不是空容器', () => {
    render(
      <VirtualList
        items={[]}
        rowHeight={40}
        height={200}
        getKey={item => item.id}
        renderRow={item => <span>{item.label}</span>}
        emptyState={<p>empty state</p>}
      />
    );
    expect(screen.getByText('empty state')).toBeInTheDocument();
    expect(screen.queryByTestId('virtual-list')).toBeNull();
  });

  it('渲染出的行带正确的位置信息', () => {
    const items = makeItems(30);
    renderList(items);
    const rows = screen.getAllByRole('listitem');
    expect(rows[0]).toHaveAttribute('aria-posinset', '1');
    expect(rows[0]).toHaveAttribute('aria-setsize', '30');
  });
});
