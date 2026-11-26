import { useMemo } from 'react';
import { Box, Stack, Typography } from '@mui/material';

/**
 * 行级 LCS：返回两侧逐行对齐后的渲染指令。
 *
 * <p>只做行比较，不做语法分析；相同行保持相等、左侧独有标记为删除、右侧独有标记为新增。
 * 结果稳定可测，且不引入任何新依赖。
 */
export function diffLines(leftText, rightText) {
  const left = String(leftText ?? '').split('\n');
  const right = String(rightText ?? '').split('\n');
  const rows = left.length;
  const cols = right.length;
  // 动态规划表按 (rows+1)*(cols+1) 建；这里只保留最短路长度与回溯指针。
  const table = Array.from({ length: rows + 1 }, () => new Uint32Array(cols + 1));
  for (let i = rows - 1; i >= 0; i -= 1) {
    for (let j = cols - 1; j >= 0; j -= 1) {
      table[i][j] =
        left[i] === right[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1]);
    }
  }
  const result = [];
  let i = 0;
  let j = 0;
  while (i < rows && j < cols) {
    if (left[i] === right[j]) {
      result.push({ kind: 'same', left: left[i], right: right[j] });
      i += 1;
      j += 1;
    } else if (table[i + 1][j] >= table[i][j + 1]) {
      result.push({ kind: 'removed', left: left[i], right: '' });
      i += 1;
    } else {
      result.push({ kind: 'added', left: '', right: right[j] });
      j += 1;
    }
  }
  while (i < rows) {
    result.push({ kind: 'removed', left: left[i], right: '' });
    i += 1;
  }
  while (j < cols) {
    result.push({ kind: 'added', left: '', right: right[j] });
    j += 1;
  }
  return result;
}

const TONE = {
  same: { bg: 'transparent', glyph: ' ' },
  added: { bg: '#E6F4EA', glyph: '+' },
  removed: { bg: '#FCE8E6', glyph: '-' }
};

/**
 * 并排代码对比：左旧右新，逐行高亮新增与删除。
 *
 * <p>这里是纯展示组件：不做文本清洗、不做语义推断，传入什么就对比什么；同一份对比在节点产出与
 * 交付内容上复用同一套渲染，避免两处 diff 语义不一致。
 */
export default function CodeDiffView({ left = '', right = '', leftLabel, rightLabel, testId }) {
  const rows = useMemo(() => diffLines(left, right), [left, right]);
  return (
    <Box data-testid={testId ?? 'code-diff'}>
      <Stack direction="row" spacing={1}>
        <Typography variant="caption" sx={{ flex: 1 }} color="text.secondary">
          {leftLabel}
        </Typography>
        <Typography variant="caption" sx={{ flex: 1 }} color="text.secondary">
          {rightLabel}
        </Typography>
      </Stack>
      <Box
        sx={{
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 1.5,
          fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
          fontSize: 12,
          maxHeight: 320,
          overflow: 'auto'
        }}
      >
        {rows.map((row, index) => {
          const tone = TONE[row.kind] ?? TONE.same;
          return (
            <Stack
              // 行序即身份：同一对文本的渲染顺序稳定，可用下标作键。
              key={`${index}:${row.kind}`}
              direction="row"
              sx={{ bgcolor: tone.bg, minHeight: 18 }}
              data-diff-row={row.kind}
            >
              <Typography
                component="span"
                sx={{ width: 18, textAlign: 'center', color: 'text.secondary' }}
              >
                {tone.glyph}
              </Typography>
              <Typography component="span" sx={{ flex: 1, whiteSpace: 'pre-wrap', px: 0.5 }}>
                {row.left}
              </Typography>
              <Typography
                component="span"
                sx={{
                  width: 1,
                  borderLeft: '1px solid',
                  borderColor: 'divider',
                  mx: 0.5
                }}
              />
              <Typography component="span" sx={{ flex: 1, whiteSpace: 'pre-wrap', px: 0.5 }}>
                {row.right}
              </Typography>
            </Stack>
          );
        })}
      </Box>
    </Box>
  );
}
