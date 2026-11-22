import { useState } from 'react';
import { Alert, Box, Button, Chip, Collapse, Stack, Typography } from '@mui/material';
import CheckCircleRounded from '@mui/icons-material/CheckCircleRounded';
import CancelRounded from '@mui/icons-material/CancelRounded';
import RemoveCircleOutlineRounded from '@mui/icons-material/RemoveCircleOutlineRounded';
import HelpOutlineRounded from '@mui/icons-material/HelpOutlineRounded';
import { TraceSection, Readout } from '../../../components/WorkbenchDialog';
import EmptyState from '../../../components/EmptyState';
import { toolNameLabel } from '../../../constants/labels';
import { duration } from '../../../utils/format';
import { googleTokens } from '../../../theme/googleTheme';
import { useI18n } from '../../../i18n/I18nProvider';
import { checkTitle, gatewayCheckRules } from './checkExplanations';

function gateTone(status) {
  if (status === 'PASS') {
    return {
      bg: googleTokens.successBg,
      fg: googleTokens.successFg,
      border: '#CEEAD6',
      icon: <CheckCircleRounded sx={{ fontSize: 15, color: googleTokens.successFg }} />
    };
  }
  if (status === 'DENY') {
    return {
      bg: googleTokens.errorBg,
      fg: googleTokens.errorFg,
      border: '#F2B8B5',
      icon: <CancelRounded sx={{ fontSize: 15, color: googleTokens.errorFg }} />
    };
  }
  return {
    bg: googleTokens.neutralBg,
    fg: googleTokens.neutralFg,
    border: googleTokens.outline,
    icon: <RemoveCircleOutlineRounded sx={{ fontSize: 15, color: googleTokens.neutralFg }} />
  };
}

function ToolCallCard({ call, index }) {
  const { t, locale } = useI18n();
  const [showRules, setShowRules] = useState(false);
  const trace = call.trace;
  const checks = trace?.gatewayChecks ?? [];
  const failed = call.status === 'FAILED';
  const rules = gatewayCheckRules(locale);

  return (
    <Box
      sx={{
        p: 2,
        borderRadius: 3,
        bgcolor: '#FFFFFF',
        border: `1px solid ${failed ? '#F2B8B5' : googleTokens.outline}`
      }}
    >
      <Stack
        direction="row"
        spacing={0.75}
        sx={{
          alignItems: 'center',
          justifyContent: 'space-between',
          flexWrap: 'wrap',
          mb: 1.25
        }}
      >
        <Typography variant="subtitle2" sx={{ fontSize: 14.5 }}>
          #{index + 1} · {toolNameLabel(call.name, locale)}
          {call.version != null ? ` v${call.version}` : ''}
        </Typography>
        <Stack direction="row" spacing={0.5}>
          <Chip
            size="small"
            label={call.status}
            sx={{
              bgcolor: failed ? googleTokens.errorBg : googleTokens.successBg,
              color: failed ? googleTokens.errorFg : googleTokens.successFg,
              fontWeight: 600
            }}
          />
          <Chip size="small" label={duration(call.durationMs)} />
        </Stack>
      </Stack>

      <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
        {t('gateway.inputTitle')}
      </Typography>
      <Box sx={{ mt: 0.45, mb: 1.25 }}>
        <Readout mono maxHeight={120}>
          {trace?.inputArgs || t('gateway.noInput')}
        </Readout>
      </Box>

      <Stack
        direction="row"
        sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 0.6 }}
      >
        <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
          {t('gateway.checksTitle')}
        </Typography>
        {checks.length > 0 && (
          <Button
            size="small"
            startIcon={<HelpOutlineRounded sx={{ fontSize: 14 }} />}
            onClick={() => setShowRules(prev => !prev)}
            sx={{ py: 0.15, px: 0.75, fontSize: 12, minHeight: 26 }}
          >
            {showRules ? t('glassbox.collapseRules') : t('glassbox.expandRules')}
          </Button>
        )}
      </Stack>

      {checks.length > 0 ? (
        <Stack spacing={0.7} sx={{ mb: 1.25 }}>
          {checks.map((chk, idx) => {
            const tone = gateTone(chk.status);
            const rule = rules[chk.gate];
            // 中文沿用后端历史落库 label（含测试与历史记录），英文按 gate 重新渲染标题。
            const title =
              (locale === 'en-US' ? checkTitle(chk.gate, 'gateway', locale) : null) || chk.label;
            return (
              <Box
                key={`${chk.gate}:${idx}`}
                sx={{
                  p: 1.05,
                  borderRadius: 2,
                  bgcolor: chk.status === 'DENY' ? '#FFF8F7' : '#FAFAFA',
                  border: `1px solid ${tone.border}`
                }}
              >
                <Stack
                  direction="row"
                  spacing={0.75}
                  sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.45 }}
                >
                  <Chip
                    size="small"
                    icon={tone.icon}
                    label={`${title} · ${chk.status}`}
                    sx={{ bgcolor: tone.bg, color: tone.fg, fontWeight: 600 }}
                  />
                  <Typography
                    variant="caption"
                    sx={{
                      color: chk.status === 'DENY' ? googleTokens.errorFg : googleTokens.text,
                      lineHeight: 1.5,
                      flex: 1,
                      minWidth: 180
                    }}
                  >
                    {chk.detail || t('gateway.checkDone')}
                  </Typography>
                </Stack>

                {rule && (
                  <Collapse in={showRules}>
                    <Box
                      sx={{
                        mt: 0.75,
                        pt: 0.7,
                        borderTop: `1px dashed ${googleTokens.outline}`,
                        color: googleTokens.textSecondary
                      }}
                    >
                      <Typography variant="caption" sx={{ display: 'block', lineHeight: 1.55 }}>
                        <strong>{t('gateway.why')}</strong>
                        {rule.purpose}
                      </Typography>
                      <Typography variant="caption" sx={{ display: 'block', lineHeight: 1.55 }}>
                        <strong>{t('glassbox.passRule')}</strong>
                        {rule.passRule}
                      </Typography>
                      <Typography variant="caption" sx={{ display: 'block', lineHeight: 1.55 }}>
                        <strong>{t('glassbox.failRule')}</strong>
                        {rule.failRule}
                      </Typography>
                    </Box>
                  </Collapse>
                )}
              </Box>
            );
          })}
        </Stack>
      ) : (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1.25 }}>
          {t('gateway.noChecks')}
        </Typography>
      )}

      <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
        {t('gateway.resultTitle')}
      </Typography>
      {call.errorMessage && (
        <Alert severity="error" sx={{ mt: 0.5, mb: 0.5 }}>
          {call.errorMessage}
        </Alert>
      )}
      <Box sx={{ mt: 0.45 }}>
        <Readout mono maxHeight={180}>
          {trace?.fullResult || call.result || t('gateway.noResult')}
        </Readout>
      </Box>
      {call.resourceVersion && (
        <Box sx={{ mt: 0.75 }}>
          <TraceSection title={t('gateway.receipt')} dense>
            <Readout mono maxHeight={120}>
              {call.resourceVersion}
            </Readout>
          </TraceSection>
        </Box>
      )}
    </Box>
  );
}

/** 玻璃盒面板三：ToolGateway 三段式透明卡片（入参 JSON → 安检门与判定依据 → 完整返回值与回执）。 */
export default function ToolGatewayPanel({ tools }) {
  const { t } = useI18n();
  if (!tools || tools.length === 0) {
    return (
      <EmptyState
        compact
        title={t('gateway.emptyTitle')}
        description={t('gateway.emptyDescription')}
      />
    );
  }

  return (
    <Stack spacing={1.5}>
      {tools.map((call, index) => (
        <ToolCallCard key={call.id} call={call} index={index} />
      ))}
    </Stack>
  );
}
