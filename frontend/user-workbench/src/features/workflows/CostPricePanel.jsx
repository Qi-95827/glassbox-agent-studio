import { useEffect, useState } from 'react';
import { Alert, Box, Button, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { costApi } from '../../api/evaluations';

const empty = {
  provider: 'default',
  model: 'mock',
  currency: 'CNY',
  inputMicrosPerMillion: 0,
  outputMicrosPerMillion: 0,
  inputTokenUpperBound: 4096,
  outputTokenUpperBound: 4096
};

/** 供应方价格版本发布与当前生效版本查看。 */
export default function CostPricePanel() {
  const [prices, setPrices] = useState([]);
  const [form, setForm] = useState(empty);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    try {
      setPrices(await costApi.prices());
    } catch (failure) {
      setError(failure.message);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const publish = async () => {
    setBusy(true);
    try {
      await costApi.publishPrice({
        ...form,
        inputMicrosPerMillion: Number(form.inputMicrosPerMillion) || 0,
        outputMicrosPerMillion: Number(form.outputMicrosPerMillion) || 0,
        inputTokenUpperBound: Number(form.inputTokenUpperBound) || 1,
        outputTokenUpperBound: Number(form.outputTokenUpperBound) || 1
      });
      setError(null);
      await load();
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  const update = (field, value) => setForm(previous => ({ ...previous, [field]: value }));

  return (
    <Box>
      <Typography sx={{ fontSize: 14, fontWeight: 600 }}>供应方价格版本</Typography>
      <Typography variant="caption" color="text.secondary">
        价格版本用于费用估算和派发前可计价上界预留；发布后旧版本保留，只有最新生效版本参与新预算。
      </Typography>
      <Stack direction="row" spacing={1} useFlexGap sx={{ mt: 1.2, flexWrap: 'wrap' }}>
        <TextField
          size="small"
          label="供应方"
          value={form.provider}
          onChange={event => update('provider', event.target.value)}
          sx={{ width: 120 }}
        />

        <TextField
          size="small"
          label="模型"
          value={form.model}
          onChange={event => update('model', event.target.value)}
          sx={{ width: 120 }}
        />

        <TextField
          select
          size="small"
          label="币种"
          value={form.currency}
          onChange={event => update('currency', event.target.value)}
          sx={{ width: 96 }}
        >
          <MenuItem value="CNY">CNY</MenuItem>
          <MenuItem value="USD">USD</MenuItem>
        </TextField>
        <TextField
          size="small"
          type="number"
          label="输入/百万"
          value={form.inputMicrosPerMillion}
          onChange={event => update('inputMicrosPerMillion', event.target.value)}
          sx={{ width: 120 }}
        />

        <TextField
          size="small"
          type="number"
          label="输出/百万"
          value={form.outputMicrosPerMillion}
          onChange={event => update('outputMicrosPerMillion', event.target.value)}
          sx={{ width: 120 }}
        />

        <TextField
          size="small"
          type="number"
          label="输入上界"
          value={form.inputTokenUpperBound}
          onChange={event => update('inputTokenUpperBound', event.target.value)}
          sx={{ width: 110 }}
        />

        <TextField
          size="small"
          type="number"
          label="输出上界"
          value={form.outputTokenUpperBound}
          onChange={event => update('outputTokenUpperBound', event.target.value)}
          sx={{ width: 110 }}
        />

        <Button variant="outlined" loading={busy} onClick={publish}>
          发布价格版本
        </Button>
      </Stack>
      {error && (
        <Alert severity="warning" sx={{ mt: 1 }}>
          价格版本未保存：{error}
        </Alert>
      )}
      <Stack spacing={0.25} sx={{ mt: 1 }}>
        {prices.slice(0, 6).map(price => (
          <Typography key={price.id} variant="caption" color="text.secondary">
            {price.provider}/{price.model} v{price.version} · {price.currency} · 输入{' '}
            {price.inputMicrosPerMillion}/百万 · 输出 {price.outputMicrosPerMillion}/百万 ·{' '}
            {price.active ? '生效' : '历史'}
          </Typography>
        ))}
      </Stack>
    </Box>
  );
}
