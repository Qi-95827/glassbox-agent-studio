import { useMemo, useState } from 'react';
import { Box, Stack, Typography } from '@mui/material';
import { googleTokens } from '../../../theme/googleTheme';
import { getCurrentLocale, translate } from '../../../i18n';

const L = (key, params) => translate(getCurrentLocale(), key, params);

export const CHART_COLORS = {
  primary: '#1A73E8',
  primarySoft: '#D3E3FD',
  success: '#137333',
  successSoft: '#CEEAD6',
  warning: '#B06000',
  warningSoft: '#FEEFC3',
  error: '#C5221F',
  errorSoft: '#F6C7C4',
  neutral: '#9AA0A6',
  neutralSoft: '#E8EAED',
  purple: '#7627BB',
  teal: '#0B8043',
  orange: '#E37400'
};

export const DONUT_PALETTE = [
  CHART_COLORS.primary,
  CHART_COLORS.success,
  CHART_COLORS.warning,
  CHART_COLORS.error,
  CHART_COLORS.purple,
  CHART_COLORS.teal,
  CHART_COLORS.orange,
  CHART_COLORS.neutral,
  '#1967D2',
  '#A142F4'
];

function compactNumber(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number <= 0) return '0';
  if (number >= 1_000_000) return `${(number / 1_000_000).toFixed(1)}M`;
  if (number >= 1_000) return `${(number / 1_000).toFixed(1)}k`;
  return String(Math.round(number));
}

function percentText(value, total) {
  if (!total) return '0%';
  return `${((value / total) * 100).toFixed(1)}%`;
}

/** 半圆仪表盘：用于成功率等单一百分比指标。 */
export function GaugeArc({ value = 0, label = L('monitoring.successRate'), color }) {
  const clamped = Math.min(100, Math.max(0, Number(value) || 0));
  const radius = 70;
  const length = Math.PI * radius;
  const stroke =
    color ||
    (clamped >= 80
      ? CHART_COLORS.success
      : clamped >= 50
        ? CHART_COLORS.warning
        : CHART_COLORS.error);
  return (
    <Box sx={{ width: '100%', maxWidth: 190 }}>
      <svg viewBox="0 0 176 108" role="img" aria-label={`${label} ${clamped.toFixed(1)}%`}>
        <path
          d={`M 18 90 A ${radius} ${radius} 0 0 1 158 90`}
          fill="none"
          stroke={googleTokens.outline}
          strokeWidth="13"
          strokeLinecap="round"
        />
        <path
          d={`M 18 90 A ${radius} ${radius} 0 0 1 158 90`}
          fill="none"
          stroke={stroke}
          strokeWidth="13"
          strokeLinecap="round"
          strokeDasharray={`${(clamped / 100) * length} ${length}`}
        />
        <text
          x="88"
          y="76"
          textAnchor="middle"
          fontSize="27"
          fontWeight="600"
          fill={googleTokens.text}
        >
          {clamped.toFixed(1)}%
        </text>
        <text x="88" y="96" textAnchor="middle" fontSize="11" fill={googleTokens.textSecondary}>
          {label}
        </text>
      </svg>
    </Box>
  );
}

function LineGrid({ width, height, left, top, maxValue }) {
  const lines = [0, 0.25, 0.5, 0.75, 1];
  return (
    <g>
      {lines.map(ratio => {
        const y = top + height - ratio * height;
        return (
          <g key={ratio}>
            <line
              x1={left}
              x2={left + width}
              y1={y}
              y2={y}
              stroke={googleTokens.outline}
              strokeDasharray={ratio === 0 ? '0' : '3 4'}
            />
            <text
              x={left - 8}
              y={y + 3.5}
              textAnchor="end"
              fontSize="10"
              fill={googleTokens.textSecondary}
            >
              {compactNumber(maxValue * ratio)}
            </text>
          </g>
        );
      })}
    </g>
  );
}

/**
 * 时序趋势图：运行量堆叠柱、平均耗时折线、Token 面积图共用同一套坐标与 hover Tooltip。
 * 使用纯 SVG 渲染，不引入重型图表依赖；空数据时保持坐标轴但不会出现 NaN/Infinity。
 */
export function TrendChart({
  points = [],
  metric = 'runs',
  title = L('monitoring.chart.trendTitle')
}) {
  const [hovered, setHovered] = useState(null);
  const width = 760;
  const height = 250;
  const left = 50;
  const top = 18;
  const right = 16;
  const bottom = 34;
  const innerWidth = width - left - right;
  const innerHeight = height - top - bottom;
  const count = points.length;

  const maxValue = useMemo(() => {
    const values = points.map(point => {
      if (metric === 'runs') return Number(point.runCount) || 0;
      if (metric === 'duration') return Number(point.avgDurationMs) || 0;
      return Number(point.totalTokens) || 0;
    });
    const max = Math.max(0, ...values);
    return max > 0 ? max * 1.12 : 1;
  }, [metric, points]);

  if (!count) {
    return (
      <Typography variant="body2" color="text.secondary" sx={{ py: 6, textAlign: 'center' }}>
        该时间段暂无趋势样本。
      </Typography>
    );
  }

  const slot = innerWidth / count;
  const centerX = index => left + slot * index + slot / 2;
  const yOf = value => top + innerHeight - (Math.max(0, value) / maxValue) * innerHeight;
  const labelEvery = Math.max(1, Math.ceil(count / 8));
  const hoveredPoint = hovered == null ? null : points[hovered];

  const linePoints = points
    .map((point, index) =>
      point.avgDurationMs == null ? null : `${centerX(index)},${yOf(point.avgDurationMs)}`
    )
    .filter(Boolean)
    .join(' ');

  const tokenPoints = points.map((point, index) => `${centerX(index)},${yOf(point.totalTokens)}`);
  const areaPath = tokenPoints.length
    ? `M ${left},${top + innerHeight} L ${tokenPoints.join(' L ')} L ${left + innerWidth},${
        top + innerHeight
      } Z`
    : '';

  return (
    <Box sx={{ position: 'relative', width: '100%' }}>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, mb: 0.5, px: 0.5 }}>
        {metric === 'runs' && (
          <>
            <LegendDot color={CHART_COLORS.success} label={L('monitoring.chart.success')} />
            <LegendDot color={CHART_COLORS.error} label={L('monitoring.chart.failed')} />
            <LegendDot color={CHART_COLORS.neutralSoft} label={L('monitoring.chart.other')} />
          </>
        )}
        {metric === 'duration' && (
          <LegendDot color={CHART_COLORS.primary} label={L('monitoring.chart.avgDuration')} />
        )}
        {metric === 'tokens' && (
          <LegendDot color={CHART_COLORS.primary} label={L('monitoring.chart.tokens')} />
        )}
      </Box>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        style={{ width: '100%', height: 'auto', display: 'block' }}
        role="img"
        aria-label={title}
        onMouseLeave={() => setHovered(null)}
      >
        <LineGrid
          width={innerWidth}
          height={innerHeight}
          left={left}
          top={top}
          maxValue={maxValue}
        />

        {metric === 'runs' &&
          points.map((point, index) => {
            const total = Number(point.runCount) || 0;
            if (total <= 0) return null;
            const success = Number(point.successCount) || 0;
            const failed = Number(point.failedCount) || 0;
            const other = Math.max(0, total - success - failed);
            const barWidth = Math.max(3, slot * 0.56);
            const x = centerX(index) - barWidth / 2;
            let cursor = top + innerHeight;
            const parts = [
              { value: success, color: CHART_COLORS.success },
              { value: failed, color: CHART_COLORS.error },
              { value: other, color: CHART_COLORS.neutralSoft }
            ];
            return (
              <g key={`bar-${point.bucketStart || index}`}>
                {parts.map((part, partIndex) => {
                  if (part.value <= 0) return null;
                  const barHeight = (part.value / maxValue) * innerHeight;
                  cursor -= barHeight;
                  return (
                    <rect
                      key={partIndex}
                      x={x}
                      y={cursor}
                      width={barWidth}
                      height={Math.max(1, barHeight)}
                      rx={Math.min(4, barWidth / 2)}
                      fill={part.color}
                    />
                  );
                })}
              </g>
            );
          })}

        {metric === 'tokens' && (
          <>
            <path d={areaPath} fill={CHART_COLORS.primarySoft} opacity="0.75" />
            <polyline
              points={tokenPoints.join(' ')}
              fill="none"
              stroke={CHART_COLORS.primary}
              strokeWidth="2.5"
              strokeLinejoin="round"
            />
          </>
        )}

        {metric === 'duration' && linePoints && (
          <>
            <polyline
              points={linePoints}
              fill="none"
              stroke={CHART_COLORS.primary}
              strokeWidth="2.5"
              strokeLinejoin="round"
              strokeLinecap="round"
            />
            {points.map((point, index) =>
              point.avgDurationMs == null ? null : (
                <circle
                  key={`dot-${point.bucketStart || index}`}
                  cx={centerX(index)}
                  cy={yOf(point.avgDurationMs)}
                  r={hovered === index ? 5 : 3.2}
                  fill="#FFFFFF"
                  stroke={CHART_COLORS.primary}
                  strokeWidth="2"
                />
              )
            )}
          </>
        )}

        {points.map((point, index) => (
          <g key={`label-${point.bucketStart || index}`}>
            {(index % labelEvery === 0 || index === count - 1) && (
              <text
                x={centerX(index)}
                y={height - 10}
                textAnchor="middle"
                fontSize="10"
                fill={googleTokens.textSecondary}
              >
                {point.bucketLabel}
              </text>
            )}
            <rect
              x={left + slot * index}
              y={top}
              width={slot}
              height={innerHeight}
              fill="transparent"
              onMouseEnter={() => setHovered(index)}
              onFocus={() => setHovered(index)}
              tabIndex={-1}
            />
          </g>
        ))}

        {hoveredPoint && (
          <line
            x1={centerX(hovered)}
            x2={centerX(hovered)}
            y1={top}
            y2={top + innerHeight}
            stroke={googleTokens.outlineStrong}
            strokeDasharray="3 3"
          />
        )}
      </svg>

      {hoveredPoint && (
        <Box
          sx={{
            position: 'absolute',
            left: `${Math.min(88, Math.max(12, (centerX(hovered) / width) * 100))}%`,
            top: 4,
            transform: 'translateX(-50%)',
            bgcolor: '#3C4043',
            color: '#FFFFFF',
            borderRadius: 2,
            px: 1.1,
            py: 0.7,
            pointerEvents: 'none',
            zIndex: 2,
            minWidth: 128,
            boxShadow: '0 4px 14px rgba(60,64,67,.24)'
          }}
        >
          <Typography sx={{ fontSize: 11, fontWeight: 600 }}>{hoveredPoint.bucketLabel}</Typography>
          {metric === 'runs' && (
            <Typography sx={{ fontSize: 11 }}>
              {L('monitoring.chart.runs', { count: hoveredPoint.runCount ?? 0 })} ·{' '}
              {L('monitoring.chart.successCount', { count: hoveredPoint.successCount ?? 0 })} ·{' '}
              {L('monitoring.chart.failedCount', { count: hoveredPoint.failedCount ?? 0 })}
            </Typography>
          )}
          {metric === 'duration' && (
            <Typography sx={{ fontSize: 11 }}>
              {L('monitoring.chart.avgDuration')}{' '}
              {hoveredPoint.avgDurationMs == null ? '—' : `${hoveredPoint.avgDurationMs}ms`}
            </Typography>
          )}
          {metric === 'tokens' && (
            <Typography sx={{ fontSize: 11 }}>
              Token {Number(hoveredPoint.totalTokens || 0).toLocaleString()}
            </Typography>
          )}
        </Box>
      )}
    </Box>
  );
}

function LegendDot({ color, label }) {
  return (
    <Stack direction="row" spacing={0.6} sx={{ alignItems: 'center' }}>
      <Box sx={{ width: 9, height: 9, borderRadius: '2px', bgcolor: color }} />
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
    </Stack>
  );
}

/**
 * Donut 圆环图：支持 hover 高亮、中心汇总与右侧图例。
 * slices: [{ key, label, value, detail }]
 */
export function DonutChart({ slices = [], totalLabel = L('common.total'), valueSuffix = '' }) {
  const [hovered, setHovered] = useState(null);
  const total = slices.reduce((sum, slice) => sum + (Number(slice.value) || 0), 0);
  const radius = 62;
  const circumference = 2 * Math.PI * radius;
  let offset = 0;
  const active = hovered == null ? null : slices[hovered];

  if (!slices.length || total <= 0) {
    return (
      <Typography variant="body2" color="text.secondary" sx={{ py: 5, textAlign: 'center' }}>
        {L('monitoring.chart.noDistribution')}
      </Typography>
    );
  }

  return (
    <Stack
      direction={{ xs: 'column', sm: 'row' }}
      spacing={2}
      sx={{ alignItems: 'center', justifyContent: 'center' }}
    >
      <Box sx={{ position: 'relative', width: 190, flexShrink: 0 }}>
        <svg
          viewBox="0 0 180 180"
          style={{ width: '100%', display: 'block' }}
          role="img"
          aria-label={L('monitoring.chart.distribution')}
        >
          <circle cx="90" cy="90" r={radius} fill="none" stroke="#F1F3F4" strokeWidth="26" />
          {slices.map((slice, index) => {
            const value = Number(slice.value) || 0;
            const portion = value / total;
            const dash = portion * circumference;
            const element = (
              <circle
                key={slice.key ?? index}
                cx="90"
                cy="90"
                r={radius}
                fill="none"
                stroke={DONUT_PALETTE[index % DONUT_PALETTE.length]}
                strokeWidth={hovered === index ? 29 : 26}
                strokeDasharray={`${Math.max(0, dash - 1)} ${circumference}`}
                strokeDashoffset={-offset}
                transform="rotate(-90 90 90)"
                opacity={hovered == null || hovered === index ? 1 : 0.42}
                onMouseEnter={() => setHovered(index)}
                onMouseLeave={() => setHovered(null)}
                style={{ transition: 'opacity .15s ease, stroke-width .15s ease' }}
              />
            );
            offset += dash;
            return element;
          })}
          <text x="90" y="84" textAnchor="middle" fontSize="12" fill={googleTokens.textSecondary}>
            {active ? active.label : totalLabel}
          </text>
          <text
            x="90"
            y="107"
            textAnchor="middle"
            fontSize="22"
            fontWeight="600"
            fill={googleTokens.text}
          >
            {active
              ? `${Number(active.value).toLocaleString()}${valueSuffix}`
              : total.toLocaleString()}
          </text>
          {active && (
            <text
              x="90"
              y="124"
              textAnchor="middle"
              fontSize="11"
              fill={googleTokens.textSecondary}
            >
              {percentText(active.value, total)}
            </text>
          )}
        </svg>
      </Box>
      <Stack spacing={0.75} sx={{ flex: 1, minWidth: 0, width: '100%' }}>
        {slices.map((slice, index) => (
          <Stack
            key={slice.key ?? index}
            direction="row"
            spacing={0.8}
            sx={{ alignItems: 'center', minWidth: 0 }}
            onMouseEnter={() => setHovered(index)}
            onMouseLeave={() => setHovered(null)}
          >
            <Box
              sx={{
                width: 9,
                height: 9,
                borderRadius: '50%',
                bgcolor: DONUT_PALETTE[index % DONUT_PALETTE.length],
                flexShrink: 0
              }}
            />
            <Typography
              variant="caption"
              sx={{
                flex: 1,
                minWidth: 0,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap'
              }}
            >
              {slice.label}
            </Typography>
            <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0 }}>
              {Number(slice.value).toLocaleString()}
              {valueSuffix} · {percentText(slice.value, total)}
            </Typography>
          </Stack>
        ))}
      </Stack>
    </Stack>
  );
}
