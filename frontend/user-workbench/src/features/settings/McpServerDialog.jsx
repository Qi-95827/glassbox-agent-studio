import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField
} from '@mui/material';
import { mcpApi } from '../../api/mcp';
import { useI18n } from '../../i18n/I18nProvider';

function parseJsonObject(text, label) {
  if (!text || !text.trim()) return null;
  try {
    const value = JSON.parse(text);
    if (!value || Array.isArray(value) || typeof value !== 'object')
      throw new Error(`${label} 必须是 JSON 对象`);
    return value;
  } catch (error) {
    throw new Error(`${label}：${error.message}`);
  }
}

/** 新增/编辑 MCP Server；凭据输入只提交一次，服务端加密且永不回显。 */
export default function McpServerDialog({ open, initial, onClose, onSaved }) {
  const { t } = useI18n();
  const [form, setForm] = useState({
    name: '',
    slug: '',
    transport: 'STREAMABLE_HTTP',
    command: '',
    args: '',
    endpointUrl: '',
    headers: '',
    env: '',
    enabled: true
  });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setError(null);
    setForm({
      name: initial?.name ?? '',
      slug: initial?.slug ?? '',
      transport: initial?.transport ?? 'STREAMABLE_HTTP',
      command: initial?.command ?? '',
      args: (initial?.args ?? []).join(' '),
      endpointUrl: initial?.endpointUrl ?? '',
      headers: '',
      env: '',
      enabled: initial?.enabled ?? true
    });
  }, [open, initial]);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const payload = {
        name: form.name,
        slug: form.slug,
        transport: form.transport,
        command: form.command || null,
        args: form.args.trim() ? form.args.trim().split(/\s+/) : [],
        endpointUrl: form.endpointUrl || null,
        headers: parseJsonObject(form.headers, t('mcp.headers')),
        env: parseJsonObject(form.env, t('mcp.env')),
        enabled: form.enabled
      };
      if (initial?.id) await mcpApi.update(initial.id, payload);
      else await mcpApi.create(payload);
      onSaved?.();
      onClose?.();
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{initial ? t('mcp.editTitle') : t('mcp.addTitle')}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.25} sx={{ mt: 0.5 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField
            size="small"
            label={t('mcp.name')}
            value={form.name}
            onChange={event => setForm({ ...form, name: event.target.value })}
          />
          <TextField
            size="small"
            label={t('mcp.slug')}
            value={form.slug}
            disabled={Boolean(initial)}
            onChange={event => setForm({ ...form, slug: event.target.value })}
          />
          <TextField
            select
            size="small"
            label={t('mcp.transport')}
            value={form.transport}
            onChange={event => setForm({ ...form, transport: event.target.value })}
          >
            <MenuItem value="STREAMABLE_HTTP">STREAMABLE_HTTP</MenuItem>
            <MenuItem value="STDIO">STDIO</MenuItem>
          </TextField>
          {form.transport === 'STDIO' ? (
            <>
              <TextField
                size="small"
                label={t('mcp.command')}
                value={form.command}
                onChange={event => setForm({ ...form, command: event.target.value })}
              />
              <TextField
                size="small"
                label={t('mcp.args')}
                value={form.args}
                onChange={event => setForm({ ...form, args: event.target.value })}
              />
            </>
          ) : (
            <TextField
              size="small"
              label={t('mcp.endpoint')}
              value={form.endpointUrl}
              onChange={event => setForm({ ...form, endpointUrl: event.target.value })}
            />
          )}
          <TextField
            size="small"
            multiline
            minRows={2}
            label={t('mcp.headers')}
            helperText={t('mcp.secretHint')}
            value={form.headers}
            onChange={event => setForm({ ...form, headers: event.target.value })}
          />
          <TextField
            size="small"
            multiline
            minRows={2}
            label={t('mcp.env')}
            helperText={t('mcp.secretHint')}
            value={form.env}
            onChange={event => setForm({ ...form, env: event.target.value })}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button color="inherit" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button variant="contained" loading={busy} onClick={save}>
          {t('common.save')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

export { parseJsonObject };
