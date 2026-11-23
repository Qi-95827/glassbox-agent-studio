import { useMemo, useState } from 'react';
import { Box, Stack, Typography } from '@mui/material';
import { googleTokens } from '../../theme/googleTheme';

const SIZE = 360;
const CENTER = 176;
const RADIUS = 118;
const RINGS = [0.25, 0.5, 0.75, 1];

function clamp(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return 0;
  return Math.max(0, Math.min(100, number));
}

function point(index, total, ratio) {
  const angle = -Math.PI / 2 + (index * 2 * Math.PI) / total;
  return [CENTER + Math.cos(angle) * RADIUS * ratio, CENTER + Math.sin(angle) * RADIUS * ratio];
}

function polygon(total, ratio) {
  return Array.from({ length: total }, (_, index) => point(index, total, ratio).join(',')).join(
    ' '
  );
}

/**
 * 纯 React SVG 六维能力雷达。
 *
 * <p>支持单批次与 A/B 双图层叠加；缺失或未测维度按 0 绘制并灰显，不插值、不补分。
 */
export default function EvalRadarChart({
  categories = [],
  series = [],
  ariaLabel = 'Eval radar',
  size = 360
}) {
  const [hover, setHover] = useState(null);
  const total = Math.max(3, categories.length);
  const normalized = useMemo(
    () =>
      series.map((item, index) => ({
        key: item.key ?? `series-${index}`,
        label: item.label ?? '',
        color: item.color ?? [googleTokens.primary, '#B3261E', '#34A853'][index % 3],
        values: categories.map(category => clamp(item.values?.[category.code])),
        sampleCounts: categories.map(category => item.sampleCounts?.[category.code] ?? null),
        measured: categories.map(category => item.values?.[category.code] != null)
      })),
    [series, categories]
  );

  const labels = categories.map(category => category.label ?? category.code);

  return (
    <Box sx={{ width: '100%', maxWidth: size + 80, mx: 'auto' }}>
      <Box
        component="svg"
        viewBox={`0 0 ${SIZE} ${SIZE}`}
        role="img"
        aria-label={ariaLabel}
        sx={{ width: '100%', height: 'auto', display: 'block' }}
      >
        {RINGS.map(ratio => (
          <polygon
            key={ratio}
            points={polygon(total, ratio)}
            fill="none"
            stroke="#DADCE0"
            strokeWidth={ratio === 1 ? 1.2 : 0.7}
            strokeDasharray={ratio === 1 ? '0' : '4 4'}
          />
        ))}
        {categories.map((category, index) => {
          const [x, y] = point(index, total, 1);
          return (
            <line
              key={category.code}
              x1={CENTER}
              y1={CENTER}
              x2={x}
              y2={y}
              stroke="#E0E3E7"
              strokeWidth={0.8}
            />
          );
        })}
        {labels.map((label, index) => {
          const [x, y] = point(index, total, 1.19);
          return (
            <text
              key={`${label}-${index}`}
              x={x}
              y={y}
              textAnchor={x < CENTER - 4 ? 'end' : x > CENTER + 4 ? 'start' : 'middle'}
              dominantBaseline="middle"
              fontSize="10.5"
              fill="#5F6368"
            >
              {label}
            </text>
          );
        })}
        {normalized.map(item => (
          <g key={item.key}>
            <polygon
              points={categories
                .map((_, index) => point(index, total, item.values[index] / 100).join(','))
                .join(' ')}
              fill={item.color}
              fillOpacity={normalized.length > 1 ? 0.12 : 0.16}
              stroke={item.color}
              strokeWidth={2}
              strokeDasharray={item.measured.every(Boolean) ? '0' : '5 3'}
            />
            {categories.map((category, index) => {
              const [x, y] = point(index, total, item.values[index] / 100);
              return (
                <circle
                  key={`${item.key}-${category.code}`}
                  cx={x}
                  cy={y}
                  r={hover?.category === index ? 4.5 : 3}
                  fill="#fff"
                  stroke={item.color}
                  strokeWidth={1.8}
                  onMouseEnter={() => setHover({ category: index, series: item.key })}
                  onMouseLeave={() => setHover(null)}
                />
              );
            })}
          </g>
        ))}
        {categories.map((category, index) => {
          const [x, y] = point(index, total, 1);
          return (
            <circle
              key={`hit-${category.code}`}
              cx={x}
              cy={y}
              r={14}
              fill="transparent"
              onMouseEnter={() => setHover({ category: index, series: null })}
              onMouseLeave={() => setHover(null)}
            />
          );
        })}
      </Box>
      <Stack direction="row" spacing={1.5} sx={{ justifyContent: 'center', flexWrap: 'wrap' }}>
        {normalized.map(item => (
          <Stack key={item.key} direction="row" spacing={0.6} sx={{ alignItems: 'center' }}>
            <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: item.color }} />
            <Typography variant="caption" color="text.secondary">
              {item.label}
            </Typography>
          </Stack>
        ))}
      </Stack>
      <Box sx={{ minHeight: 22, textAlign: 'center' }}>
        {hover && (
          <Typography variant="caption" color="text.secondary">
            {labels[hover.category]}
            {' · '}
            {normalized
              .map(item => {
                const count = item.sampleCounts[hover.category];
                return `${item.label} ${item.values[hover.category].toFixed(1)}%${
                  count == null ? '' : ` (${count})`
                }`;
              })
              .join('  ·  ')}
          </Typography>
        )}
      </Box>
    </Box>
  );
}
