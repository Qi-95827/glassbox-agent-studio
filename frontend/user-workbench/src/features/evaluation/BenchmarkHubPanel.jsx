import { useEffect, useMemo, useState } from 'react';
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
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography
} from '@mui/material';
import CloudDownloadOutlined from '@mui/icons-material/CloudDownloadOutlined';
import PlayArrowRounded from '@mui/icons-material/PlayArrowRounded';
import UploadFileOutlined from '@mui/icons-material/UploadFileOutlined';
import AddRounded from '@mui/icons-material/AddRounded';
import DownloadOutlined from '@mui/icons-material/DownloadOutlined';
import DeleteOutlineRounded from '@mui/icons-material/DeleteOutlineRounded';
import EditOutlined from '@mui/icons-material/EditOutlined';
import { useQueryClient } from '@tanstack/react-query';
import { evalLabApi } from '../../api/evalLab';
import { useEvalDatasets, useEvalHub } from '../../hooks/useEvalLab';
import { useToast } from '../../components/ToastProvider';
import EmptyState from '../../components/EmptyState';
import { useI18n } from '../../i18n/I18nProvider';
import { dateTime } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';

const emptyDataset = { code: '', name: '', category: 'CODING_REFLECTION', description: '' };
const emptySample = {
  sampleKey: '',
  titleZh: '',
  titleEn: '',
  prompt: '',
  setup: '{}',
  assertions: '[]',
  rubric: '[]'
};

function jsonError(t, error) {
  return t('evalLab.hub.invalidJson', { message: error.message });
}

/** 三层评测集：Benchmark Hub 下载 / 离线导入 + 自定义评测集 CRUD 与 JSONL 交换。 */
export default function BenchmarkHubPanel({ meta, onRun }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const hub = useEvalHub();
  const datasets = useEvalDatasets();
  const categories = useMemo(() => meta?.categories ?? [], [meta]);

  const [busy, setBusy] = useState('');
  const [importTarget, setImportTarget] = useState(null);
  const [importText, setImportText] = useState('');
  const [datasetDialog, setDatasetDialog] = useState(null);
  const [datasetForm, setDatasetForm] = useState(emptyDataset);
  const [editDataset, setEditDataset] = useState(null);
  const [samples, setSamples] = useState([]);
  const [sampleDialog, setSampleDialog] = useState(null);
  const [sampleForm, setSampleForm] = useState(emptySample);
  const [samplesBusy, setSamplesBusy] = useState(false);

  const packs = hub.data ?? [];
  const myDatasets = datasets.data ?? [];

  useEffect(() => {
    if (!editDataset) {
      setSamples([]);
      return;
    }
    let active = true;
    setSamplesBusy(true);
    evalLabApi
      .datasetSamples(editDataset.id)
      .then(rows => {
        if (active) setSamples(rows);
      })
      .catch(error => toast.error(t('evalLab.error.load', { message: error.message })))
      .finally(() => {
        if (active) setSamplesBusy(false);
      });
    return () => {
      active = false;
    };
  }, [editDataset, toast, t]);

  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['eval-lab-hub'] }),
      queryClient.invalidateQueries({ queryKey: ['eval-lab-datasets'] })
    ]);
  };

  const download = async pack => {
    setBusy(pack.packId);
    try {
      const result = await evalLabApi.downloadPack(pack.packId);
      toast.success(
        `${t('evalLab.hub.downloaded')} · ${result.sampleCount} ${t('evalLab.hub.samples', {
          count: result.sampleCount
        })}`
      );
      await refresh();
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy('');
    }
  };

  const openImport = target => {
    setImportTarget(target);
    setImportText('');
  };

  const submitImport = async () => {
    if (!importTarget || !importText.trim()) return;
    setBusy(importTarget.key);
    try {
      const result = importTarget.datasetId
        ? await evalLabApi.importDataset(importTarget.datasetId, importText)
        : await evalLabApi.importPack(importTarget.key, importText);
      toast.success(`${t('evalLab.hub.imported')} · ${result.sampleCount ?? result.imported ?? 0}`);
      setImportTarget(null);
      await refresh();
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy('');
    }
  };

  const readImportFile = async event => {
    const file = event.target.files?.[0];
    if (!file) return;
    setImportText(await file.text());
    event.target.value = '';
  };

  const openDatasetDialog = (mode, dataset) => {
    setDatasetDialog(mode);
    setEditDataset(mode === 'edit' ? dataset : null);
    setDatasetForm(
      mode === 'edit'
        ? {
            code: dataset.code,
            name: dataset.name,
            category: dataset.category,
            description: dataset.description ?? ''
          }
        : emptyDataset
    );
  };

  const saveDataset = async () => {
    setBusy('dataset');
    try {
      if (datasetDialog === 'edit') {
        await evalLabApi.updateDataset(editDataset.id, datasetForm);
      } else {
        await evalLabApi.createDataset(datasetForm);
      }
      toast.success(t('evalLab.hub.datasetSaved'));
      setDatasetDialog(null);
      await refresh();
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy('');
    }
  };

  const deleteDataset = async dataset => {
    if (!window.confirm(t('evalLab.hub.deleteConfirm'))) return;
    setBusy(dataset.id);
    try {
      await evalLabApi.deleteDataset(dataset.id);
      toast.success(t('common.deleted') ?? t('evalLab.hub.deleted'));
      if (editDataset?.id === dataset.id) setEditDataset(null);
      await refresh();
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy('');
    }
  };

  const openSample = sample => {
    if (sample) {
      setSampleForm({
        sampleKey: sample.sampleKey,
        titleZh: sample.titleZh ?? '',
        titleEn: sample.titleEn ?? '',
        prompt: sample.prompt,
        setup: JSON.stringify(sample.setup ?? {}, null, 2),
        assertions: JSON.stringify(sample.assertions ?? [], null, 2),
        rubric: JSON.stringify(sample.judgeRubric ?? [], null, 2)
      });
      setSampleDialog('edit');
    } else {
      setSampleForm(emptySample);
      setSampleDialog('create');
    }
  };

  const saveSample = async () => {
    let setup;
    let assertions;
    let rubric;
    try {
      setup = JSON.parse(sampleForm.setup || '{}');
      assertions = JSON.parse(sampleForm.assertions || '[]');
      rubric = JSON.parse(sampleForm.rubric || '[]');
    } catch (error) {
      toast.error(jsonError(t, error));
      return;
    }
    if (!editDataset) return;
    setBusy('sample');
    try {
      const key = sampleForm.sampleKey.trim();
      await evalLabApi.saveSample(editDataset.id, key, {
        sampleKey: key,
        titleZh: sampleForm.titleZh,
        titleEn: sampleForm.titleEn,
        prompt: sampleForm.prompt,
        setup,
        assertions,
        judgeRubric: rubric
      });
      toast.success(t('evalLab.hub.sampleSaved'));
      setSampleDialog(null);
      setSamples(await evalLabApi.datasetSamples(editDataset.id));
      queryClient.invalidateQueries({ queryKey: ['eval-lab-datasets'] });
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy('');
    }
  };

  const deleteSample = async sample => {
    if (!editDataset || !window.confirm(t('evalLab.hub.deleteConfirm'))) return;
    setBusy(sample.sampleKey);
    try {
      await evalLabApi.deleteSample(editDataset.id, sample.sampleKey);
      setSamples(await evalLabApi.datasetSamples(editDataset.id));
      queryClient.invalidateQueries({ queryKey: ['eval-lab-datasets'] });
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy('');
    }
  };

  const exportDataset = async dataset => {
    setBusy(dataset.id);
    try {
      const result = await evalLabApi.exportDataset(dataset.id);
      const blob = new Blob([result.jsonl ?? ''], { type: 'application/x-ndjson' });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `${dataset.code}.jsonl`;
      anchor.click();
      URL.revokeObjectURL(url);
      toast.success(t('evalLab.hub.exportDone'));
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy('');
    }
  };

  return (
    <Stack spacing={3}>
      <Box>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.5 }}>
          <Typography sx={{ fontSize: 16, fontWeight: 600 }}>{t('evalLab.hub.title')}</Typography>
          <Chip size="small" label={packs.length} variant="outlined" />
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5, maxWidth: 940 }}>
          {t('evalLab.hub.description')}
        </Typography>
        {hub.error && (
          <Alert severity="error">{t('evalLab.error.load', { message: hub.error.message })}</Alert>
        )}
        {hub.isLoading && <CircularProgress size={22} />}
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
            gap: 1.5
          }}
        >
          {packs.map(pack => (
            <Box
              key={pack.packId}
              sx={{
                p: 2,
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 3,
                bgcolor: '#fff',
                display: 'flex',
                flexDirection: 'column',
                gap: 1
              }}
            >
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{pack.displayName}</Typography>
                <Chip size="small" label={pack.category} variant="outlined" />
                <Chip
                  size="small"
                  color={pack.sampleCount > 0 ? 'success' : 'default'}
                  label={pack.status}
                />
              </Stack>
              <Typography variant="caption" color="text.secondary">
                {locale === 'en-US' ? pack.descriptionEn : pack.descriptionZh}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {t('evalLab.hub.license', { license: pack.license })} ·{' '}
                {t('evalLab.hub.samples', { count: pack.sampleCount || pack.expectedSamples })} ·{' '}
                {pack.downloadedAt ? dateTime(pack.downloadedAt, locale) : t('common.notStarted')}
              </Typography>
              {pack.errorMessage && <Alert severity="warning">{pack.errorMessage}</Alert>}
              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
                <Button
                  size="small"
                  startIcon={
                    busy === pack.packId ? (
                      <CircularProgress size={14} />
                    ) : (
                      <CloudDownloadOutlined />
                    )
                  }
                  disabled={busy === pack.packId}
                  onClick={() => download(pack)}
                >
                  {t('evalLab.hub.download')}
                </Button>
                <Button
                  size="small"
                  startIcon={<UploadFileOutlined />}
                  onClick={() => openImport({ key: pack.packId, label: pack.displayName })}
                >
                  {t('evalLab.hub.import')}
                </Button>
                <Button
                  size="small"
                  variant="contained"
                  startIcon={<PlayArrowRounded />}
                  disabled={!pack.sampleCount}
                  onClick={() => onRun?.('HUB', pack.packId)}
                >
                  {t('evalLab.hub.run')}
                </Button>
              </Stack>
            </Box>
          ))}
        </Box>
      </Box>

      <Divider />

      <Box>
        <Stack
          direction="row"
          spacing={1}
          sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 1 }}
        >
          <Box>
            <Typography sx={{ fontSize: 16, fontWeight: 600 }}>
              {t('evalLab.hub.customTitle')}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {t('evalLab.hub.customDescription')}
            </Typography>
          </Box>
          <Button
            variant="contained"
            startIcon={<AddRounded />}
            onClick={() => openDatasetDialog('create')}
          >
            {t('evalLab.hub.newDataset')}
          </Button>
        </Stack>
        {!myDatasets.length && !datasets.isLoading && (
          <EmptyState
            title={t('evalLab.hub.empty')}
            description={t('evalLab.hub.customDescription')}
          />
        )}
        <Stack spacing={1.2}>
          {myDatasets.map(dataset => (
            <Box
              key={dataset.id}
              sx={{
                p: 1.5,
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 3,
                bgcolor: '#fff'
              }}
            >
              <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={1}
                sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between' }}
              >
                <Box>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{dataset.name}</Typography>
                    <Chip size="small" label={dataset.category} variant="outlined" />
                    <Typography variant="caption" color="text.secondary">
                      {t('evalLab.hub.samplesCount', { count: dataset.sampleCount })}
                    </Typography>
                  </Stack>
                  <Typography variant="caption" color="text.secondary">
                    {dataset.code} · {dateTime(dataset.updatedAt, locale)}
                  </Typography>
                </Box>
                <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap' }}>
                  <Button
                    size="small"
                    startIcon={<EditOutlined />}
                    onClick={() => setEditDataset(dataset)}
                  >
                    {t('evalLab.hub.editSamples')}
                  </Button>
                  <Button
                    size="small"
                    startIcon={<UploadFileOutlined />}
                    onClick={() =>
                      openImport({ key: dataset.id, datasetId: dataset.id, label: dataset.name })
                    }
                  >
                    {t('evalLab.hub.import')}
                  </Button>
                  <Button
                    size="small"
                    startIcon={<DownloadOutlined />}
                    onClick={() => exportDataset(dataset)}
                  >
                    {t('evalLab.hub.export')}
                  </Button>
                  <Button
                    size="small"
                    startIcon={<PlayArrowRounded />}
                    disabled={!dataset.sampleCount}
                    onClick={() => onRun?.('CUSTOM', dataset.id)}
                  >
                    {t('evalLab.hub.run')}
                  </Button>
                  <Button
                    size="small"
                    color="inherit"
                    startIcon={<EditOutlined />}
                    onClick={() => openDatasetDialog('edit', dataset)}
                  >
                    {t('common.edit')}
                  </Button>
                  <Button
                    size="small"
                    color="inherit"
                    startIcon={<DeleteOutlineRounded />}
                    onClick={() => deleteDataset(dataset)}
                  >
                    {t('common.delete')}
                  </Button>
                </Stack>
              </Stack>
            </Box>
          ))}
        </Stack>
      </Box>

      <Dialog
        open={Boolean(importTarget)}
        onClose={() => setImportTarget(null)}
        maxWidth="md"
        fullWidth
      >
        <DialogTitle>
          {t('evalLab.hub.import')} · {importTarget?.label}
        </DialogTitle>
        <DialogContent dividers>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            {t('evalLab.hub.importHint')}
          </Typography>
          <Stack direction="row" spacing={1} sx={{ mb: 1.5 }}>
            <Button component="label" variant="outlined" startIcon={<UploadFileOutlined />}>
              {t('evalLab.hub.chooseFile')}
              <input
                hidden
                type="file"
                accept=".jsonl,.json,.ndjson,application/json"
                onChange={readImportFile}
              />
            </Button>
          </Stack>
          <TextField
            multiline
            minRows={10}
            fullWidth
            value={importText}
            onChange={event => setImportText(event.target.value)}
            placeholder='{"id":"case-1","prompt":"..."}'
            slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: 12 } } }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setImportTarget(null)}>{t('common.cancel')}</Button>
          <Button
            variant="contained"
            onClick={submitImport}
            disabled={busy === importTarget?.key || !importText.trim()}
          >
            {busy === importTarget?.key ? t('evalLab.hub.importing') : t('common.confirm')}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog
        open={Boolean(datasetDialog)}
        onClose={() => setDatasetDialog(null)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>
          {datasetDialog === 'edit' ? t('common.edit') : t('evalLab.hub.newDataset')}
        </DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2}>
            <TextField
              label={t('evalLab.hub.datasetCode')}
              value={datasetForm.code}
              disabled={datasetDialog === 'edit'}
              onChange={event => setDatasetForm({ ...datasetForm, code: event.target.value })}
            />
            <TextField
              label={t('evalLab.hub.datasetName')}
              value={datasetForm.name}
              onChange={event => setDatasetForm({ ...datasetForm, name: event.target.value })}
            />
            <FormControl>
              <InputLabel>{t('evalLab.hub.datasetCategory')}</InputLabel>
              <Select
                label={t('evalLab.hub.datasetCategory')}
                value={datasetForm.category}
                onChange={event => setDatasetForm({ ...datasetForm, category: event.target.value })}
              >
                {categories.map(category => (
                  <MenuItem key={category.code} value={category.code}>
                    {category.labelZh} / {category.labelEn}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <TextField
              label={t('common.description')}
              value={datasetForm.description}
              multiline
              minRows={2}
              onChange={event =>
                setDatasetForm({ ...datasetForm, description: event.target.value })
              }
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDatasetDialog(null)}>{t('common.cancel')}</Button>
          <Button variant="contained" onClick={saveDataset} disabled={busy === 'dataset'}>
            {t('common.save')}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog
        open={Boolean(editDataset)}
        onClose={() => setEditDataset(null)}
        maxWidth="md"
        fullWidth
      >
        <DialogTitle>
          {t('evalLab.hub.editSamples')} · {editDataset?.name}
        </DialogTitle>
        <DialogContent dividers>
          <Stack direction="row" spacing={1} sx={{ mb: 1.5 }}>
            <Button
              size="small"
              variant="contained"
              startIcon={<AddRounded />}
              onClick={() => openSample(null)}
            >
              {t('evalLab.hub.newSample')}
            </Button>
          </Stack>
          {samplesBusy && <CircularProgress size={20} />}
          {!samplesBusy && !samples.length && (
            <EmptyState
              title={t('evalLab.hub.noSamples')}
              description={t('evalLab.hub.importHint')}
            />
          )}
          {!!samples.length && (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t('evalLab.hub.sampleKey')}</TableCell>
                  <TableCell>{t('evalLab.hub.prompt')}</TableCell>
                  <TableCell align="right">{t('common.actions')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {samples.map(sample => (
                  <TableRow key={sample.id}>
                    <TableCell>{sample.sampleKey}</TableCell>
                    <TableCell sx={{ maxWidth: 360 }}>
                      <Typography variant="caption" noWrap sx={{ display: 'block', maxWidth: 340 }}>
                        {sample.prompt}
                      </Typography>
                    </TableCell>
                    <TableCell align="right">
                      <Button size="small" onClick={() => openSample(sample)}>
                        {t('common.edit')}
                      </Button>
                      <Button size="small" color="inherit" onClick={() => deleteSample(sample)}>
                        {t('common.delete')}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditDataset(null)}>{t('common.close')}</Button>
        </DialogActions>
      </Dialog>

      <Dialog
        open={Boolean(sampleDialog)}
        onClose={() => setSampleDialog(null)}
        maxWidth="md"
        fullWidth
      >
        <DialogTitle>
          {sampleDialog === 'edit' ? t('common.edit') : t('evalLab.hub.newSample')}
        </DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2}>
            <TextField
              label={t('evalLab.hub.sampleKey')}
              value={sampleForm.sampleKey}
              disabled={sampleDialog === 'edit'}
              onChange={event => setSampleForm({ ...sampleForm, sampleKey: event.target.value })}
            />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                fullWidth
                label="titleZh"
                value={sampleForm.titleZh}
                onChange={event => setSampleForm({ ...sampleForm, titleZh: event.target.value })}
              />
              <TextField
                fullWidth
                label="titleEn"
                value={sampleForm.titleEn}
                onChange={event => setSampleForm({ ...sampleForm, titleEn: event.target.value })}
              />
            </Stack>
            <TextField
              label={t('evalLab.hub.prompt')}
              value={sampleForm.prompt}
              multiline
              minRows={3}
              onChange={event => setSampleForm({ ...sampleForm, prompt: event.target.value })}
            />
            <TextField
              label={t('evalLab.hub.setupJson')}
              value={sampleForm.setup}
              multiline
              minRows={5}
              onChange={event => setSampleForm({ ...sampleForm, setup: event.target.value })}
              slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: 12 } } }}
            />
            <TextField
              label={t('evalLab.hub.assertionsJson')}
              value={sampleForm.assertions}
              multiline
              minRows={5}
              onChange={event => setSampleForm({ ...sampleForm, assertions: event.target.value })}
              slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: 12 } } }}
            />
            <TextField
              label={t('evalLab.hub.rubricJson')}
              value={sampleForm.rubric}
              multiline
              minRows={3}
              onChange={event => setSampleForm({ ...sampleForm, rubric: event.target.value })}
              slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: 12 } } }}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSampleDialog(null)}>{t('common.cancel')}</Button>
          <Button variant="contained" onClick={saveSample} disabled={busy === 'sample'}>
            {t('evalLab.hub.saveSample')}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
