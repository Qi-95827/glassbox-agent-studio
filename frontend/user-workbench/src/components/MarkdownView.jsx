import { Fragment } from 'react';
import { Box, Divider, Stack, Typography } from '@mui/material';
import { googleTokens } from '../theme/googleTheme';

/**
 * 安全的轻量 Markdown 渲染器（纯 React 节点，严禁使用 dangerouslySetInnerHTML）。
 *
 * 支持标题（# ~ ####）、分割线（---）、围栏代码块（```）、无序/有序列表、
 * 行内代码（`code`）与加粗（**bold**），适配 Agent 输出与交接正文。
 */
function renderInline(text) {
  const parts = [];
  const pattern = /(`[^`\n]+`|\*\*[^*\n]+\*\*)/g;
  let last = 0;
  let match;
  let idx = 0;
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > last) {
      parts.push(<Fragment key={`t:${idx++}`}>{text.slice(last, match.index)}</Fragment>);
    }
    const token = match[0];
    if (token.startsWith('`')) {
      parts.push(
        <Box
          key={`c:${idx++}`}
          component="code"
          sx={{
            px: 0.6,
            py: 0.1,
            mx: 0.2,
            borderRadius: 1,
            bgcolor: '#F1F3F4',
            color: '#1F1F1F',
            fontFamily: googleTokens.mono,
            fontSize: '0.88em'
          }}
        >
          {token.slice(1, -1)}
        </Box>
      );
    } else {
      parts.push(
        <Box key={`b:${idx++}`} component="strong" sx={{ fontWeight: 600 }}>
          {renderInline(token.slice(2, -2))}
        </Box>
      );
    }
    last = match.index + token.length;
  }
  if (last < text.length) {
    parts.push(<Fragment key={`e:${idx++}`}>{text.slice(last)}</Fragment>);
  }
  return parts;
}

function isTableSeparator(line) {
  const trimmed = line.trim();
  if (!trimmed.includes('|') || !trimmed.includes('-')) return false;
  const cells = splitTableRow(trimmed);
  return cells.length > 0 && cells.every(cell => /^:?-{2,}:?$/.test(cell));
}

function splitTableRow(line) {
  let trimmed = line.trim();
  if (trimmed.startsWith('|')) trimmed = trimmed.slice(1);
  if (trimmed.endsWith('|')) trimmed = trimmed.slice(0, -1);
  return trimmed.split('|').map(cell => cell.trim());
}

function parseBlocks(source) {
  const lines = String(source || '')
    .replace(/\r\n/g, '\n')
    .split('\n');
  const blocks = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    const trimmed = line.trim();
    if (!trimmed) {
      i += 1;
      continue;
    }
    if (trimmed.startsWith('```')) {
      const lang = trimmed.slice(3).trim();
      const codeLines = [];
      i += 1;
      while (i < lines.length && !lines[i].trim().startsWith('```')) {
        codeLines.push(lines[i]);
        i += 1;
      }
      if (i < lines.length) i += 1;
      blocks.push({ type: 'code', lang, content: codeLines.join('\n') });
      continue;
    }
    if (trimmed.includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      const headers = splitTableRow(trimmed);
      i += 2;
      const rows = [];
      while (i < lines.length && lines[i].trim().includes('|')) {
        rows.push(splitTableRow(lines[i]));
        i += 1;
      }
      blocks.push({ type: 'table', headers, rows });
      continue;
    }
    if (/^[-*_]{3,}$/.test(trimmed)) {
      blocks.push({ type: 'hr' });
      i += 1;
      continue;
    }
    const heading = /^(#{1,4})\s+(.+)$/.exec(trimmed);
    if (heading) {
      blocks.push({ type: 'heading', level: heading[1].length, text: heading[2] });
      i += 1;
      continue;
    }
    if (/^([-*•]|\d+\.)\s+/.test(trimmed)) {
      const items = [];
      while (i < lines.length && /^([-*•]|\d+\.)\s+/.test(lines[i].trim())) {
        const cur = lines[i].trim();
        const bullet = /^(\d+\.|[-*•])\s+(.+)$/.exec(cur);
        if (bullet) items.push({ marker: bullet[1], text: bullet[2] });
        i += 1;
      }
      blocks.push({ type: 'list', items });
      continue;
    }
    const para = [line];
    i += 1;
    while (
      i < lines.length &&
      lines[i].trim() &&
      !lines[i].trim().startsWith('```') &&
      !(lines[i].trim().includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1])) &&
      !/^#{1,4}\s+/.test(lines[i].trim()) &&
      !/^[-*_]{3,}$/.test(lines[i].trim()) &&
      !/^([-*•]|\d+\.)\s+/.test(lines[i].trim())
    ) {
      para.push(lines[i]);
      i += 1;
    }
    blocks.push({ type: 'paragraph', text: para.join('\n') });
  }
  return blocks;
}

const headingSize = { 1: 18, 2: 16, 3: 14.5, 4: 13.5 };

export default function MarkdownView({ text, maxHeight, dense = false }) {
  const blocks = parseBlocks(text);
  if (blocks.length === 0) return null;

  return (
    <Stack
      spacing={dense ? 0.85 : 1.15}
      sx={{
        maxHeight: maxHeight || 'none',
        overflowY: maxHeight ? 'auto' : 'visible',
        overflowWrap: 'anywhere',
        color: googleTokens.text
      }}
    >
      {blocks.map((block, index) => {
        if (block.type === 'hr') {
          return <Divider key={`hr:${index}`} sx={{ my: 0.5 }} />;
        }
        if (block.type === 'heading') {
          return (
            <Typography
              key={`h:${index}`}
              sx={{
                fontSize: headingSize[block.level] || 14,
                fontWeight: 600,
                lineHeight: 1.4,
                pt: index === 0 ? 0 : 0.4
              }}
            >
              {renderInline(block.text)}
            </Typography>
          );
        }
        if (block.type === 'code') {
          return (
            <Box
              key={`code:${index}`}
              sx={{
                borderRadius: 2,
                bgcolor: '#F8F9FA',
                border: `1px solid ${googleTokens.outline}`,
                overflow: 'hidden'
              }}
            >
              {block.lang && (
                <Typography
                  variant="caption"
                  sx={{
                    display: 'block',
                    px: 1.25,
                    py: 0.35,
                    bgcolor: '#F1F3F4',
                    color: googleTokens.textSecondary,
                    fontFamily: googleTokens.mono,
                    fontSize: 11
                  }}
                >
                  {block.lang}
                </Typography>
              )}
              <Box
                component="pre"
                sx={{
                  m: 0,
                  p: 1.25,
                  overflowX: 'auto',
                  fontFamily: googleTokens.mono,
                  fontSize: 12.25,
                  lineHeight: 1.55
                }}
              >
                {block.content}
              </Box>
            </Box>
          );
        }
        if (block.type === 'table') {
          return (
            <Box
              key={`tbl:${index}`}
              sx={{
                overflowX: 'auto',
                borderRadius: 2,
                border: `1px solid ${googleTokens.outline}`,
                bgcolor: '#FFFFFF'
              }}
            >
              <Box
                component="table"
                sx={{
                  width: '100%',
                  borderCollapse: 'collapse',
                  fontSize: dense ? 12.5 : 13,
                  '& th, & td': {
                    px: 1.25,
                    py: 0.75,
                    textAlign: 'left',
                    borderBottom: `1px solid ${googleTokens.outline}`,
                    verticalAlign: 'top',
                    lineHeight: 1.55
                  },
                  '& thead th': {
                    bgcolor: '#F8F9FA',
                    fontWeight: 600,
                    color: googleTokens.textSecondary
                  },
                  '& tbody tr:last-of-type td': {
                    borderBottom: 'none'
                  }
                }}
              >
                <thead>
                  <tr>
                    {block.headers.map((head, hIdx) => (
                      <th key={`th:${hIdx}`}>{renderInline(head)}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {block.rows.map((row, rIdx) => (
                    <tr key={`tr:${rIdx}`}>
                      {block.headers.map((_, cIdx) => (
                        <td key={`td:${rIdx}:${cIdx}`}>{renderInline(row[cIdx] || '')}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </Box>
            </Box>
          );
        }
        if (block.type === 'list') {
          return (
            <Stack key={`list:${index}`} spacing={0.45} sx={{ pl: 0.5 }}>
              {block.items.map((item, idx) => (
                <Stack
                  key={`li:${idx}`}
                  direction="row"
                  spacing={0.8}
                  sx={{ alignItems: 'baseline' }}
                >
                  <Typography
                    variant="body2"
                    sx={{ color: googleTokens.textSecondary, flexShrink: 0, fontWeight: 500 }}
                  >
                    {/^\d+\.$/.test(item.marker) ? item.marker : '•'}
                  </Typography>
                  <Typography variant="body2" sx={{ lineHeight: 1.65 }}>
                    {renderInline(item.text)}
                  </Typography>
                </Stack>
              ))}
            </Stack>
          );
        }
        return (
          <Typography
            key={`p:${index}`}
            variant="body2"
            sx={{ lineHeight: 1.7, whiteSpace: 'pre-wrap' }}
          >
            {renderInline(block.text)}
          </Typography>
        );
      })}
    </Stack>
  );
}
