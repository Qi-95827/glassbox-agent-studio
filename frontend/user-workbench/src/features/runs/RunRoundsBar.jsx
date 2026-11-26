import { Box, Button, Chip, Stack, Typography } from '@mui/material';
import CompareArrowsRounded from '@mui/icons-material/CompareArrowsRounded';
import { googleTokens } from '../../theme/googleTheme';
import { statusLabel } from '../../constants/labels';
import { useI18n } from '../../i18n/I18nProvider';

/**
 * 同任务轮次切换条。
 *
 * <p>只在任务真的有多轮时出现：按轮次升序展示「第 N 轮 · 状态」，当前轮高亮；点任意轮次切到该轮完整的
 * DAG、玻璃盒与交付视图，右侧按钮直接打开既有双跑对照比较「上一轮 vs 当前轮」。
 */
export default function RunRoundsBar({
  rounds = [],
  currentRunId,
  onSelect,
  onComparePrevious,
  compareEnabled = true
}) {
  const { t, locale } = useI18n();
  if (!rounds || rounds.length <= 1) return null;
  const index = rounds.findIndex(round => round.runId === currentRunId);
  const previous = index > 0 ? rounds[index - 1] : null;

  return (
    <Box
      data-testid="run-rounds-bar"
      sx={{
        px: { xs: 1, sm: 2 },
        py: 0.75,
        bgcolor: '#F8F9FA',
        borderBottom: `1px solid ${googleTokens.outline}`
      }}
    >
      <Stack
        direction="row"
        spacing={0.75}
        sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.75 }}
        aria-label={t('run.round.switchAria')}
      >
        {rounds.map(round => {
          const active = round.runId === currentRunId;
          return (
            <Chip
              key={round.runId}
              size="small"
              data-testid={`run-round-${round.roundIndex}`}
              clickable={!active}
              color={active ? 'primary' : 'default'}
              variant={active ? 'filled' : 'outlined'}
              onClick={() => {
                if (!active) onSelect?.(round.runId);
              }}
              label={`${t('run.round.label', { round: round.roundIndex })} · ${statusLabel(
                round.status,
                locale
              )}${active ? ` · ${t('run.round.current')}` : ''}`}
              title={round.feedbackSummary || undefined}
            />
          );
        })}
        <Box sx={{ flex: 1 }} />
        {previous ? (
          <Button
            size="small"
            variant="outlined"
            startIcon={<CompareArrowsRounded fontSize="small" />}
            data-testid="run-rounds-compare"
            disabled={!compareEnabled}
            onClick={() => onComparePrevious?.(previous.runId)}
            title={t('run.round.compareHint', {
              from: previous.roundIndex,
              to: rounds[index]?.roundIndex ?? previous.roundIndex + 1
            })}
          >
            {t('run.iteration.comparePrevious')}
          </Button>
        ) : (
          <Typography variant="caption" color="text.secondary">
            {t('run.round.noPrevious')}
          </Typography>
        )}
      </Stack>
    </Box>
  );
}
