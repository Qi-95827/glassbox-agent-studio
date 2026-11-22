import { useEffect, useMemo, useState } from 'react';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Autocomplete,
  Box,
  Button,
  Checkbox,
  FormControlLabel,
  IconButton,
  InputAdornment,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography
} from '@mui/material';
import ExpandMoreRounded from '@mui/icons-material/ExpandMoreRounded';
import VisibilityOutlined from '@mui/icons-material/VisibilityOutlined';
import VisibilityOffOutlined from '@mui/icons-material/VisibilityOffOutlined';
import WorkbenchDialog from '../../components/WorkbenchDialog';
import { modelConnectionsApi } from '../../api/modelConnections';

const empty = {
  providerType: 'DEEPSEEK',
  displayName: '',
  baseUrl: 'https://api.deepseek.com',
  modelName: 'deepseek-chat',
  apiKey: '',
  enabled: true,
  isDefault: false,
  inputPricePer1m: '',
  outputPricePer1m: '',
  currency: 'CNY'
};

function numberOrNull(value) {
  if (value === '' || value == null) return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function fromConnection(connection) {
  if (!connection) return empty;
  return {
    providerType: connection.providerType,
    displayName: connection.displayName,
    baseUrl: connection.baseUrl,
    modelName: connection.modelName,
    apiKey: '',
    enabled: connection.enabled,
    isDefault: connection.isDefault,
    inputPricePer1m: connection.inputPricePer1m ?? '',
    outputPricePer1m: connection.outputPricePer1m ?? '',
    currency: connection.currency || 'CNY'
  };
}

/** 新建/编辑用户模型连接；密钥只在显式输入时提交，编辑时留空表示不修改。 */
export default function ModelConnectionDialog({ open, connection, presets, onClose, onSaved }) {
  const [form, setForm] = useState(empty);
  const [showKey, setShowKey] = useState(false);
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState('');
  const [testResult, setTestResult] = useState(null);
  const editing = Boolean(connection);

  const preset = useMemo(
    () => (presets ?? []).find(item => item.providerType === form.providerType) ?? null,
    [presets, form.providerType]
  );

  useEffect(() => {
    if (!open) return;
    setForm(fromConnection(connection));
    setShowKey(false);
    setError('');
    setTestResult(
      connection?.lastTestStatus
        ? {
            ok: connection.lastTestStatus === 'OK',
            message: connection.lastTestMessage,
            latencyMs: null,
            testedAt: connection.lastTestedAt
          }
        : null
    );
  }, [open, connection]);

  const update = (field, value) => setForm(previous => ({ ...previous, [field]: value }));

  const changeProvider = code => {
    const next = (presets ?? []).find(item => item.providerType === code);
    setForm(previous => ({
      ...previous,
      providerType: code,
      baseUrl: next?.defaultBaseUrl || '',
      modelName: next?.models?.[0] || '',
      displayName:
        previous.displayName ||
        (next ? `${next.label} · ${next.models?.[0] || '自定义模型'}` : '我的模型')
    }));
  };

  const payload = () => ({
    displayName: form.displayName,
    providerType: form.providerType,
    baseUrl: form.baseUrl,
    modelName: form.modelName,
    apiKey: form.apiKey,
    enabled: form.enabled,
    isDefault: form.isDefault,
    inputPricePer1m: numberOrNull(form.inputPricePer1m),
    outputPricePer1m: numberOrNull(form.outputPricePer1m),
    currency: form.currency
  });

  const test = async () => {
    setError('');
    setTestResult(null);
    setTesting(true);
    try {
      const result = await modelConnectionsApi.testDraft({
        connectionId: editing ? connection.id : null,
        providerType: form.providerType,
        baseUrl: form.baseUrl,
        modelName: form.modelName,
        apiKey: form.apiKey
      });
      setTestResult(result);
    } catch (failure) {
      setTestResult({ ok: false, message: failure.message });
    } finally {
      setTesting(false);
    }
  };

  const save = async () => {
    setError('');
    setBusy(true);
    try {
      const body = payload();
      const saved = editing
        ? await modelConnectionsApi.update(connection.id, body)
        : await modelConnectionsApi.create(body);
      onSaved?.(saved);
      onClose();
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <WorkbenchDialog
      open={open}
      title={editing ? '编辑模型连接' : '添加模型连接'}
      subtitle="使用你自己的 API Key。密钥经服务端 AES-256-GCM 加密后存储，页面只显示掩码。"
      width={640}
      onCancel={onClose}
      footer={null}
    >
      <Stack spacing={1.6} sx={{ pt: 0.5 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.2}>
          <TextField
            select
            fullWidth
            size="small"
            label="服务商"
            value={form.providerType}
            onChange={event => changeProvider(event.target.value)}
          >
            {(presets ?? []).map(item => (
              <MenuItem key={item.providerType} value={item.providerType}>
                {item.label}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            fullWidth
            size="small"
            label="展示名"
            value={form.displayName}
            onChange={event => update('displayName', event.target.value)}
            placeholder="例如：我的 DeepSeek"
            slotProps={{ htmlInput: { maxLength: 80 } }}
          />
        </Stack>

        <TextField
          fullWidth
          size="small"
          label="Base URL"
          value={form.baseUrl}
          onChange={event => update('baseUrl', event.target.value)}
          helperText="只允许 https 公网地址；本机调试可使用 http://127.0.0.1。"
          placeholder={preset?.defaultBaseUrl || 'https://your-provider.example/v1'}
        />

        <Autocomplete
          freeSolo
          options={preset?.models ?? []}
          value={form.modelName}
          onChange={(_, value) => update('modelName', value || '')}
          onInputChange={(_, value, reason) => {
            if (reason === 'input' || reason === 'clear') update('modelName', value);
          }}
          renderInput={params => (
            <TextField
              {...params}
              fullWidth
              size="small"
              label="Model ID"
              placeholder="例如 deepseek-chat"
              helperText={preset?.docsUrl ? `服务商文档：${preset.docsUrl}` : undefined}
            />
          )}
        />

        <TextField
          fullWidth
          size="small"
          label="API Key"
          type={showKey ? 'text' : 'password'}
          value={form.apiKey}
          onChange={event => update('apiKey', event.target.value)}
          placeholder={
            editing ? `留空表示不修改（当前 ${connection?.keyMasked || '已保存'}）` : 'sk-...'
          }
          helperText={editing ? '编辑时留空不会覆盖已保存的密钥。' : '密钥只提交给本机服务端。'}
          slotProps={{
            input: {
              endAdornment: (
                <InputAdornment position="end">
                  <IconButton
                    size="small"
                    aria-label={showKey ? '隐藏密钥' : '显示密钥'}
                    onClick={() => setShowKey(value => !value)}
                  >
                    {showKey ? (
                      <VisibilityOffOutlined fontSize="small" />
                    ) : (
                      <VisibilityOutlined fontSize="small" />
                    )}
                  </IconButton>
                </InputAdornment>
              )
            }
          }}
        />

        <Stack direction="row" spacing={2} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
          <FormControlLabel
            label="启用"
            control={
              <Switch
                size="small"
                checked={form.enabled}
                onChange={event => update('enabled', event.target.checked)}
              />
            }
          />
          <FormControlLabel
            label="设为默认模型"
            control={
              <Checkbox
                size="small"
                checked={form.isDefault}
                onChange={event => update('isDefault', event.target.checked)}
              />
            }
          />
          <Box sx={{ flex: 1 }} />
          <Button variant="outlined" loading={testing} onClick={test}>
            测试连通性
          </Button>
        </Stack>

        {testResult && (
          <Alert severity={testResult.ok ? 'success' : 'error'}>
            {testResult.ok ? '已连通' : '连接失败'}
            {testResult.latencyMs != null ? ` · ${testResult.latencyMs} ms` : ''}
            {testResult.message ? `：${testResult.message}` : ''}
          </Alert>
        )}

        <Accordion sx={{ border: 'none', bgcolor: 'transparent', '&:before': { display: 'none' } }}>
          <AccordionSummary
            expandIcon={<ExpandMoreRounded fontSize="small" />}
            sx={{ px: 0, minHeight: 38, '& .MuiAccordionSummary-content': { my: 0.5 } }}
          >
            <Typography variant="body2" sx={{ fontWeight: 500 }}>
              可选：参考单价（仅用于本机预算上限估算）
            </Typography>
          </AccordionSummary>
          <AccordionDetails sx={{ px: 0, pt: 0 }}>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>
              填写后只用于工作台内的估算与预算提示；费用仍由你的服务商结算，不向平台充值。
            </Typography>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
              <TextField
                fullWidth
                size="small"
                type="number"
                label="输入单价 / 百万 Token"
                value={form.inputPricePer1m}
                onChange={event => update('inputPricePer1m', event.target.value)}
                slotProps={{ htmlInput: { min: 0, step: 0.01 } }}
              />
              <TextField
                fullWidth
                size="small"
                type="number"
                label="输出单价 / 百万 Token"
                value={form.outputPricePer1m}
                onChange={event => update('outputPricePer1m', event.target.value)}
                slotProps={{ htmlInput: { min: 0, step: 0.01 } }}
              />
              <TextField
                select
                size="small"
                label="币种"
                value={form.currency}
                onChange={event => update('currency', event.target.value)}
                sx={{ width: 120 }}
              >
                <MenuItem value="CNY">CNY</MenuItem>
                <MenuItem value="USD">USD</MenuItem>
                <MenuItem value="EUR">EUR</MenuItem>
              </TextField>
            </Stack>
          </AccordionDetails>
        </Accordion>

        {error && <Alert severity="error">{error}</Alert>}

        <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
          <Button color="inherit" onClick={onClose} disabled={busy}>
            取消
          </Button>
          <Button variant="contained" loading={busy} onClick={save}>
            保存
          </Button>
        </Stack>
      </Stack>
    </WorkbenchDialog>
  );
}
