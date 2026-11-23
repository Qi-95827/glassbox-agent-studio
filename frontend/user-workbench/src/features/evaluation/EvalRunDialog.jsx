import { useEffect, useMemo, useRef, useState } from 'react';
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
  CODING_REFLECTION: ['enableReflection'],
  TOOL_MCP: ['enableMcp'],
  CONTEXT_COMPACTION: ['enableCompaction'],
  LONG_TERM_MEMORY: ['enableLongTermMemory'],
  PLANNING_MULTI_AGENT: ['enableReflection'],
  SAFETY_INJECTION: ['enableMcp']
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

function sameCodes(left, right) {
  return left.length === right.length && [...left].sort().join('|') === [...right].sort().join('|');
}

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
  const [packSelection, setPackSelection] = useState([]);
  const [allReadyPending, setAllReadyPending] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const categories = useMemo(() => meta?.categories ?? [], [meta]);
  const allCodes = useMemo(() => categories.map(item => item.code), [categories]);
  const allPacks = useMemo(
    () => (hub.data ?? []).filter(item => item.sampleCount > 0 || item.previewOnly),
    [hub.data]
  );
  const runnablePacks = useMemo(
    () => allPacks.filter(item => !item.previewOnly && item.sampleCount > 0),
    [allPacks]
  );
  const datasets = useMemo(() => custom.data ?? [], [custom.data]);
  const isHub = form.sourceKind === 'HUB';
  const selectedPacks = useMemo(
    () => allPacks.filter(pack => packSelection.includes(pack.packId)),
    [allPacks, packSelection]
  );
  const selectedRunnable = useMemo(
    () => selectedPacks.filter(pack => !pack.previewOnly && pack.sampleCount > 0),
    [selectedPacks]
  );
  const hubCategories = useMemo(
    () => [...new Set(selectedRunnable.map(pack => pack.category))],
    [selectedRunnable]
  );
  const hubCategoryKey = hubCategories.join(',');
  const hubFieldKeys = useMemo(
    () => new Set(selectedRunnable.flatMap(pack => HUB_FIELDS[pack.category] ?? [])),
    [selectedRunnable]
  );
  const fields = isHub
    ? EXPERIMENT_FIELDS.filter(([key]) => hubFieldKeys.has(key))
    : EXPERIMENT_FIELDS;
  const modelMode = form.modelMode;

  const allCodesRef = useRef(allCodes);
  allCodesRef.current = allCodes;
  const runnableRef = useRef(runnablePacks);
  runnableRef.current = runnablePacks;
  const hubCategoriesRef = useRef(hubCategories);
  hubCategoriesRef.current = hubCategories;

  const sourceCategory = useMemo(() => {
    if (form.sourceKind === 'CUSTOM') {
      const dataset = datasets.find(item => item.id === form.sourceKey);
      return dataset?.category ?? null;
    }
    return null;
  }, [form.sourceKind, form.sourceKey, datasets]);

  useEffect(() => {
    if (!open) return;
    const ids =
      initialSource === 'HUB'
        ? initialSourceKey === 'all-ready'
          ? runnableRef.current.map(pack => pack.packId)
          : (initialSourceKey ?? '')
              .split(',')
              .map(text => text.trim())
              .filter(Boolean)
        : [];
    setPackSelection(ids);
    setAllReadyPending(
      initialSource === 'HUB' && initialSourceKey === 'all-ready' && ids.length === 0
    );
    setForm({
      ...defaults,
      sourceKind: initialSource,
      sourceKey: initialSource === 'HUB' ? (ids[0] ?? '') : initialSourceKey,
      sampleLimit: initialSource === 'HUB' ? 5 : defaults.sampleLimit,
      modelMode: initialSource === 'HUB' ? 'byok' : defaults.modelMode,
      categories: initialSource === 'MINI' ? allCodesRef.current : []
    });
    setError('');
  }, [open, initialSource, initialSourceKey]);

  useEffect(() => {
    if (!open || form.sourceKind !== 'HUB' || !allReadyPending || !runnablePacks.length) return;
    setPackSelection(runnablePacks.map(pack => pack.packId));
    setAllReadyPending(false);
  }, [open, form.sourceKind, allReadyPending, runnablePacks]);

  useEffect(() => {
    if (!open) return;
    if (form.sourceKind === 'HUB') {
      setForm(current =>
        sameCodes(current.categories, hubCategoriesRef.current)
          ? current
          : { ...current, categories: hubCategoriesRef.current }
      );
    } else if (form.sourceKind === 'CUSTOM' && sourceCategory) {
      setForm(current =>
        sameCodes(current.categories, [sourceCategory])
          ? current
          : { ...current, categories: [sourceCategory] }
      );
    }
  }, [open, form.sourceKind, hubCategoryKey, sourceCategory]);

  const set = (key, value) => setForm(current => ({ ...current, [key]: value }));
  const toggleCategory = code =>
    setForm(current => ({
      ...current,
      categories: current.categories.includes(code)
        ? current.categories.filter(item => item !== code)
        : [...current.categories, code]
    }));

  const togglePack = packId => {
    if (selectedPacks.some(pack => pack.packId === packId && pack.previewOnly)) return;
    setPackSelection(current =>
      current.includes(packId) ? current.filter(item => item !== packId) : [...current, packId]
    );
  };

  const selectAllReady = () => setPackSelection(runnablePacks.map(pack => pack.packId));
  const selectSingle = () =>
    setPackSelection(runnablePacks.length ? [runnablePacks[0].packId] : []);

  const byokOptions = useMemo(() => {
    const list = connections.data?.connections ?? [];
    return list.filter(item => item.enabled);
  }, [connections.data]);

  const changeSource = sourceKind => {
    setPackSelection([]);
    setAllReadyPending(false);
    setForm(current => ({
      ...current,
      sourceKind,
      sourceKey: sourceKind === 'MINI' ? 'mini' : '',
      sampleLimit: sourceKind === 'HUB' ? 5 : defaults.sampleLimit,
      modelMode: sourceKind === 'HUB' ? 'byok' : defaults.modelMode,
      categories: sourceKind === 'MINI' ? allCodes : []
    }));
  };

  const sampleLimit = Number(form.sampleLimit) || 0;
  const repeatK = Number(form.repeatK) || 1;
  const plannedSampleCount = isHub
    ? selectedRunnable.reduce((sum, pack) => sum + Math.min(sampleLimit, pack.sampleCount), 0)
    : 0;
  const plannedAttempts = plannedSampleCount * repeatK;

  const submit = async () => {
    if (!isHub && !form.categories.length) {
      setError(t('evalLab.field.categories'));
      return;
    }
    if (isHub && !selectedRunnable.length) {
      setError(t('evalLab.hub.noPackSelected'));
      return;
    }
    if (form.sourceKind === 'CUSTOM' && !form.sourceKey) {
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
        sourceKey: isHub
          ? selectedRunnable
              .map(pack => pack.packId)
              .sort()
              .join(',')
          : form.sourceKey,
        categories: isHub ? hubCategories : form.categories,
        modelMode,
        modelConnectionId: modelMode === 'byok' ? form.modelConnectionId : null,
        enableCompaction: form.enableCompaction,
        enableLongTermMemory: form.enableLongTermMemory,
        enableReflection: form.enableReflection,
        enableMcp: form.enableMcp,
        repeatK,
        sampleLimit,
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

          {isHub && (
            <Box
              sx={{
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 2.5,
                p: 1.2
              }}
            >
              <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={1}
                sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between', mb: 0.5 }}
              >
                <Typography sx={{ fontSize: 13, fontWeight: 600 }}>
                  {t('evalLab.hub.selectPacks')} ·{' '}
                  {t('evalLab.hub.packsSelected', { count: selectedRunnable.length })}
                </Typography>
                <Stack direction="row" spacing={0.5}>
                  <Button size="small" onClick={selectAllReady} disabled={!runnablePacks.length}>
                    {t('evalLab.hub.selectAllReady')}
                  </Button>
                  <Button size="small" onClick={selectSingle} disabled={!runnablePacks.length}>
                    {t('evalLab.hub.selectSingle')}
                  </Button>
                </Stack>
              </Stack>
              <Box sx={{ maxHeight: 210, overflowY: 'auto', pr: 0.5 }}>
                {allPacks.map(pack => (
                  <FormControlLabel
                    key={pack.packId}
                    disabled={pack.previewOnly || pack.sampleCount <= 0}
                    control={
                      <Checkbox
                        size="small"
                        checked={packSelection.includes(pack.packId)}
                        onChange={() => togglePack(pack.packId)}
                      />
                    }
                    label={
                      <Box>
                        <Typography variant="caption" sx={{ fontWeight: 600 }}>
                          {pack.displayName}
                        </Typography>
                        <Typography variant="caption" color="text.secondary" sx={{ ml: 0.8 }}>
                          {pack.previewOnly
                            ? t('evalLab.hub.previewDisabled')
                            : `${t('evalLab.hub.capability.' + pack.category)} · ${pack.sampleCount}`}
                        </Typography>
                      </Box>
                    }
                  />
                ))}
              </Box>
            </Box>
          )}

          {isHub && !hub.isLoading && !runnablePacks.length && (
            <Alert severity="info">{t('evalLab.hub.noRunnablePack')}</Alert>
          )}
          {selectedPacks.some(pack => pack.previewOnly) && (
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
                          ? hubCategories.includes(category.code)
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
              label={t(
                isHub && selectedRunnable.length > 1
                  ? 'evalLab.hub.perPackLimit'
                  : 'evalLab.field.sampleLimit'
              )}
              value={form.sampleLimit}
              onChange={event => set('sampleLimit', event.target.value)}
              slotProps={{
                htmlInput: {
                  min: 1,
                  max: meta?.maxSampleLimit ?? 50
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
          {isHub && selectedRunnable.length > 0 && (
            <Typography variant="caption" color="text.secondary">
              {t('evalLab.hub.samplingHint', {
                limit: sampleLimit,
                packs: selectedRunnable.length,
                total: plannedSampleCount,
                attempts: plannedAttempts
              })}
            </Typography>
          )}

          {isHub && hubCategories.includes('TOOL_MCP') && (
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
            (isHub && !selectedRunnable.length) ||
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
