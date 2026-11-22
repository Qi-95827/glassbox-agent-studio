import { useState } from 'react';
import { Box, Button, Chip, Collapse, Stack, Typography } from '@mui/material';
import CheckCircleRounded from '@mui/icons-material/CheckCircleRounded';
import CancelRounded from '@mui/icons-material/CancelRounded';
import RemoveCircleOutlineRounded from '@mui/icons-material/RemoveCircleOutlineRounded';
import InsertDriveFileOutlined from '@mui/icons-material/InsertDriveFileOutlined';
import HelpOutlineRounded from '@mui/icons-material/HelpOutlineRounded';
import { TraceSection, Readout } from '../../../components/WorkbenchDialog';
import EmptyState from '../../../components/EmptyState';
import MarkdownView from '../../../components/MarkdownView';
import { localizeNodeLabel, toolNameLabel } from '../../../constants/labels';
import { clock, shortId } from '../../../utils/format';
import { googleTokens } from '../../../theme/googleTheme';
import { useI18n } from '../../../i18n/I18nProvider';
import { checkTitle, describeHandoffCheckEvidence, handoffCheckRules } from './checkExplanations';
import { cleanBulletMarkdown, readableHandoffSummary } from './textClean';

function checkTone(status) {
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

function EnvelopeCard({ record, direction, defaultExpandBody = false }) {
  const { t, locale } = useI18n();
  const [showRules, setShowRules] = useState(false);
  const sender = localizeNodeLabel(
    record.senderNodeLabel || record.dependencyNodeLabel || t('glassbox.sender'),
    locale
  );
  const receiver = localizeNodeLabel(record.receiverNodeLabel || t('glassbox.receiver'), locale);
  const rules = handoffCheckRules(locale);
  const structured = record.structured;
  const summaryText = structured
    ? readableHandoffSummary(structured.summary, record.sourceResult, 220)
    : '';

  return (
    <Box
      sx={{
        p: 2,
        borderRadius: 3,
        bgcolor: '#FFFFFF',
        border: `1px solid ${googleTokens.outline}`
      }}
    >
      <Stack
        direction="row"
        spacing={0.75}
        sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', mb: 0.75 }}
      >
        <Typography variant="subtitle2" sx={{ fontSize: 14.5 }}>
          {direction === 'outbound' ? t('glassbox.outbound') : t('glassbox.inbound')} · {sender} →{' '}
          {receiver}
        </Typography>
        <Chip
          size="small"
          label={clock(record.acceptedAt, locale) || t('glassbox.recorded')}
          sx={{ bgcolor: googleTokens.successBg, color: googleTokens.successFg }}
        />
      </Stack>

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1.25 }}>
        {t('glassbox.sender')} #{shortId(record.senderAgentId)} ({t('glassbox.attempt')} #
        {shortId(record.senderAttemptId)}) → {t('glassbox.receiver')} #
        {shortId(record.receiverAgentId)} ({t('glassbox.attempt')} #
        {shortId(record.receiverAttemptId)})
      </Typography>

      {structured && (
        <Stack spacing={1.25} sx={{ mb: 1.5 }}>
          <Box
            sx={{
              p: 1.25,
              borderRadius: 2,
              bgcolor: '#F8F9FA',
              border: `1px solid ${googleTokens.outline}`
            }}
          >
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ fontWeight: 600, display: 'block', mb: 0.35 }}
            >
              {t('glassbox.summary')}
            </Typography>
            <Typography variant="body2" sx={{ lineHeight: 1.65 }}>
              {summaryText}
            </Typography>
          </Box>

          {(structured.artifacts ?? []).length > 0 && (
            <Box>
              <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                {t('glassbox.artifacts')}
              </Typography>
              <Stack direction="row" spacing={0.6} sx={{ flexWrap: 'wrap', rowGap: 0.6, mt: 0.5 }}>
                {structured.artifacts.map(file => (
                  <Chip
                    key={file}
                    size="small"
                    icon={<InsertDriveFileOutlined sx={{ fontSize: 14 }} />}
                    label={file}
                    sx={{ fontFamily: googleTokens.mono, fontSize: 11.5 }}
                  />
                ))}
              </Stack>
            </Box>
          )}

          {(structured.decisions ?? []).length > 0 && (
            <Box>
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ fontWeight: 600, display: 'block', mb: 0.4 }}
              >
                {t('glassbox.decisions')}
              </Typography>
              <Stack spacing={0.45}>
                {structured.decisions.map((item, idx) => (
                  <Box
                    key={`dec:${idx}`}
                    sx={{
                      pl: 1.1,
                      borderLeft: `2px solid ${googleTokens.primarySoft}`
                    }}
                  >
                    <MarkdownView text={cleanBulletMarkdown(item)} dense />
                  </Box>
                ))}
              </Stack>
            </Box>
          )}

          {(structured.openRisks ?? []).length > 0 && (
            <Box
              sx={{
                p: 1.25,
                borderRadius: 2,
                bgcolor: googleTokens.warningBg,
                border: '1px solid #FDE293'
              }}
            >
              <Typography
                variant="caption"
                sx={{ fontWeight: 600, color: googleTokens.warningFg, display: 'block', mb: 0.45 }}
              >
                {t('glassbox.risks')}
              </Typography>
              <Stack spacing={0.5}>
                {structured.openRisks.map((item, idx) => (
                  <Box key={`risk:${idx}`} sx={{ color: googleTokens.warningFg }}>
                    <MarkdownView text={cleanBulletMarkdown(item)} dense />
                  </Box>
                ))}
              </Stack>
            </Box>
          )}

          {(structured.validationChecks ?? []).length > 0 && (
            <Box>
              <Stack
                direction="row"
                sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 0.6 }}
              >
                <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                  {t('glassbox.checks')}
                </Typography>
                <Button
                  size="small"
                  startIcon={<HelpOutlineRounded sx={{ fontSize: 14 }} />}
                  onClick={() => setShowRules(prev => !prev)}
                  sx={{ py: 0.15, px: 0.75, fontSize: 12, minHeight: 26 }}
                >
                  {showRules ? t('glassbox.collapseRules') : t('glassbox.expandRules')}
                </Button>
              </Stack>

              <Stack spacing={0.75}>
                {structured.validationChecks.map((chk, idx) => {
                  const tone = checkTone(chk.status);
                  const rule = rules[chk.gate];
                  const evidence = describeHandoffCheckEvidence(chk, record, locale);
                  const title =
                    (locale === 'en-US' ? checkTitle(chk.gate, 'handoff', locale) : null) ||
                    chk.label;
                  return (
                    <Box
                      key={`hc:${chk.gate || idx}`}
                      sx={{
                        p: 1.1,
                        borderRadius: 2,
                        bgcolor: '#FAFAFA',
                        border: `1px solid ${tone.border}`
                      }}
                    >
                      <Stack
                        direction="row"
                        spacing={0.75}
                        sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}
                      >
                        <Chip
                          size="small"
                          icon={tone.icon}
                          label={`${title} · ${chk.status}`}
                          sx={{ bgcolor: tone.bg, color: tone.fg, fontWeight: 600 }}
                        />
                        <Typography
                          variant="caption"
                          sx={{ color: googleTokens.text, lineHeight: 1.5, flex: 1, minWidth: 200 }}
                        >
                          {evidence}
                        </Typography>
                      </Stack>

                      {rule && (
                        <Collapse in={showRules}>
                          <Box
                            sx={{
                              mt: 0.8,
                              pt: 0.75,
                              borderTop: `1px dashed ${googleTokens.outline}`,
                              color: googleTokens.textSecondary
                            }}
                          >
                            <Typography
                              variant="caption"
                              sx={{ display: 'block', lineHeight: 1.55 }}
                            >
                              <strong>{t('glassbox.purpose')}</strong>
                              {rule.purpose}
                            </Typography>
                            <Typography
                              variant="caption"
                              sx={{ display: 'block', lineHeight: 1.55 }}
                            >
                              <strong>{t('glassbox.passRule')}</strong>
                              {rule.passRule}
                            </Typography>
                            <Typography
                              variant="caption"
                              sx={{ display: 'block', lineHeight: 1.55 }}
                            >
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
            </Box>
          )}
        </Stack>
      )}

      {(record.toolEvidence ?? []).length > 0 && (
        <Box sx={{ mb: 1.25 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
            {t('glassbox.toolEvidence', { count: record.toolEvidence.length })}
          </Typography>
          <Stack spacing={0.5} sx={{ mt: 0.45 }}>
            {record.toolEvidence.map(ev => (
              <Readout key={ev.toolCallId} mono maxHeight={90}>
                {toolNameLabel(ev.tool, locale)} v{ev.version} · {shortId(ev.toolCallId)}
                {ev.resourceVersion ? `\n${ev.resourceVersion}` : ''}
              </Readout>
            ))}
          </Stack>
        </Box>
      )}

      <TraceSection
        title={t('glassbox.fullBody')}
        subtitle={t('glassbox.markdown')}
        defaultExpanded={defaultExpandBody}
        dense
      >
        <Box
          sx={{
            p: 1.5,
            borderRadius: 2,
            bgcolor: '#F8F9FA',
            border: `1px solid ${googleTokens.outline}`
          }}
        >
          <MarkdownView text={record.sourceResult || t('glassbox.noBody')} dense />
        </Box>
      </TraceSection>

      <TraceSection title={t('glassbox.rawJson')} dense>
        <Readout mono maxHeight={200}>
          {JSON.stringify(record, null, 2)}
        </Readout>
      </TraceSection>
    </Box>
  );
}

/** 玻璃盒面板四：多 Agent 结构化交接信封。 */
export default function HandoffEnvelopePanel({
  inbound = [],
  outbound = [],
  defaultExpandBody = false
}) {
  const { t } = useI18n();
  if (inbound.length === 0 && outbound.length === 0) {
    return (
      <EmptyState
        compact
        title={t('glassbox.emptyHandoffTitle')}
        description={t('glassbox.emptyHandoffDescription')}
      />
    );
  }

  return (
    <Stack spacing={1.5}>
      {inbound.map(record => (
        <EnvelopeCard
          key={`in:${record.senderAttemptId}:${record.receiverAttemptId}:${record.dependencyNodeId}`}
          record={record}
          direction="inbound"
          defaultExpandBody={defaultExpandBody}
        />
      ))}
      {outbound.map(record => (
        <EnvelopeCard
          key={`out:${record.senderAttemptId}:${record.receiverAttemptId}:${record.dependencyNodeId}`}
          record={record}
          direction="outbound"
          defaultExpandBody={defaultExpandBody}
        />
      ))}
    </Stack>
  );
}
