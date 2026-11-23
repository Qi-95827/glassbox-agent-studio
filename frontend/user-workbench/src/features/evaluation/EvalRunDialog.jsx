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
import { useToast } from '../../components/ToastProvider';
import { useI18n } from '../../i18n/I18nProvider';
import { googleTokens } from '../../theme/googleTheme';

const defaults = {
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

/** 发起评测：类别、模型、消融开关、裁判、抽样与费用上限。 */
export default function EvalRunDialog({ open, meta, onClose, onCreated }) {
  const { t } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const connections = useModelConnections(open);
  const [form, setForm] = useState(defaults);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const categories = useMemo(() => meta?.categories ?? [], [meta]);
  const allCodes = useMemo(() => categories.map(item => item.code), [categories]);

  useEffect(() => {
    if (!open) return;
    setForm(current => ({
      ...defaults,
      categories: current.categories.length ? current.categories : allCodes
    }));
    setError('');
  }, [open, allCodes]);

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

  const submit = async () => {
    if (!form.categories.length) {
      setError(t('evalLab.field.categories'));
      return;
    }
    if (form.modelMode === 'byok' && !form.modelConnectionId) {
      setError(t('evalLab.field.modelConnection'));
      return;
    }
    setBusy(true);
    setError('');
    try {
      const created = await evalLabApi.start({
        sourceKind: 'MINI',
        sourceKey: 'mini',
        categories: form.categories,
        modelMode: form.modelMode,
        modelConnectionId: form.modelMode === 'byok' ? form.modelConnectionId : null,
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
          {t('evalLab.dialog.subtitle')}
        </Typography>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Stack spacing={2.2}>
          <Box>
            <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>
              {t('evalLab.field.categories')}
            </Typography>
            <Box
              sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 0.5 }}
            >
              {categories.map(category => (
                <FormControlLabel
                  key={category.code}
                  control={
                    <Checkbox
                      size="small"
                      checked={form.categories.includes(category.code)}
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
            {form.modelMode === 'byok' && (
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
          {form.modelMode === 'byok' && (
            <Typography variant="caption" color="text.secondary">
              {t('evalLab.field.byokHint')}
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
              slotProps={{ htmlInput: { min: 1, max: meta?.maxSampleLimit ?? 50 } }}
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

          <Box
            sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 0.5 }}
          >
            {[
              ['enableCompaction', 'evalLab.field.enableCompaction'],
              ['enableLongTermMemory', 'evalLab.field.enableLongTermMemory'],
              ['enableReflection', 'evalLab.field.enableReflection'],
              ['enableMcp', 'evalLab.field.enableMcp']
            ].map(([key, label]) => (
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
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy} sx={{ color: googleTokens.textSecondary }}>
          {t('common.cancel')}
        </Button>
        <Button
          variant="contained"
          onClick={submit}
          disabled={busy}
          startIcon={busy ? <CircularProgress size={16} /> : null}
        >
          {busy ? t('evalLab.starting') : t('evalLab.start')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
