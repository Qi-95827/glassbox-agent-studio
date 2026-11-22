import { useEffect, useState } from 'react';
import { Alert, Box, Button, Chip, MenuItem, Stack, TextField, Typography } from '@mui/material';
import PlayArrowRounded from '@mui/icons-material/PlayArrowRounded';
import { useQueryClient } from '@tanstack/react-query';
import StatusChip from '../../components/StatusChip';
import EmptyState from '../../components/EmptyState';
import { Readout, TraceSection } from '../../components/WorkbenchDialog';
import WorkbenchDialog from '../../components/WorkbenchDialog';
import { useEvaluationDetail, useEvaluations } from '../../hooks/useEvaluations';
import { evaluationApi } from '../../api/evaluations';
import { useToast } from '../../components/ToastProvider';
import { comparisonLabel, evaluationSuiteLabel, statusLabel } from '../../constants/labels';
import { dateTime, duration, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

const emptyConfig = {
  suite: 'LOCAL_AGENT_V2',
  version: '',
  maxRounds: '',
  maxTools: '',
  maxSeconds: ''
};

/** 种子评测：启动、逐项断言、停止与历史批次对照。 */
export default function EvaluationBatchesPanel({ onOpenRun }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const evaluations = useEvaluations();
  const [selectedId, setSelectedId] = useState('');
  const [startOpen, setStartOpen] = useState(false);
  const [config, setConfig] = useState(emptyConfig);
  const [baseline, setBaseline] = useState('');
  const [comparison, setComparison] = useState(null);
  const [busy, setBusy] = useState(false);

  const list = evaluations.data ?? [];
  const activeId = selectedId || list[0]?.id || '';
  const detail = useEvaluationDetail(activeId);
  const batch = detail.data?.batch;
  const cases = detail.data?.cases ?? [];

  useEffect(() => {
    setComparison(null);
    setBaseline('');
  }, [activeId]);

  const start = async () => {
    setBusy(true);
    try {
      const created = await evaluationApi.startConfigured({
        suite: config.suite,
        version: config.version === '' ? null : Number(config.version),
        maxRounds: config.maxRounds === '' ? null : Number(config.maxRounds),
        maxTools: config.maxTools === '' ? null : Number(config.maxTools),
        maxSeconds: config.maxSeconds === '' ? null : Number(config.maxSeconds),
        requestedBy: 'local-user'
      });
      setSelectedId(created.id);
      setComparison(null);
      setStartOpen(false);
      queryClient.invalidateQueries({ queryKey: ['evaluations'] });
      toast.success(t('eval.started'));
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    try {
      await evaluationApi.cancel(activeId);
      queryClient.invalidateQueries({ queryKey: ['evaluation', activeId] });
      toast.info(t('eval.stopRequested'));
    } catch (error) {
      toast.error(error.message);
    }
  };

  const compare = async () => {
    if (!baseline || !activeId || baseline === activeId) {
      toast.warning(t('eval.selectTwo'));
      return;
    }
    try {
      setComparison(await evaluationApi.compare(baseline, activeId));
    } catch (error) {
      toast.error(error.message);
    }
  };

  return (
    <Stack spacing={2}>
      <Stack
        direction="row"
        sx={{ justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}
      >
        <Typography variant="body2" color="text.secondary">
          {t('eval.description')}
        </Typography>
        <Button
          variant="contained"
          startIcon={<PlayArrowRounded />}
          onClick={() => setStartOpen(true)}
        >
          {t('eval.runSeed')}
        </Button>
      </Stack>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', lg: '290px minmax(0, 1fr)' },
          gap: 2,
          alignItems: 'start'
        }}
      >
        <Box
          sx={{
            bgcolor: '#FFFFFF',
            border: `1px solid ${googleTokens.outline}`,
            borderRadius: 4,
            p: 1,
            maxHeight: 640,
            overflowY: 'auto'
          }}
        >
          {list.map(row => {
            const active = row.id === activeId;
            return (
              <Box
                key={row.id}
                role="button"
                tabIndex={0}
                onClick={() => setSelectedId(row.id)}
                onKeyDown={event => event.key === 'Enter' && setSelectedId(row.id)}
                sx={{
                  px: 1.25,
                  py: 1,
                  borderRadius: 2.5,
                  cursor: 'pointer',
                  bgcolor: active ? '#E8F0FE' : 'transparent',
                  '&:hover': { bgcolor: active ? '#E8F0FE' : '#F8F9FA' }
                }}
              >
                <Stack
                  direction="row"
                  spacing={0.75}
                  sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                >
                  <StatusChip value={row.status} />
                  <Typography variant="caption" color="text.secondary">
                    {dateTime(row.createdAt, locale)}
                  </Typography>
                </Stack>
                <Typography sx={{ fontSize: 13, fontWeight: active ? 600 : 500, mt: 0.5 }}>
                  {evaluationSuiteLabel(row.suite, locale)}
                </Typography>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ fontFamily: googleTokens.mono }}
                >
                  #{shortId(row.id)}
                </Typography>
              </Box>
            );
          })}
          {!evaluations.isLoading && list.length === 0 && (
            <EmptyState
              compact
              title={t('eval.emptyTitle')}
              description={t('eval.emptyDescription')}
            />
          )}
        </Box>

        <Box
          sx={{
            bgcolor: '#FFFFFF',
            border: `1px solid ${googleTokens.outline}`,
            borderRadius: 4,
            p: { xs: 2, sm: 2.5 },
            minWidth: 0
          }}
        >
          {!batch ? (
            <EmptyState title={t('eval.selectTitle')} description={t('eval.selectDescription')} />
          ) : (
            <Stack spacing={1.6}>
              <Stack
                direction="row"
                sx={{ justifyContent: 'space-between', alignItems: 'flex-start', gap: 1 }}
              >
                <Box>
                  <Stack
                    direction="row"
                    spacing={0.75}
                    sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                  >
                    <Typography sx={{ fontSize: 16, fontWeight: 500 }}>
                      {evaluationSuiteLabel(batch.suite, locale)}
                    </Typography>
                    <StatusChip value={batch.status} />
                  </Stack>
                  <Typography variant="caption" color="text.secondary">
                    {t('eval.batchMeta', {
                      time: dateTime(batch.createdAt, locale),
                      id: shortId(batch.id),
                      sha: batch.definitionSha256?.slice(0, 16) || t('eval.unknownHistory')
                    })}
                  </Typography>
                </Box>
                {['RUNNING', 'CANCELLING'].includes(batch.status) && (
                  <Button size="small" color="error" variant="outlined" onClick={cancel}>
                    {t('eval.stop')}
                  </Button>
                )}
              </Stack>
              <Typography variant="body2" color="text.secondary">
                {t('eval.passSummary', {
                  passed: cases.filter(item => item.status === 'PASSED').length,
                  total: cases.length
                })}
              </Typography>
              {batch.definitionJson && (
                <TraceSection title={t('eval.snapshot')} dense>
                  <Readout mono maxHeight={180}>
                    {batch.definitionJson}
                  </Readout>
                </TraceSection>
              )}

              <Stack spacing={1.2}>
                {cases.map(item => (
                  <Box
                    key={item.id}
                    sx={{ p: 1.5, border: `1px solid ${googleTokens.outline}`, borderRadius: 3 }}
                  >
                    <Stack
                      direction="row"
                      sx={{ justifyContent: 'space-between', gap: 1, alignItems: 'flex-start' }}
                    >
                      <Box sx={{ minWidth: 0 }}>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>
                          {item.title}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          {item.explanation || t('eval.waitingResult')}
                        </Typography>
                      </Box>
                      <StatusChip value={item.status} />
                    </Stack>
                    {item.checks?.length > 0 ? (
                      <TraceSection title={t('eval.checks', { count: item.checks.length })}>
                        <Stack spacing={0.5}>
                          {item.checks.map(check => (
                            <Typography key={check.code} variant="caption" color="text.secondary">
                              {check.passed ? t('eval.passed') : t('eval.notPassed')} ·{' '}
                              {check.detail}
                            </Typography>
                          ))}
                        </Stack>
                      </TraceSection>
                    ) : (
                      <Typography
                        variant="caption"
                        color="text.secondary"
                        sx={{ display: 'block', mt: 0.5 }}
                      >
                        {t('eval.noChecks')}
                      </Typography>
                    )}
                    {item.runId && (
                      <Button size="small" sx={{ mt: 0.5 }} onClick={() => onOpenRun(item.runId)}>
                        {t('eval.openRun', { id: shortId(item.runId) })}
                      </Button>
                    )}
                  </Box>
                ))}
                {cases.length === 0 && (
                  <Typography variant="body2" color="text.secondary">
                    {t('eval.noCases')}
                  </Typography>
                )}
              </Stack>

              <Box>
                <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.75 }}>
                  {t('eval.compareTitle')}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {t('eval.compareNote')}
                </Typography>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1 }}>
                  <TextField
                    select
                    size="small"
                    label={t('eval.baseline')}
                    value={baseline}
                    onChange={event => {
                      setBaseline(event.target.value);
                      setComparison(null);
                    }}
                    sx={{ flex: 1, minWidth: 220 }}
                  >
                    {list
                      .filter(row => row.id !== activeId)
                      .map(row => (
                        <MenuItem key={row.id} value={row.id}>
                          {evaluationSuiteLabel(row.suite, locale)} ·{' '}
                          {dateTime(row.createdAt, locale)} · {statusLabel(row.status, locale)}
                        </MenuItem>
                      ))}
                  </TextField>
                  <Button variant="outlined" onClick={compare} disabled={!baseline}>
                    {t('eval.compare')}
                  </Button>
                </Stack>
              </Box>

              {comparison && (
                <Stack spacing={1}>
                  <Alert severity={comparison.comparableDefinition ? 'info' : 'warning'}>
                    {comparison.comparabilityReason ||
                      (comparison.comparableDefinition
                        ? t('eval.comparable')
                        : t('eval.notComparable'))}
                  </Alert>
                  {(comparison.cases ?? []).map(item => (
                    <Box
                      key={item.code}
                      sx={{ borderBottom: '1px solid', borderColor: 'divider', pb: 1 }}
                    >
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>
                        {item.title} · {comparisonLabel(item.outcome, locale)}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {t('eval.compareStatus', {
                          baseline:
                            statusLabel(item.baseline?.status, locale) || t('eval.noRecord'),
                          candidate:
                            statusLabel(item.candidate?.status, locale) || t('eval.noRecord')
                        })}
                      </Typography>
                      <Stack direction="row" spacing={0.75} sx={{ mt: 0.5 }}>
                        {item.baseline?.runId && (
                          <Button size="small" onClick={() => onOpenRun(item.baseline.runId)}>
                            {t('eval.baselineRun')}
                          </Button>
                        )}
                        {item.candidate?.runId && (
                          <Button size="small" onClick={() => onOpenRun(item.candidate.runId)}>
                            {t('eval.candidateRun')}
                          </Button>
                        )}
                      </Stack>
                    </Box>
                  ))}
                </Stack>
              )}

              <Chip
                size="small"
                label={t('eval.duration', { value: duration(batch.durationMs) })}
                sx={{ alignSelf: 'flex-start', bgcolor: '#F1F3F4' }}
              />
            </Stack>
          )}
        </Box>
      </Box>

      <WorkbenchDialog
        open={startOpen}
        onCancel={() => setStartOpen(false)}
        onOk={start}
        confirmLoading={busy}
        okText={t('eval.runSeed')}
        width={620}
        title={t('eval.dialogTitle')}
        subtitle={t('eval.dialogSubtitle')}
      >
        <Stack spacing={1.5} sx={{ pt: 0.5 }}>
          <Alert severity="info">{t('eval.dialogNote')}</Alert>
          <TextField
            select
            size="small"
            label={t('eval.suite')}
            value={config.suite}
            onChange={event => setConfig({ ...config, suite: event.target.value })}
          >
            {['LOCAL_AGENT_V2', 'DYNAMIC_WORKFLOW_V1', 'DYNAMIC_CHECK_V1'].map(value => (
              <MenuItem key={value} value={value}>
                {evaluationSuiteLabel(value, locale)}
              </MenuItem>
            ))}
          </TextField>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25}>
            <TextField
              size="small"
              type="number"
              label={t('eval.suiteVersion')}
              value={config.version}
              onChange={event => setConfig({ ...config, version: event.target.value })}
              sx={{ flex: 1 }}
            />

            <TextField
              size="small"
              type="number"
              label={t('eval.maxRounds')}
              value={config.maxRounds}
              onChange={event => setConfig({ ...config, maxRounds: event.target.value })}
              sx={{ flex: 1 }}
            />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25}>
            <TextField
              size="small"
              type="number"
              label={t('eval.maxTools')}
              value={config.maxTools}
              onChange={event => setConfig({ ...config, maxTools: event.target.value })}
              sx={{ flex: 1 }}
            />

            <TextField
              size="small"
              type="number"
              label={t('eval.maxSeconds')}
              value={config.maxSeconds}
              onChange={event => setConfig({ ...config, maxSeconds: event.target.value })}
              sx={{ flex: 1 }}
            />
          </Stack>
        </Stack>
      </WorkbenchDialog>
    </Stack>
  );
}
