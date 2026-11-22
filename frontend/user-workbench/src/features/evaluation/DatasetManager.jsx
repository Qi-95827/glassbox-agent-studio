import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  FormControlLabel,
  MenuItem,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import { evaluationApi } from '../../api/evaluations';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

const blank = t => ({
  code: `CASE_${Math.random().toString(36).slice(2, 6).toUpperCase()}`,
  title: t('dataset.newSample'),
  goal: t('dataset.newSampleGoal'),
  scenario: 'normal',
  expectedStatus: 'COMPLETED',
  maxRounds: 4,
  maxTools: 2,
  sourceKind: 'MANUAL',
  sourceRef: null
});

/** 可管理评测数据集：编辑草稿、发布不可变版本、从失败运行录入并回看批次。 */
export default function DatasetManager({ onOpenRun }) {
  const { t } = useI18n();
  const [key, setKey] = useState('MANAGED_UI');
  const [rows, setRows] = useState([]);
  const [versions, setVersions] = useState([]);
  const [batches, setBatches] = useState([]);
  const [version, setVersion] = useState(null);
  const [capture, setCapture] = useState({ runId: '', confirmed: false });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const failureRun = capture.runId;
  const confirmed = capture.confirmed;
  const setFailureRun = value => setCapture(previous => ({ ...previous, runId: value }));
  const setConfirmed = value => setCapture(previous => ({ ...previous, confirmed: value }));

  const load = async currentKey => {
    try {
      const [draft, published, batchList] = await Promise.all([
        evaluationApi.dataset(currentKey),
        evaluationApi.datasetVersions(currentKey),
        evaluationApi.datasetBatches()
      ]);
      setRows(draft);
      setVersions(published);
      setBatches(batchList);
      setVersion(previous => previous ?? published[0]?.version ?? null);
      setError(null);
    } catch (failure) {
      setError(failure.message);
    }
  };

  useEffect(() => {
    setVersion(null);
    load(key);
  }, [key]);

  const update = (code, field, value) =>
    setRows(current => current.map(row => (row.code === code ? { ...row, [field]: value } : row)));

  const save = async row => {
    setBusy(true);
    try {
      await evaluationApi.saveSample(key, row.code, {
        code: row.code,
        title: row.title,
        goal: row.goal,
        scenario: row.scenario,
        expectedStatus: row.expectedStatus,
        maxRounds: Number(row.maxRounds) || 1,
        maxTools: Number(row.maxTools) || 0,
        sourceKind: row.sourceKind || 'MANUAL',
        sourceRef: row.sourceRef || null
      });
      await load(key);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  const publish = async () => {
    setBusy(true);
    try {
      await evaluationApi.publishDataset(key);
      await load(key);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  const addFromRun = async () => {
    setBusy(true);
    try {
      await evaluationApi.datasetFromRun(key, {
        runId: failureRun.trim(),
        confirmedRedaction: confirmed
      });
      setFailureRun('');
      setConfirmed(false);
      await load(key);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  const startBatch = async () => {
    setBusy(true);
    try {
      await evaluationApi.startDatasetBatch(key, version);
      await load(key);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack spacing={2}>
      <Alert severity="info">{t('dataset.intro')}</Alert>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.25}
        useFlexGap
        sx={{ flexWrap: 'wrap' }}
      >
        <TextField
          size="small"
          label={t('dataset.code')}
          value={key}
          onChange={event => setKey(event.target.value.toUpperCase())}
          sx={{ width: 190 }}
        />

        <Button variant="outlined" loading={busy} onClick={publish}>
          {t('dataset.publishDraft')}
        </Button>
        <TextField
          select
          size="small"
          label={t('dataset.version')}
          value={version ?? ''}
          onChange={event => setVersion(event.target.value)}
          sx={{ width: 170 }}
        >
          {versions.map(item => (
            <MenuItem key={item.version} value={item.version}>
              {t('dataset.versionOption', {
                version: item.version,
                count: item.sampleCount
              })}
            </MenuItem>
          ))}
        </TextField>
        <Button variant="contained" loading={busy} disabled={!versions.length} onClick={startBatch}>
          {t('dataset.runBatch')}
        </Button>
      </Stack>
      {error && <Alert severity="error">{error}</Alert>}

      <Stack spacing={1.25}>
        {rows.map(row => (
          <Box
            key={row.code}
            sx={{ p: 1.75, border: `1px solid ${googleTokens.outline}`, borderRadius: 3 }}
          >
            <Stack spacing={1}>
              <TextField
                size="small"
                label={t('dataset.rowTitle', { code: row.code })}
                value={row.title}
                onChange={event => update(row.code, 'title', event.target.value)}
              />

              <TextField
                multiline
                minRows={2}
                label={t('dataset.goal')}
                value={row.goal}
                onChange={event => update(row.code, 'goal', event.target.value)}
              />

              <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                <TextField
                  select
                  size="small"
                  label={t('advanced.scenario')}
                  value={row.scenario}
                  onChange={event => update(row.code, 'scenario', event.target.value)}
                  sx={{ width: 120 }}
                >
                  <MenuItem value="normal">{t('advanced.scenario.normal')}</MenuItem>
                  <MenuItem value="fail">{t('advanced.scenario.fail')}</MenuItem>
                  <MenuItem value="repeat">{t('advanced.scenario.repeat')}</MenuItem>
                </TextField>
                <TextField
                  select
                  size="small"
                  label={t('dataset.expectedStatus')}
                  value={row.expectedStatus}
                  onChange={event => update(row.code, 'expectedStatus', event.target.value)}
                  sx={{ width: 140 }}
                >
                  {['COMPLETED', 'FAILED', 'LIMITED', 'BLOCKED'].map(value => (
                    <MenuItem key={value} value={value}>
                      {value}
                    </MenuItem>
                  ))}
                </TextField>
                <TextField
                  size="small"
                  type="number"
                  label={t('dataset.rounds')}
                  value={row.maxRounds}
                  onChange={event => update(row.code, 'maxRounds', event.target.value)}
                  sx={{ width: 90 }}
                />

                <TextField
                  size="small"
                  type="number"
                  label={t('dataset.tools')}
                  value={row.maxTools}
                  onChange={event => update(row.code, 'maxTools', event.target.value)}
                  sx={{ width: 90 }}
                />

                <Button
                  size="small"
                  variant="outlined"
                  loading={busy}
                  onClick={() => save(row)}
                  sx={{ alignSelf: 'center' }}
                >
                  {t('dataset.saveDraft')}
                </Button>
              </Stack>
              <Typography variant="caption" color="text.secondary">
                {t('dataset.draftMeta', {
                  version: row.version,
                  source: row.sourceKind,
                  ref: row.sourceRef ? ` · ${row.sourceRef}` : '',
                  redacted: row.redacted ? t('common.yes') : t('common.no')
                })}
              </Typography>
            </Stack>
          </Box>
        ))}
      </Stack>
      <Button
        variant="outlined"
        onClick={() =>
          setRows(current => [...current, { ...blank(t), version: 0, redacted: false }])
        }
        sx={{ alignSelf: 'flex-start' }}
      >
        {t('dataset.addSample')}
      </Button>

      <Box>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{t('dataset.captureTitle')}</Typography>
        <Typography variant="caption" color="text.secondary">
          {t('dataset.captureNote')}
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1 }}>
          <TextField
            size="small"
            label={t('dataset.failedRunId')}
            value={failureRun}
            onChange={event => setFailureRun(event.target.value)}
            sx={{ flex: 1 }}
          />

          <Button
            variant="outlined"
            loading={busy}
            disabled={!failureRun.trim() || !confirmed}
            onClick={addFromRun}
          >
            {t('dataset.capture')}
          </Button>
        </Stack>
        <FormControlLabel
          control={
            <Checkbox
              size="small"
              checked={confirmed}
              onChange={event => setConfirmed(event.target.checked)}
            />
          }
          label={<Typography variant="body2">{t('dataset.captureConfirm')}</Typography>}
        />
      </Box>

      <Box>
        <Typography sx={{ fontSize: 14, fontWeight: 600, mb: 0.75 }}>
          {t('dataset.batchesTitle')}
        </Typography>
        {batches.length === 0 && (
          <Typography variant="body2" color="text.secondary">
            {t('dataset.noBatches')}
          </Typography>
        )}
        <Stack spacing={1}>
          {batches.map(batch => (
            <Stack
              key={batch.id}
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1}

              sx={{
                p: 1.25,
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 2.5,
                alignItems: { sm: 'center' }
              }}
            >
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography variant="body2" sx={{ fontWeight: 500 }}>
                  {batch.datasetKey} · {batch.status}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {t('dataset.batchMeta', {
                    version: batch.datasetVersionId?.slice(0, 8),
                    sha: batch.definitionSha256?.slice(0, 16)
                  })}
                </Typography>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  {batch.configJson}
                </Typography>
              </Box>
              <Button
                size="small"
                onClick={async () => {
                  try {
                    const detail = await evaluationApi.datasetBatch(batch.id);
                    const failed =
                      detail.cases.find(item => item.status === 'FAILED' && item.runId) ||
                      detail.cases.find(item => item.runId);
                    if (failed && onOpenRun) onOpenRun(failed.runId);
                  } catch (failure) {
                    setError(failure.message);
                  }
                }}
              >
                {t('dataset.viewFailedRun')}
              </Button>
            </Stack>
          ))}
        </Stack>
      </Box>
    </Stack>
  );
}
