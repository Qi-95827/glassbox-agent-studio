import { useMemo } from 'react';
import { Box, Chip, Stack, Tooltip, Typography } from '@mui/material';
import CheckRounded from '@mui/icons-material/CheckRounded';
import CloseRounded from '@mui/icons-material/CloseRounded';
import RemoveRounded from '@mui/icons-material/RemoveRounded';
import ErrorOutlineRounded from '@mui/icons-material/ErrorOutlineRounded';
import { googleTokens } from '../../../theme/googleTheme';
import { useI18n } from '../../../i18n/I18nProvider';
import { checkTitle, gatewayCheckRules } from './checkExplanations';

/** 单道安检门的语义色。风险分级用 LOW/MEDIUM/HIGH 作为状态值，不能按 PASS/DENY 处理。 */
function tone(status) {
  switch (status) {
    case 'PASS':
    case 'LOW':
      return {
        bg: googleTokens.successBg,
        fg: googleTokens.successFg,
        border: '#CEEAD6',
        icon: <CheckRounded sx={{ fontSize: 14 }} />
      };
    case 'MEDIUM':
      return {
        bg: '#FEF7E0',
        fg: '#B06000',
        border: '#FEE8A8',
        icon: <ErrorOutlineRounded sx={{ fontSize: 14 }} />
      };
    case 'DENY':
    case 'HIGH':
      return {
        bg: googleTokens.errorBg,
        fg: googleTokens.errorFg,
        border: '#F2B8B5',
        icon: <CloseRounded sx={{ fontSize: 14 }} />
      };
    default:
      return {
        bg: googleTokens.neutralBg,
        fg: googleTokens.neutralFg,
        border: googleTokens.outline,
        icon: <RemoveRounded sx={{ fontSize: 14 }} />
      };
  }
}

/**
 * 默认选中被拦截的那道门；没有拦截时选中最后一道。
 *
 * <p>用户最想知道的是「为什么停在这里」，而不是从头读一遍全部通过记录。
 */
export function defaultGateIndex(checks) {
  if (!checks || checks.length === 0) return -1;
  const denied = checks.findIndex(item => item.status === 'DENY' || item.status === 'HIGH');
  return denied >= 0 ? denied : checks.length - 1;
}

/**
 * ToolGateway 安检门横向过检流水线。
 *
 * <p>数据完全来自服务端落库的逐关判定，不做任何本地推断：关卡顺序就是后端实际记录的顺序，
 * 状态就是后端写入的 status。横向流水线解决的问题是「一眼看出停在第几关」，
 * 而不是把六道门简化成一句「已拦截」。
 *
 * @param checks 服务端记录的逐关判定。
 * @param selected 当前展开的关卡下标；为 -1 时不展开。
 * @param onSelect 点击关卡的回调，参数为下标。
 */
export default function GatePipeline({ checks = [], selected = -1, onSelect }) {
  const { t, locale } = useI18n();
  const rules = useMemo(() => gatewayCheckRules(locale), [locale]);
  const active = checks[selected];
  const activeTone = active ? tone(active.status) : null;
  const activeRule = active ? rules[active.gate] : null;

  if (checks.length === 0) {
    return (
      <Typography variant="caption" color="text.secondary">
        {t('gateway.noChecks')}
      </Typography>
    );
  }

  return (
    <Box data-testid="gate-pipeline">
      <Stack
        direction="row"
        sx={{ alignItems: 'stretch', flexWrap: 'wrap', rowGap: 0.75, columnGap: 0 }}
      >
        {checks.map((check, index) => {
          const colors = tone(check.status);
          const isActive = index === selected;
          return (
            <Box key={`${check.gate}:${index}`} sx={{ display: 'flex', alignItems: 'center' }}>
              {index > 0 && (
                <Box
                  aria-hidden
                  sx={{
                    width: 14,
                    height: 2,
                    bgcolor: colors.border,
                    flexShrink: 0
                  }}
                />
              )}
              <Tooltip title={check.detail || check.label || ''} enterDelay={350}>
                <Box
                  component="button"
                  type="button"
                  data-testid={`gate-stage-${check.gate}`}
                  data-status={check.status}
                  aria-pressed={isActive}
                  onClick={() => onSelect?.(isActive ? -1 : index)}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 0.5,
                    px: 0.9,
                    py: 0.55,
                    borderRadius: 2,
                    cursor: 'pointer',
                    bgcolor: colors.bg,
                    border: `1px solid ${isActive ? colors.fg : colors.border}`,
                    boxShadow: isActive ? `0 0 0 2px ${colors.bg}` : 'none',
                    font: 'inherit',
                    maxWidth: 190
                  }}
                >
                  <Box sx={{ color: colors.fg, display: 'flex' }}>{colors.icon}</Box>
                  <Typography
                    sx={{
                      fontSize: 11.5,
                      fontWeight: 600,
                      color: colors.fg,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis'
                    }}
                  >
                    {index + 1}. {checkTitle(check.gate, 'gateway', locale) || check.label}
                  </Typography>
                </Box>
              </Tooltip>
            </Box>
          );
        })}
      </Stack>

      {active && (
        <Box
          data-testid="gate-detail"
          sx={{
            mt: 0.9,
            p: 1.1,
            borderRadius: 2,
            bgcolor: '#FAFAFA',
            border: `1px solid ${activeTone.border}`
          }}
        >
          <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', mb: 0.4 }}>
            <Chip
              size="small"
              label={active.status}
              sx={{ bgcolor: activeTone.bg, color: activeTone.fg, fontWeight: 700, height: 20 }}
            />
            <Typography sx={{ fontSize: 12.5, fontWeight: 600 }}>
              {checkTitle(active.gate, 'gateway', locale) || active.label}
            </Typography>
          </Stack>
          <Typography variant="caption" sx={{ display: 'block', lineHeight: 1.55 }}>
            {active.detail || t('gateway.checkDone')}
          </Typography>
          {activeRule && (
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block', mt: 0.4, lineHeight: 1.55 }}
            >
              <strong>{t('gateway.why')}</strong>
              {activeRule.purpose}
            </Typography>
          )}
        </Box>
      )}
    </Box>
  );
}
