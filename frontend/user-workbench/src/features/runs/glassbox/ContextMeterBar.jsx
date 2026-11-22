import { Box, Chip, Stack, Typography } from '@mui/material';
import { googleTokens } from '../../../theme/googleTheme';
import { useI18n } from '../../../i18n/I18nProvider';

const SEGMENTS = [
  ['SYSTEM', '#1A73E8'],
  ['SKILLS', '#8430CE'],
  ['MEMORY', '#0B8043'],
  ['HANDOFF', '#B06000'],
  ['TOOL_OUTPUTS', '#D93025'],
  ['HISTORY', '#5F6368']
];

/**
 * 玻璃盒上下文占比条：纯 CSS 分段，不注入 HTML；数据来自每次 ModelCall 的预算快照。
 */
export default function ContextMeterBar({ context }) {
  const { t } = useI18n();
  if (!context) return null;
  const limit = Math.max(1, context.modelContextLimit || 0);
  const segments = context.segmentTokens || {};
  const total = Math.max(
    1,
    Object.values(segments).reduce((sum, value) => sum + (Number(value) || 0), 0)
  );
  const tier = context.appliedTier || 'L0_OBSERVE_ONLY';

  return (
    <Box
      sx={{
        p: 1.25,
        borderRadius: 2.5,
        bgcolor: '#FFFFFF',
        border: `1px solid ${googleTokens.outline}`
      }}
    >
      <Stack
        direction="row"
        spacing={0.75}
        sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5, mb: 0.75 }}
      >
        <Typography variant="caption" sx={{ fontWeight: 700 }}>
          {t('context.title')}
        </Typography>
        <Chip
          size="small"
          label={t(`context.tier.${tier}`)}
          sx={{ bgcolor: '#E8F0FE', color: '#174EA6', fontWeight: 600 }}
        />
        <Typography variant="caption" color="text.secondary">
          {t('context.before', { tokens: context.beforeTokens ?? 0 })} →{' '}
          {t('context.after', { tokens: context.totalEstimatedTokens ?? 0 })}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {t('context.limit', { limit })}
        </Typography>
        <Chip
          size="small"
          variant="outlined"
          label={t('context.saved', { percent: context.savedPercent ?? 0 })}
        />
      </Stack>

      <Box
        sx={{
          display: 'flex',
          width: '100%',
          height: 10,
          borderRadius: 9999,
          overflow: 'hidden',
          bgcolor: '#F1F3F4'
        }}
        role="img"
        aria-label={t('context.title')}
      >
        {SEGMENTS.map(([key, color]) => {
          const value = Number(segments[key]) || 0;
          if (value <= 0) return null;
          return (
            <Box
              key={key}
              title={`${t(`context.segment.${key}`)} · ${value} tokens`}
              sx={{ width: `${(value / total) * 100}%`, bgcolor: color }}
            />
          );
        })}
      </Box>

      <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap', rowGap: 0.5, mt: 0.75 }}>
        {SEGMENTS.filter(([key]) => (Number(segments[key]) || 0) > 0).map(([key, color]) => (
          <Chip
            key={key}
            size="small"
            variant="outlined"
            label={`${t(`context.segment.${key}`)} ${segments[key]}`}
            sx={{
              borderColor: color,
              color,
              '& .MuiChip-label': { px: 0.75, fontSize: 11 }
            }}
          />
        ))}
      </Stack>

      {(context.offloadedToolCallIds || []).length > 0 && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
          {t('context.offload', { count: context.offloadedToolCallIds.length })}
        </Typography>
      )}
      {context.summaryPreview && (
        <Typography
          variant="caption"
          color="text.secondary"
          sx={{ display: 'block', mt: 0.5, whiteSpace: 'pre-wrap' }}
        >
          {t('context.summary', { preview: String(context.summaryPreview).slice(0, 240) })}
        </Typography>
      )}
    </Box>
  );
}
