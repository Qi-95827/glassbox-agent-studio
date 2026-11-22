import { Box } from '@mui/material';

/**
 * 架构图谱共享 SVG 视觉原语（Google Material 3 浅色高对比语义色板）。
 */

export const TONES = {
  blue: {
    accent: '#1A73E8',
    soft: '#E8F0FE',
    border: '#AECBFA',
    text: '#174EA6'
  },
  green: {
    accent: '#1E8E3E',
    soft: '#E6F4EA',
    border: '#A8DAB5',
    text: '#137333'
  },
  amber: {
    accent: '#E37400',
    soft: '#FEF7E0',
    border: '#FDE293',
    text: '#B06000'
  },
  purple: {
    accent: '#8430CE',
    soft: '#F3E8FD',
    border: '#D7AEFB',
    text: '#681DA8'
  },
  red: {
    accent: '#D93025',
    soft: '#FCE8E6',
    border: '#F6AEA9',
    text: '#A50E0E'
  }
};

export function DiagramDefs({ idPrefix }) {
  return (
    <defs>
      <pattern id={`${idPrefix}-grid`} width="20" height="20" patternUnits="userSpaceOnUse">
        <circle cx="2" cy="2" r="1" fill="#DCE3EC" />
      </pattern>
      <filter id={`${idPrefix}-shadow`} x="-8%" y="-10%" width="116%" height="128%">
        <feDropShadow dx="0" dy="2" stdDeviation="3" floodColor="#1F2937" floodOpacity="0.06" />
      </filter>
      <filter id={`${idPrefix}-glow`} x="-10%" y="-14%" width="120%" height="136%">
        <feDropShadow dx="0" dy="3" stdDeviation="5" floodColor="#1A73E8" floodOpacity="0.18" />
      </filter>
      <marker
        id={`${idPrefix}-arrow`}
        viewBox="0 0 10 10"
        refX="8.5"
        refY="5"
        markerWidth="6.5"
        markerHeight="6.5"
        orient="auto-start-reverse"
      >
        <path d="M 1 1.5 L 8.5 5 L 1 8.5 z" fill="#7B8794" />
      </marker>
      <marker
        id={`${idPrefix}-arrow-green`}
        viewBox="0 0 10 10"
        refX="8.5"
        refY="5"
        markerWidth="6.5"
        markerHeight="6.5"
        orient="auto-start-reverse"
      >
        <path d="M 1 1.5 L 8.5 5 L 1 8.5 z" fill="#1E8E3E" />
      </marker>
      <marker
        id={`${idPrefix}-arrow-red`}
        viewBox="0 0 10 10"
        refX="8.5"
        refY="5"
        markerWidth="6.5"
        markerHeight="6.5"
        orient="auto-start-reverse"
      >
        <path d="M 1 1.5 L 8.5 5 L 1 8.5 z" fill="#D93025" />
      </marker>
      <marker
        id={`${idPrefix}-arrow-blue`}
        viewBox="0 0 10 10"
        refX="8.5"
        refY="5"
        markerWidth="6.5"
        markerHeight="6.5"
        orient="auto-start-reverse"
      >
        <path d="M 1 1.5 L 8.5 5 L 1 8.5 z" fill="#1A73E8" />
      </marker>
    </defs>
  );
}

export function ZoneBox({
  x,
  y,
  width,
  height,
  label,
  color = '#94A3B8',
  fill = 'rgba(255,255,255,0.55)'
}) {
  return (
    <g>
      <rect
        x={x}
        y={y}
        width={width}
        height={height}
        rx="16"
        fill={fill}
        stroke={color}
        strokeWidth="1.25"
        strokeDasharray="5 4"
      />
      {label && (
        <text
          x={x + 14}
          y={y + 20}
          fontSize="11"
          fontWeight="600"
          fill="#5F6368"
          letterSpacing="0.02em"
        >
          {label}
        </text>
      )}
    </g>
  );
}

export function FlowPath({
  idPrefix,
  d,
  variant = 'default',
  dashed = false,
  label,
  labelX,
  labelY
}) {
  const color =
    variant === 'green'
      ? '#1E8E3E'
      : variant === 'red'
        ? '#D93025'
        : variant === 'blue'
          ? '#1A73E8'
          : '#80868B';
  const marker =
    variant === 'green'
      ? `url(#${idPrefix}-arrow-green)`
      : variant === 'red'
        ? `url(#${idPrefix}-arrow-red)`
        : variant === 'blue'
          ? `url(#${idPrefix}-arrow-blue)`
          : `url(#${idPrefix}-arrow)`;
  const labelWidth = label ? Math.max(46, label.length * 7.2 + 16) : 48;
  return (
    <g>
      <path
        d={d}
        fill="none"
        stroke={color}
        strokeWidth="1.65"
        strokeDasharray={dashed ? '4 3' : undefined}
        markerEnd={marker}
      />
      {label && labelX != null && labelY != null && (
        <g transform={`translate(${labelX}, ${labelY})`}>
          <rect
            x={-labelWidth / 2}
            y={-10}
            width={labelWidth}
            height={18}
            rx={9}
            fill={variant === 'red' ? '#FCE8E6' : variant === 'green' ? '#E6F4EA' : '#FFFFFF'}
            stroke={variant === 'red' ? '#F6AEA9' : variant === 'green' ? '#A8DAB5' : '#DADCE0'}
            strokeWidth="1"
          />
          <text x="0" y="3" textAnchor="middle" fontSize="10" fontWeight="700" fill={color}>
            {label}
          </text>
        </g>
      )}
    </g>
  );
}

export function DiagramNode({
  idPrefix,
  id,
  x,
  y,
  width,
  height,
  title,
  subtitle,
  badge,
  tone = 'blue',
  drillable = false,
  selected,
  onSelect
}) {
  const palette = TONES[tone] ?? TONES.blue;
  const centerX = x + width / 2;
  const hasSubtitle = Boolean(subtitle);
  const titleY = hasSubtitle ? y + height / 2 - 4 : y + height / 2 + 4;

  return (
    <g
      role="button"
      tabIndex={0}
      aria-label={title}
      aria-pressed={selected}
      onClick={() => onSelect?.(id)}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onSelect?.(id);
        }
      }}
      style={{ cursor: 'pointer', outline: 'none' }}
    >
      <rect
        x={x}
        y={y}
        width={width}
        height={height}
        rx="13"
        fill={selected ? palette.soft : '#FFFFFF'}
        stroke={selected ? palette.accent : '#DADCE0'}
        strokeWidth={selected ? 2 : 1.2}
        filter={selected ? `url(#${idPrefix}-glow)` : `url(#${idPrefix}-shadow)`}
      />
      <rect
        x={x + 12}
        y={y + 1.5}
        width={Math.max(24, width - 24)}
        height="3.2"
        rx="1.6"
        fill={palette.accent}
        opacity={selected ? 1 : 0.72}
      />
      {badge && (
        <g transform={`translate(${x + 10}, ${y + 12})`}>
          <rect
            x="0"
            y="0"
            width={Math.max(24, badge.length * 7 + 10)}
            height="16"
            rx="8"
            fill={palette.soft}
          />
          <text
            x={Math.max(24, badge.length * 7 + 10) / 2}
            y="11.5"
            textAnchor="middle"
            fontSize="9.5"
            fontWeight="700"
            fill={palette.text}
          >
            {badge}
          </text>
        </g>
      )}
      {drillable && (
        <g transform={`translate(${x + width - 24}, ${y + 11})`}>
          <rect x="0" y="0" width="16" height="16" rx="8" fill={palette.soft} />
          <text
            x="8"
            y="11.5"
            textAnchor="middle"
            fontSize="10"
            fontWeight="700"
            fill={palette.text}
          >
            ↗
          </text>
        </g>
      )}
      <text
        x={badge && width < 175 ? x + width / 2 + 8 : centerX}
        y={titleY}
        textAnchor="middle"
        fontSize="12.5"
        fontWeight="600"
        fill="#202124"
      >
        {title}
      </text>
      {hasSubtitle && (
        <text
          x={centerX}
          y={y + height / 2 + 15}
          textAnchor="middle"
          fontSize="10.5"
          fill="#5F6368"
        >
          {subtitle}
        </text>
      )}
    </g>
  );
}

export function SvgFrame({ idPrefix, viewBox = '0 0 960 360', ariaLabel, children }) {
  return (
    <Box
      sx={{
        width: '100%',
        overflowX: 'auto',
        borderRadius: 3.5,
        border: '1px solid #E0E3EB',
        bgcolor: '#FAFBFD'
      }}
    >
      <svg
        viewBox={viewBox}
        role="img"
        aria-label={ariaLabel}
        style={{
          display: 'block',
          width: '100%',
          minWidth: 740,
          height: 'auto',
          fontFamily: "'Inter', 'Noto Sans SC', -apple-system, BlinkMacSystemFont, sans-serif"
        }}
      >
        <DiagramDefs idPrefix={idPrefix} />
        <rect x="0" y="0" width="100%" height="100%" fill={`url(#${idPrefix}-grid)`} />
        {children}
      </svg>
    </Box>
  );
}
