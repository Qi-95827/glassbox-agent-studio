import {
  Box,
  Button,
  Chip,
  Divider,
  Drawer,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography
} from '@mui/material';
import LaunchRounded from '@mui/icons-material/LaunchRounded';
import CheckCircleRounded from '@mui/icons-material/CheckCircleRounded';
import CancelRounded from '@mui/icons-material/CancelRounded';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';
import { duration } from '../../utils/format';

function JsonBlock({ value, maxHeight = 220 }) {
  return (
    <Box
      component="pre"
      sx={{
        m: 0,
        p: 1.5,
        bgcolor: '#F8F9FA',
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 2,
        fontSize: 11.5,
        lineHeight: 1.55,
        overflow: 'auto',
        maxHeight,
        fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace'
      }}
    >
      {typeof value === 'string' ? value : JSON.stringify(value, null, 2)}
    </Box>
  );
}

function Section({ title, children }) {
  return (
    <Box>
      <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.8 }}>{title}</Typography>
      {children}
    </Box>
  );
}

function CheckRow({ check }) {
  const denied = check?.status === 'DENY';
  return (
    <Stack direction="row" spacing={0.8} sx={{ alignItems: 'flex-start', py: 0.35 }}>
      {denied ? (
        <CancelRounded sx={{ fontSize: 15, color: '#B3261E', mt: '1px' }} />
      ) : (
        <CheckCircleRounded sx={{ fontSize: 15, color: '#188038', mt: '1px' }} />
      )}
      <Box>
        <Typography variant="caption" sx={{ fontWeight: 600 }}>
          {check?.label || check?.gate} · {check?.status}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
          {check?.detail}
        </Typography>
      </Box>
    </Stack>
  );
}

const GATEWAY_ROOTS = ['toolCalls', 'attacks'];
const METRIC_KEYS = {
  needleRetention: 'evalLab.metric.needleRetention',
  compressionSavedPercent: 'evalLab.metric.compressionSavedPercent',
  recallAtK: 'evalLab.metric.recallAtK',
  precisionAtK: 'evalLab.metric.precisionAtK',
  mrr: 'evalLab.metric.mrr',
  blockRate: 'evalLab.metric.blockRate',
  gate55CaptureRate: 'evalLab.metric.gate55CaptureRate',
  injectionDefenseRate: 'evalLab.metric.injectionDefenseRate',
  selectionAccuracy: 'evalLab.metric.selectionAccuracy',
  gatewayPassRate: 'evalLab.metric.gatewayPassRate',
  duplicateRate: 'evalLab.metric.duplicateRate',
  validationFirstPassRate: 'evalLab.metric.validationFirstPassRate',
  handoffPassRate: 'evalLab.metric.handoffPassRate',
  fileMatchesExpected: 'evalLab.metric.fileMatchesExpected'
};

/** 逐题玻璃盒穿透：题目 → 输出 → 安检门 → 压缩/记忆/规划 → 双轨裁判。 */
export default function EvalCaseDetailDrawer({ open, item, categoryLabel, onClose, onOpenRun }) {
  const { t, locale } = useI18n();
  const title = item ? (locale === 'en-US' ? item.titleEn : item.titleZh) : '';
  const trace = item?.trace ?? {};
  const judge = item?.judgeVerdict ?? {};
  const calls = GATEWAY_ROOTS.flatMap(key => (Array.isArray(trace[key]) ? trace[key] : []));
  const matches = Array.isArray(trace.matches) ? trace.matches : [];
  const plans = Array.isArray(trace.plans) ? trace.plans : [];

  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      slotProps={{
        paper: { sx: { width: '100%', maxWidth: 720, backgroundImage: 'none' } }
      }}
    >
      <Box sx={{ p: 2.5 }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.5, flexWrap: 'wrap' }}>
          <Typography sx={{ fontSize: 17, fontWeight: 600, flex: 1 }}>{title}</Typography>
          <Chip
            size="small"
            color={item?.passed ? 'success' : 'default'}
            label={item?.passed ? t('evalLab.case.passed') : t('evalLab.case.failed')}
          />
        </Stack>
        <Typography variant="caption" color="text.secondary">
          {categoryLabel?.[item?.category] ?? item?.category} · {item?.sampleId} ·{' '}
          {t('evalLab.case.repeat', { index: item?.repeatIndex ?? 1 })} ·{' '}
          {duration(item?.durationMs)}
        </Typography>
        <Stack direction="row" spacing={1} sx={{ mt: 1.2, flexWrap: 'wrap' }}>
          {item?.linkedRunId && (
            <Button
              size="small"
              variant="outlined"
              startIcon={<LaunchRounded />}
              onClick={() => onOpenRun?.(item.linkedRunId)}
            >
              {t('evalLab.case.openRun')}
            </Button>
          )}
          <Chip
            size="small"
            variant="outlined"
            label={t('evalLab.case.score', {
              score: `${Math.round((item?.codeScore ?? 0) * 100)}%`
            })}
          />
          <Chip
            size="small"
            variant="outlined"
            label={t('evalLab.case.cost', { cost: Number(item?.costUsd ?? 0).toFixed(5) })}
          />
        </Stack>
      </Box>
      <Divider />
      <Stack spacing={2.2} sx={{ p: 2.5 }}>
        <Section title={t('evalLab.detail.input')}>
          <JsonBlock value={trace.prompt || t('common.notAvailable')} maxHeight={160} />
        </Section>

        <Section title={t('evalLab.case.rawOutput')}>
          <JsonBlock value={item?.rawOutput || t('common.notAvailable')} maxHeight={220} />
        </Section>

        <Section title={t('evalLab.case.metrics')}>
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr 1fr', sm: '1fr 1fr 1fr' },
              gap: 1
            }}
          >
            {Object.entries(item?.metrics ?? {})
              .filter(([, value]) => typeof value === 'number' || typeof value === 'boolean')
              .map(([key, value]) => (
                <Box
                  key={key}
                  sx={{ p: 1, border: `1px solid ${googleTokens.outline}`, borderRadius: 2 }}
                >
                  <Typography variant="caption" color="text.secondary" noWrap>
                    {METRIC_KEYS[key] ? t(METRIC_KEYS[key]) : key}
                  </Typography>
                  <Typography sx={{ fontSize: 15, fontWeight: 500 }}>
                    {typeof value === 'boolean'
                      ? value
                        ? 'true'
                        : 'false'
                      : Number(value).toFixed(3)}
                  </Typography>
                </Box>
              ))}
          </Box>
        </Section>

        <Section title={t('evalLab.case.assertions')}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>{t('evalLab.case.type')}</TableCell>
                <TableCell>{t('evalLab.case.expected')}</TableCell>
                <TableCell>{t('evalLab.case.actual')}</TableCell>
                <TableCell>{t('common.status')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(item?.assertions ?? []).map((row, index) => (
                <TableRow key={`${row.type}-${index}`}>
                  <TableCell sx={{ fontSize: 11.5 }}>{row.type}</TableCell>
                  <TableCell sx={{ fontSize: 11.5 }}>{String(row.expected ?? '')}</TableCell>
                  <TableCell sx={{ fontSize: 11.5, maxWidth: 180 }}>
                    {typeof row.actual === 'object'
                      ? JSON.stringify(row.actual)
                      : String(row.actual ?? '')}
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      color={row.passed ? 'success' : 'error'}
                      label={row.passed ? 'PASS' : 'FAIL'}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {(item?.assertions ?? []).length === 0 && (
            <Typography variant="caption" color="text.secondary">
              {t('common.notAvailable')}
            </Typography>
          )}
        </Section>

        <Section title={t('evalLab.detail.gateway')}>
          {calls.length === 0 && <JsonBlock value={t('common.notAvailable')} maxHeight={80} />}
          {calls.map((call, index) => (
            <Box key={`${call.name}-${index}`} sx={{ mb: 1 }}>
              <Stack direction="row" spacing={0.8} sx={{ alignItems: 'center', mb: 0.3 }}>
                <Typography variant="caption" sx={{ fontWeight: 600 }}>
                  {call.name}
                </Typography>
                <Chip size="small" label={call.status} variant="outlined" />
                {call.path && (
                  <Typography variant="caption" color="text.secondary">
                    {call.path}
                  </Typography>
                )}
              </Stack>
              {(call.checks ?? []).map((check, checkIndex) => (
                <CheckRow key={`${check.gate}-${checkIndex}`} check={check} />
              ))}
            </Box>
          ))}
        </Section>

        <Section title={t('evalLab.detail.compaction')}>
          {trace.needles ? (
            <JsonBlock
              value={{
                tier: item?.metrics?.compactionTier,
                beforeTokens: item?.metrics?.beforeTokens,
                afterTokens: item?.metrics?.afterTokens,
                savedPercent: item?.metrics?.compressionSavedPercent,
                needles: trace.needles,
                retained: trace.retainedNeedles,
                segmentTokens: trace.segmentTokens,
                summaryPreview: trace.summaryPreview
              }}
            />
          ) : (
            <JsonBlock value={t('common.notAvailable')} maxHeight={70} />
          )}
        </Section>

        <Section title={t('evalLab.detail.memory')}>
          {matches.length ? (
            <JsonBlock value={matches} />
          ) : (
            <JsonBlock value={t('common.notAvailable')} maxHeight={70} />
          )}
        </Section>

        <Section title={t('evalLab.detail.planning')}>
          {plans.length || trace.handoffEnvelope ? (
            <JsonBlock value={{ plans, handoffEnvelope: trace.handoffEnvelope }} />
          ) : (
            <JsonBlock value={t('common.notAvailable')} maxHeight={70} />
          )}
        </Section>

        <Section title={t('evalLab.detail.judge')}>
          {judge.mode === 'DISABLED' ? (
            <Typography variant="caption" color="text.secondary">
              {t('evalLab.case.judgeDisabled')}
            </Typography>
          ) : (
            <JsonBlock value={judge} maxHeight={260} />
          )}
        </Section>

        <Section title={t('evalLab.detail.setup')}>
          <JsonBlock value={trace.setup ?? {}} maxHeight={220} />
        </Section>
      </Stack>
    </Drawer>
  );
}
