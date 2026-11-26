import { useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  Typography
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { contextArtifactsApi } from '../../../api/contextArtifacts';
import { Readout } from '../../../components/WorkbenchDialog';
import { googleTokens } from '../../../theme/googleTheme';
import { useI18n } from '../../../i18n/I18nProvider';

/** 六层分段的固定顺序与配色；顺序决定堆叠面积图的上下层关系。 */
export const SEGMENT_ORDER = [
  ['SYSTEM', '#1A73E8'],
  ['SKILLS', '#8430CE'],
  ['MEMORY', '#0B8043'],
  ['HANDOFF', '#B06000'],
  ['TOOL_OUTPUTS', '#D93025'],
  ['HISTORY', '#5F6368']
];

/** L1/L2/L3 的触发阈值，与后端 MultiTierContextCompactor 的默认阈值保持一致。 */
export const TIER_THRESHOLDS = [
  ['L1', 0.6, '#F9AB00'],
  ['L2', 0.78, '#E37400'],
  ['L3', 0.92, '#C5221F']
];

const CHART_WIDTH = 720;
const CHART_HEIGHT = 200;
const PADDING = { top: 14, right: 12, bottom: 22, left: 40 };

/**
 * 把「每次模型调用的预算快照」转成绘图点。
 *
 * <p>只保留真正带上下文快照的调用：历史运行或未触发压缩的调用可能没有该字段，
 * 用 0 补齐会画出并不存在的「水位归零」，所以直接跳过。
 */
export function buildWaterLevelSeries(models) {
  const points = [];
  (models ?? []).forEach((call, index) => {
    const context = call?.payload?.context;
    if (!context) return;
    const limit = Math.max(1, Number(context.modelContextLimit) || 0);
    const segments = context.segmentTokens ?? {};
    const total = SEGMENT_ORDER.reduce((sum, [key]) => sum + (Number(segments[key]) || 0), 0);
    points.push({
      index: points.length + 1,
      sourceIndex: index,
      name: call?.name ?? '',
      limit,
      segments,
      total,
      ratio: Math.min(1.35, total / limit),
      tier: context.appliedTier ?? 'L0_OBSERVE_ONLY',
      savedPercent: Number(context.savedPercent) || 0,
      context
    });
  });
  return points;
}

/** 生成某一层的堆叠多边形：上沿从左到右，下沿从右到左闭合成面。 */
function bandPolygon(points, layer, plotX, plotY) {
  if (points.length === 0) return '';
  const tops = [];
  points.forEach(point => {
    let cumulative = 0;
    for (let index = 0; index <= layer; index++) {
      cumulative += Number(point.segments[SEGMENT_ORDER[index][0]]) || 0;
    }
    tops.push(cumulative / point.limit);
  });
  const bottoms = [];
  points.forEach(point => {
    let cumulative = 0;
    for (let index = 0; index < layer; index++) {
      cumulative += Number(point.segments[SEGMENT_ORDER[index][0]]) || 0;
    }
    bottoms.push(cumulative / point.limit);
  });

  const up = points.map((point, position) => `${plotX(point.index)},${plotY(tops[position])}`);
  const down = points
    .map((point, position) => `${plotX(point.index)},${plotY(bottoms[position])}`)
    .reverse();
  return [...up, ...down].join(' ');
}

/**
 * 上下文压缩显微镜。
 *
 * <p>两块内容都来自真实持久化快照：
 * <ol>
 *   <li>多轮水位堆叠面积图：横轴是模型调用次序，纵轴是占模型窗口比例，叠 L1/L2/L3 阈值线；</li>
 *   <li>压缩前后对照：逐条列出 L1 卸载、L2 摘要合并范围与 L3 保留下来的工具原子对。</li>
 * </ol>
 * 点击 L1 的产物引用会通过受鉴权接口拉取真实原文，不做任何本地伪造。
 */
export default function ContextMicroscopePanel({ models }) {
  const { t } = useI18n();
  const points = useMemo(() => buildWaterLevelSeries(models), [models]);
  const [selected, setSelected] = useState(null);
  const [artifactRef, setArtifactRef] = useState(null);

  const current = useMemo(() => {
    if (points.length === 0) return null;
    if (selected == null) return points[points.length - 1];
    return points.find(point => point.index === selected) ?? points[points.length - 1];
  }, [points, selected]);

  const artifact = useQuery({
    queryKey: ['context-artifact', artifactRef],
    queryFn: () => contextArtifactsApi.read(artifactRef),
    enabled: Boolean(artifactRef),
    retry: 0
  });

  if (points.length === 0) {
    return (
      <Alert severity="info" data-testid="microscope-empty">
        {t('microscope.noData')}
      </Alert>
    );
  }

  const plotX = index => {
    const span = CHART_WIDTH - PADDING.left - PADDING.right;
    if (points.length === 1) return PADDING.left + span / 2;
    return PADDING.left + ((index - 1) / (points.length - 1)) * span;
  };
  const plotY = ratio => {
    const span = CHART_HEIGHT - PADDING.top - PADDING.bottom;
    return PADDING.top + (1 - Math.min(1.2, ratio) / 1.2) * span;
  };

  const detail = current?.context?.detail ?? null;
  const offloads = detail?.offloads ?? [];

  return (
    <Box data-testid="context-microscope">
      <Typography variant="caption" sx={{ fontWeight: 700, display: 'block' }}>
        {t('microscope.chartTitle')}
      </Typography>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.75 }}>
        {t('microscope.chartHint')}
      </Typography>

      <Box sx={{ overflowX: 'auto' }}>
        <svg
          viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}
          width="100%"
          height={CHART_HEIGHT}
          role="img"
          aria-label={t('microscope.chartTitle')}
          data-testid="water-level-chart"
        >
          <line
            x1={PADDING.left}
            y1={CHART_HEIGHT - PADDING.bottom}
            x2={CHART_WIDTH - PADDING.right}
            y2={CHART_HEIGHT - PADDING.bottom}
            stroke={googleTokens.outline}
          />
          <line
            x1={PADDING.left}
            y1={PADDING.top}
            x2={PADDING.left}
            y2={CHART_HEIGHT - PADDING.bottom}
            stroke={googleTokens.outline}
          />

          {[0, 0.3, 0.6, 0.9, 1.2].map(ratio => (
            <g key={ratio}>
              <line
                x1={PADDING.left}
                y1={plotY(ratio)}
                x2={CHART_WIDTH - PADDING.right}
                y2={plotY(ratio)}
                stroke="#EDEFF2"
                strokeDasharray="2 3"
              />
              <text
                x={PADDING.left - 6}
                y={plotY(ratio) + 3}
                fontSize="9"
                textAnchor="end"
                fill={googleTokens.textSecondary}
              >
                {Math.round(ratio * 100)}%
              </text>
            </g>
          ))}

          {TIER_THRESHOLDS.map(([tier, ratio, color]) => (
            <g key={tier} data-testid={`threshold-${tier}`}>
              <line
                x1={PADDING.left}
                y1={plotY(ratio)}
                x2={CHART_WIDTH - PADDING.right}
                y2={plotY(ratio)}
                stroke={color}
                strokeDasharray="6 4"
                strokeWidth="1"
              />
              <text
                x={CHART_WIDTH - PADDING.right - 2}
                y={plotY(ratio) - 3}
                fontSize="9"
                textAnchor="end"
                fill={color}
              >
                {t(`microscope.threshold.${tier.toLowerCase()}`)}
              </text>
            </g>
          ))}

          {SEGMENT_ORDER.map(([key, color], layer) => (
            <polygon
              key={key}
              data-testid={`segment-band-${key}`}
              points={bandPolygon(points, layer, plotX, plotY)}
              fill={color}
              fillOpacity="0.55"
              stroke={color}
              strokeWidth="0.6"
            />
          ))}

          {points.map(point => (
            <circle
              key={point.index}
              cx={plotX(point.index)}
              cy={plotY(point.ratio)}
              r={current?.index === point.index ? 4 : 2.5}
              fill={current?.index === point.index ? '#1A73E8' : '#5F6368'}
              role="button"
              tabIndex={0}
              aria-label={t('microscope.turn', { index: point.index })}
              onClick={() => setSelected(point.index)}
              onKeyDown={event => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  setSelected(point.index);
                }
              }}
              style={{ cursor: 'pointer' }}
            />
          ))}

          <text
            x={PADDING.left}
            y={CHART_HEIGHT - 6}
            fontSize="9"
            fill={googleTokens.textSecondary}
          >
            {t('microscope.turn', { index: 1 })}
          </text>
          <text
            x={CHART_WIDTH - PADDING.right}
            y={CHART_HEIGHT - 6}
            fontSize="9"
            textAnchor="end"
            fill={googleTokens.textSecondary}
          >
            {t('microscope.turn', { index: points.length })}
          </text>
        </svg>
      </Box>

      <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap', rowGap: 0.5, my: 1 }}>
        {SEGMENT_ORDER.map(([key, color]) => (
          <Chip
            key={key}
            size="small"
            variant="outlined"
            label={t(`context.segment.${key}`)}
            sx={{ borderColor: color, color, '& .MuiChip-label': { px: 0.75, fontSize: 11 } }}
          />
        ))}
      </Stack>

      <Divider sx={{ my: 1 }} />

      <Stack
        direction="row"
        spacing={1}
        sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}
      >
        <Typography sx={{ fontSize: 12.5, fontWeight: 700 }}>
          {t('microscope.turn', { index: current.index })}
        </Typography>
        <Chip
          size="small"
          label={t(`context.tier.${current.tier}`)}
          sx={{ bgcolor: '#E8F0FE', color: '#174EA6', fontWeight: 600 }}
        />
        <Typography variant="caption" color="text.secondary">
          {t('microscope.before', { tokens: current.context.beforeTokens ?? 0 })} →{' '}
          {t('microscope.after', { tokens: current.context.totalEstimatedTokens ?? 0 })} ·{' '}
          {t('microscope.saved', { percent: current.savedPercent })}
        </Typography>
      </Stack>

      <Box sx={{ mt: 1 }}>
        <Typography variant="caption" sx={{ fontWeight: 700, display: 'block' }}>
          {t('microscope.l1Title')}
        </Typography>
        {offloads.length === 0 ? (
          <Typography variant="caption" color="text.secondary">
            {t('microscope.l1Empty')}
          </Typography>
        ) : (
          <Stack spacing={0.5} sx={{ mt: 0.4 }} data-testid="microscope-offloads">
            {offloads.map(row => (
              <Stack
                key={`${row.toolCallId}:${row.artifactRef}`}
                direction="row"
                spacing={0.75}
                sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.4 }}
              >
                <Chip
                  size="small"
                  label={row.toolCallId}
                  sx={{ height: 19, fontSize: 10.5, fontFamily: googleTokens.mono }}
                />
                <Typography variant="caption" color="text.secondary">
                  {t('microscope.l1Row', {
                    chars: row.originalChars,
                    retained: row.retainedChars
                  })}
                </Typography>
                <Button
                  size="small"
                  variant="text"
                  data-testid={`artifact-open-${row.artifactRef}`}
                  onClick={() => setArtifactRef(row.artifactRef)}
                  sx={{ minHeight: 22, py: 0, fontSize: 11.5 }}
                >
                  {t('microscope.viewArtifact')}
                </Button>
              </Stack>
            ))}
          </Stack>
        )}
      </Box>

      <Box sx={{ mt: 1 }}>
        <Typography variant="caption" sx={{ fontWeight: 700, display: 'block' }}>
          {t('microscope.l2Title')}
        </Typography>
        {detail && detail.mergedMessageCount > 0 ? (
          <Typography variant="caption" color="text.secondary" data-testid="microscope-l2">
            {t('microscope.l2Range', {
              from: detail.mergedFrom,
              to: detail.mergedTo,
              count: detail.mergedMessageCount
            })}
            {current.context.summaryPreview
              ? ` · ${String(current.context.summaryPreview).slice(0, 200)}`
              : ''}
          </Typography>
        ) : (
          <Typography variant="caption" color="text.secondary">
            {t('microscope.l2Empty')}
          </Typography>
        )}
      </Box>

      <Box sx={{ mt: 1 }}>
        <Typography variant="caption" sx={{ fontWeight: 700, display: 'block' }}>
          {t('microscope.l3Title')}
        </Typography>
        {(detail?.protectedToolCallIds ?? []).length === 0 ? (
          <Typography variant="caption" color="text.secondary">
            {t('microscope.l3Empty')}
          </Typography>
        ) : (
          <Stack
            direction="row"
            spacing={0.5}
            sx={{ flexWrap: 'wrap', rowGap: 0.5, mt: 0.4 }}
            data-testid="microscope-l3"
          >
            <Typography variant="caption" color="text.secondary">
              {t('microscope.protectedCount', { count: detail.protectedToolCallIds.length })}
            </Typography>
            {detail.protectedToolCallIds.slice(0, 12).map(id => (
              <Chip
                key={id}
                size="small"
                label={id}
                sx={{ height: 19, fontSize: 10.5, fontFamily: googleTokens.mono }}
              />
            ))}
          </Stack>
        )}
      </Box>

      <Dialog
        open={Boolean(artifactRef)}
        onClose={() => setArtifactRef(null)}
        maxWidth="md"
        fullWidth
        data-testid="artifact-dialog"
      >
        <DialogTitle sx={{ fontSize: 15 }}>
          {t('microscope.artifactDialog', { ref: artifactRef ?? '' })}
        </DialogTitle>
        <DialogContent dividers>
          {artifact.isLoading && (
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <CircularProgress size={18} />
              <Typography variant="body2">{t('microscope.artifactLoading')}</Typography>
            </Stack>
          )}
          {artifact.error && (
            <Alert severity="error">
              {t('microscope.artifactFailed', { message: artifact.error.message })}
            </Alert>
          )}
          {artifact.data && (
            <Stack spacing={1}>
              <Typography variant="caption" color="text.secondary">
                {t('microscope.artifactChars', { count: artifact.data.charCount ?? 0 })} ·{' '}
                {artifact.data.title}
              </Typography>
              <Readout mono maxHeight={420}>
                {artifact.data.content}
              </Readout>
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setArtifactRef(null)}>{t('common.close')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
