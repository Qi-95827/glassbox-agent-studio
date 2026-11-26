import { useState } from 'react';
import { Alert, Box, Chip, Stack, Typography } from '@mui/material';
import { TraceSection, Readout } from '../../../components/WorkbenchDialog';
import EmptyState from '../../../components/EmptyState';
import GatePipeline, { defaultGateIndex } from './GatePipeline';
import { toolNameLabel } from '../../../constants/labels';
import { duration } from '../../../utils/format';
import { googleTokens } from '../../../theme/googleTheme';
import { useI18n } from '../../../i18n/I18nProvider';

function ToolCallCard({ call, index }) {
  const { t, locale } = useI18n();
  const trace = call.trace;
  const checks = trace?.gatewayChecks ?? [];
  const failed = call.status === 'FAILED';
  const [selectedGate, setSelectedGate] = useState(() => defaultGateIndex(checks));

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

      <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
        {t('gateway.checksTitle')}
      </Typography>
      <Box sx={{ mt: 0.45, mb: 1.25 }}>
        {/* 横向过检流水线：一眼看出停在第几道门，点击关卡展开该关的判定与原理。 */}
        <GatePipeline checks={checks} selected={selectedGate} onSelect={setSelectedGate} />
      </Box>

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
