import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  FormControlLabel,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  Switch,
  TextField,
  Typography
} from '@mui/material';
import { useQueryClient } from '@tanstack/react-query';
import { evalLabApi } from '../../api/evalLab';
import { useModelConnections } from '../../hooks/useModelConnections';
import { useEvalDatasets, useEvalHub } from '../../hooks/useEvalLab';
import { useToast } from '../../components/ToastProvider';
import { useI18n } from '../../i18n/I18nProvider';
import { googleTokens } from '../../theme/googleTheme';

const EXPERIMENT_FIELDS = [
  ['enableCompaction', 'evalLab.field.enableCompaction'],
  ['enableLongTermMemory', 'evalLab.field.enableLongTermMemory'],
  ['enableReflection', 'evalLab.field.enableReflection'],
  ['enableMcp', 'evalLab.field.enableMcp']
];

const HUB_FIELDS = {
  TOOL_MCP: ['enableMcp'],
  CONTEXT_COMPACTION: ['enableCompaction'],
  LONG_TERM_MEMORY: ['enableLongTermMemory'],
  PLANNING_MULTI_AGENT: ['enableReflection']
};

const defaults = {
  sourceKind: 'MINI',
  sourceKey: 'mini',
  categories: [],
  modelMode: 'mock',
  modelConnectionId: '',
  judgeModelConnectionId: '',
  repeatK: 1,
  sampleLimit: 4,
  maxCost: '',
  enableCompaction: true,
  enableLongTermMemory: true,
  enableReflection: true,
  enableMcp: false
};

/** 发起评测：数据源、类别、模型、消融开关、裁判、抽样与费用上限。 */
export default function EvalRunDialog({
  open,
  meta,
  initialSource = 'MINI',
  initialSourceKey = 'mini',
  onClose,
  onCreated
}) {
  const { t } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const connections = useModelConnections(open);
  const hub = useEvalHub();
  const custom = useEvalDatasets();
  const [form, setForm] = useState(defaults);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const categories = useMemo(() => meta?.categories ?? [], [meta]);
  const allCodes = useMemo(() => categories.map(item => item.code), [categories]);
  const hubPacks = useMemo(
    () => (hub.data ?? []).filter(item => item.sampleCount > 0 || item.previewOnly),
    [hub.data]
  );
  const isHub = form.sourceKind === 'HUB';
  const selectedPack = isHub ? hubPacks.find(item => item.packId === form.sourceKey) : null;
  const modelMode = form.modelMode;
  const fields = isHub
    ? EXPERIMENT_FIELDS.filter(([key]) => HUB_FIELDS[selectedPack?.category]?.includes(key))
    : EXPERIMENT_FIELDS;
  const datasets = useMemo(() => custom.data ?? [], [custom.data]);

  const sourceCategory = useMemo(() => {
    if (form.sourceKind === 'HUB') {
      const pack = hubPacks.find(item => item.packId === form.sourceKey);
      return pack?.category ?? null;
    }
    if (form.sourceKind === 'CUSTOM') {
      const dataset = datasets.find(item => item.id === form.sourceKey);
      return dataset?.category ?? null;
    }
    return null;
  }, [form.sourceKind, form.sourceKey, hubPacks, datasets]);

  useEffect(() => {
    if (!open) return;
    setForm(current => ({
      ...defaults,
      sourceKind: initialSource,
      sourceKey: initialSourceKey,
      sampleLimit: initialSource === 'HUB' ? 5 : defaults.sampleLimit,
      modelMode: initialSource === 'HUB' ? 'byok' : defaults.modelMode,
      categories: initialSource === 'MINI' ? allCodes : current.categories
    }));
    setError('');
  }, [open, initialSource, initialSourceKey, allCodes]);

  useEffect(() => {
    if (sourceCategory) setForm(current => ({ ...current, categories: [sourceCategory] }));
  }, [sourceCategory]);

  const set = (key, value) => setForm(current => ({ ...current, [key]: value }));
  const toggleCategory = code =>
    setForm(current => ({
      ...current,
      categories: current.categories.includes(code)
        ? current.categories.filter(item => item !== code)
        : [...current.categories, code]
    }));

  const byokOptions = useMemo(() => {
    const list = connections.data?.connections ?? [];
    return list.filter(item => item.enabled);
  }, [connections.data]);

  const changeSource = sourceKind => {
    setForm(current => ({
      ...current,
      sourceKind,
      sourceKey: sourceKind === 'MINI' ? 'mini' : '',
      sampleLimit: sourceKind === 'HUB' ? 5 : defaults.sampleLimit,
      modelMode: sourceKind === 'HUB' ? 'byok' : defaults.modelMode,
      categories: sourceKind === 'MINI' ? allCodes : []
    }));
  };

  const submit = async () => {
    if (!isHub && !form.categories.length) {
      setError(t('evalLab.field.categories'));
      return;
    }
    if (
      (isHub && (!selectedPack || selectedPack.previewOnly)) ||
      (form.sourceKind === 'CUSTOM' && !form.sourceKey)
    ) {
      setError(t('evalLab.field.source'));
      return;
    }
    if (modelMode === 'byok' && !form.modelConnectionId) {
      setError(t('evalLab.field.modelConnection'));
      return;
    }
    setBusy(true);
    setError('');
    try {
      const created = await evalLabApi.start({
        sourceKind: form.sourceKind,
        sourceKey: form.sourceKey,
        categories: isHub ? [selectedPack.category] : form.categories,
        modelMode,
        modelConnectionId: modelMode === 'byok' ? form.modelConnectionId : null,
        enableCompaction: form.enableCompaction,
        enableLongTermMemory: form.enableLongTermMemory,
        enableReflection: form.enableReflection,
        enableMcp: form.enableMcp,
        repeatK: Number(form.repeatK),
        sampleLimit: Number(form.sampleLimit),
        maxCostUsd: form.maxCost === '' ? null : Number(form.maxCost),
        judgeModelConnectionId: form.judgeModelConnectionId || null
      });
      await queryClient.invalidateQueries({ queryKey: ['eval-lab-runs'] });
      toast.success(t('evalLab.started'));
      onCreated?.(created.id);
      onClose?.();
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={busy ? undefined : onClose} maxWidth="md" fullWidth>
      <DialogTitle>{t('evalLab.dialog.title')}</DialogTitle>
      <DialogContent dividers>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t(isHub ? 'evalLab.hub.agentDescription' : 'evalLab.dialog.subtitle')}
        </Typography>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Stack spacing={2.2}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <FormControl size="small" sx={{ minWidth: 200 }}>
              <InputLabel>{t('evalLab.field.source')}</InputLabel>
              <Select
                label={t('evalLab.field.source')}
                value={form.sourceKind}
                onChange={event => changeSource(event.target.value)}
              >
                <MenuItem value="MINI">{t('evalLab.source.MINI')}</MenuItem>
                <MenuItem value="HUB">{t('evalLab.source.HUB')}</MenuItem>
                <MenuItem value="CUSTOM">{t('evalLab.source.CUSTOM')}</MenuItem>
              </Select>
            </FormControl>
            {form.sourceKind === 'HUB' && (
              <FormControl size="small" sx={{ minWidth: 260 }}>
                <InputLabel>{t('evalLab.hub.title')}</InputLabel>
                <Select
                  label={t('evalLab.hub.title')}
                  value={selectedPack?.packId ?? ''}
                  onChange={event => set('sourceKey', event.target.value)}
                >
                  {hubPacks.map(pack => (
                    <MenuItem key={pack.packId} value={pack.packId}>
                      {pack.displayName} ·{' '}
                      {pack.previewOnly ? t('evalLab.hub.routeB') : pack.sampleCount}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
            )}
            {form.sourceKind === 'CUSTOM' && (
              <FormControl size="small" sx={{ minWidth: 260 }}>
                <InputLabel>{t('evalLab.hub.customTitle')}</InputLabel>
                <Select
                  label={t('evalLab.hub.customTitle')}
                  value={form.sourceKey}
                  onChange={event => set('sourceKey', event.target.value)}
                >
                  {datasets.map(dataset => (
                    <MenuItem key={dataset.id} value={dataset.id}>
                      {dataset.name} · {dataset.sampleCount}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
            )}
          </Stack>

          {isHub && !hub.isLoading && !hubPacks.some(pack => !pack.previewOnly) && (
            <Alert severity="info">{t('evalLab.hub.noRunnablePack')}</Alert>
          )}
          {selectedPack?.previewOnly && (
            <Alert severity="info">{t('evalLab.hub.routeBHint')}</Alert>
          )}
          <Box>
            <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>
              {t('evalLab.field.categories')}
            </Typography>
            <Box
              sx={{
                display: 'grid',
                gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
                gap: 0.5
              }}
            >
              {categories.map(category => (
                <FormControlLabel
                  key={category.code}
                  control={
                    <Checkbox
                      size="small"
                      checked={
                        isHub
                          ? Boolean(selectedPack?.category === category.code)
                          : form.categories.includes(category.code)
                      }
                      disabled={isHub || form.sourceKind === 'CUSTOM'}
                      onChange={() => toggleCategory(category.code)}
                    />
                  }
                  label={`${category.labelZh} / ${category.labelEn} · ${category.miniCount}`}
                />
              ))}
            </Box>
          </Box>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <FormControl size="small" sx={{ minWidth: 210 }}>
              <InputLabel>{t('evalLab.field.modelMode')}</InputLabel>
              <Select
                label={t('evalLab.field.modelMode')}
                value={form.modelMode}
                onChange={event => set('modelMode', event.target.value)}
              >
                <MenuItem value="mock">{t('evalLab.field.mock')}</MenuItem>
                <MenuItem value="byok">BYOK</MenuItem>
              </Select>
            </FormControl>
            {modelMode === 'byok' && (
              <FormControl size="small" sx={{ minWidth: 240 }}>
                <InputLabel>{t('evalLab.field.modelConnection')}</InputLabel>
                <Select
                  label={t('evalLab.field.modelConnection')}
                  value={form.modelConnectionId}
                  onChange={event => set('modelConnectionId', event.target.value)}
                >
                  {byokOptions.map(item => (
                    <MenuItem key={item.id} value={item.id}>
                      {item.displayName} · {item.modelName}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
            )}
            <FormControl size="small" sx={{ minWidth: 240 }}>
              <InputLabel>{t('evalLab.field.judgeModel')}</InputLabel>
              <Select
                label={t('evalLab.field.judgeModel')}
                value={form.judgeModelConnectionId}
                onChange={event => set('judgeModelConnectionId', event.target.value)}
              >
                <MenuItem value="">{t('common.none')}</MenuItem>
                {byokOptions.map(item => (
                  <MenuItem key={item.id} value={item.id}>
                    {item.displayName} · {item.modelName}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          </Stack>
          {modelMode === 'byok' && (
            <Typography variant="caption" color="text.secondary">
              {t(
                isHub && !byokOptions.length ? 'evalLab.hub.noConnection' : 'evalLab.field.byokHint'
              )}
            </Typography>
          )}

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              size="small"
              type="number"
              label={t('evalLab.field.repeatK')}
              value={form.repeatK}
              onChange={event => set('repeatK', event.target.value)}
              slotProps={{ htmlInput: { min: 1, max: meta?.maxRepeatK ?? 3 } }}
            />
            <TextField
              size="small"
              type="number"
              label={t('evalLab.field.sampleLimit')}
              value={form.sampleLimit}
              onChange={event => set('sampleLimit', event.target.value)}
              slotProps={{
                htmlInput: {
                  min: 1,
                  max: Math.min(selectedPack?.sampleCount || 50, meta?.maxSampleLimit ?? 50)
                }
              }}
            />
            <TextField
              size="small"
              type="number"
              label={t('evalLab.field.maxCost')}
              value={form.maxCost}
              onChange={event => set('maxCost', event.target.value)}
              slotProps={{ htmlInput: { min: 0, step: 0.01 } }}
            />
          </Stack>

          {isHub && selectedPack?.category === 'TOOL_MCP' && (
            <Typography variant="caption" color="text.secondary">
              {t('evalLab.hub.mcpHint')}
            </Typography>
          )}
          {fields.length > 0 && (
            <Box
              sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 0.5 }}
            >
              {fields.map(([key, label]) => (
                <FormControlLabel
                  key={key}
                  control={
                    <Switch
                      size="small"
                      checked={form[key]}
                      onChange={event => set(key, event.target.checked)}
                    />
                  }
                  label={t(label)}
                />
              ))}
            </Box>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy} sx={{ color: googleTokens.textSecondary }}>
          {t('common.cancel')}
        </Button>
        <Button
          variant="contained"
          onClick={submit}
          disabled={
            busy ||
            (isHub && (!selectedPack || selectedPack.previewOnly)) ||
            (modelMode === 'byok' && !form.modelConnectionId)
          }
          startIcon={busy ? <CircularProgress size={16} /> : null}
        >
          {busy ? t('evalLab.starting') : t('evalLab.start')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
