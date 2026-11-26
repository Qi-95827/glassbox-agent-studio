import { useCallback, useMemo, useRef, useState } from 'react';
import { Box } from '@mui/material';

/**
 * 固定行高窗口虚拟滚动。
 *
 * <p>只渲染可视窗口加上下 `overscan` 行，千级事件列表的实际 DOM 节点数保持在数十个量级。
 * 这里刻意不引入重型虚拟列表依赖：行高固定时窗口计算是一个除法，自研实现更容易审计，
 * 也不会额外增加首屏包体积。
 *
 * <p>行高必须是固定值：动态测量会让滚动位置与内容高度互相依赖，长事件列表反而更容易抖动。
 * 需要多行文本时由调用方在固定行高内做省略。
 *
 * @param items 数据数组。
 * @param rowHeight 单行像素高度（含行间距）。
 * @param height 滚动视口像素高度。
 * @param overscan 视口上下额外渲染的行数，用于减少快速滚动时的空白。
 * @param renderRow `(item, index) => ReactNode`。
 * @param getKey `(item, index) => string`，缺省按索引。
 * @param emptyState 无数据时渲染的内容。
 * @param ariaLabel 列表的可访问名称。
 */
export default function VirtualList({
  items = [],
  rowHeight = 56,
  height = 360,
  overscan = 6,
  renderRow,
  getKey,
  emptyState = null,
  ariaLabel,
  testId = 'virtual-list'
}) {
  const scroller = useRef(null);
  const [scrollTop, setScrollTop] = useState(0);

  const total = items.length;
  const viewport = Math.max(1, Math.ceil(height / rowHeight));
  const startIndex = Math.max(0, Math.floor(scrollTop / rowHeight) - overscan);
  const endIndex = Math.min(total, Math.ceil((scrollTop + height) / rowHeight) + overscan);

  const onScroll = useCallback(event => {
    setScrollTop(event.currentTarget.scrollTop);
  }, []);

  const visible = useMemo(() => items.slice(startIndex, endIndex), [items, startIndex, endIndex]);

  if (total === 0) return emptyState;

  return (
    <Box
      ref={scroller}
      onScroll={onScroll}
      role="list"
      aria-label={ariaLabel}
      data-testid={testId}
      data-total={total}
      data-rendered={visible.length}
      data-viewport-rows={viewport}
      sx={{
        height,
        overflowY: 'auto',
        overflowX: 'hidden',
        position: 'relative',
        // 稳定滚动条宽度，避免出现/消失时行内容重新折行。
        scrollbarGutter: 'stable'
      }}
    >
      <Box sx={{ height: total * rowHeight, position: 'relative' }}>
        {visible.map((item, offset) => {
          const index = startIndex + offset;
          return (
            <Box
              key={getKey ? getKey(item, index) : index}
              role="listitem"
              aria-posinset={index + 1}
              aria-setsize={total}
              sx={{
                position: 'absolute',
                top: index * rowHeight,
                left: 0,
                right: 0,
                height: rowHeight,
                overflow: 'hidden'
              }}
            >
              {renderRow(item, index)}
            </Box>
          );
        })}
      </Box>
    </Box>
  );
}
