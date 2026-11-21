import { Box, Stack, TextField, Typography } from '@mui/material';

/**
 * 通用调用上限编辑器：空值表示不额外限制，0 表示不允许启动。
 * 仅负责编辑表单值，实际校验与预算继承由服务端完成。
 */
export default function BudgetEditor({ title, description, entries, value, onChange }) {
  const update = (key, field, raw) => {
    const next = { ...value };
    const budget = { ...(next[key] || {}) };
    if (raw === '') delete budget[field];
    else budget[field] = Number(raw);
    if (Object.keys(budget).length === 0) delete next[key];
    else next[key] = budget;
    onChange(next);
  };

  return (
    <Box
      sx={{
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 3,
        p: 1.75,
        bgcolor: '#FBFCFD'
      }}
    >
      <Typography sx={{ fontSize: 13, fontWeight: 600 }}>{title}</Typography>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1.25 }}>
        {description}
      </Typography>
      <Stack direction="row" spacing={1.25} sx={{ justifyContent: 'flex-end', mb: 0.5 }}>
        <Typography
          variant="caption"
          color="text.secondary"
          sx={{ width: 92, textAlign: 'center' }}
        >
          模型调用
        </Typography>
        <Typography
          variant="caption"
          color="text.secondary"
          sx={{ width: 92, textAlign: 'center' }}
        >
          工具调用
        </Typography>
      </Stack>
      <Stack spacing={1}>
        {entries.map(([key, label]) => (
          <Stack key={key} direction="row" spacing={1.25} sx={{ alignItems: 'center' }}>
            <Typography variant="body2" sx={{ flex: 1, minWidth: 0 }}>
              {label}
            </Typography>
            <TextField
              size="small"
              type="number"
              placeholder="未设置"
              value={value?.[key]?.maxModelCalls ?? ''}
              onChange={event => update(key, 'maxModelCalls', event.target.value)}
              slotProps={{ htmlInput: { min: 1, max: 20 } }}
              sx={{ width: 92 }}
              aria-label={`${label}模型调用上限`}
            />

            <TextField
              size="small"
              type="number"
              placeholder="未设置"
              value={value?.[key]?.maxToolCalls ?? ''}
              onChange={event => update(key, 'maxToolCalls', event.target.value)}
              slotProps={{ htmlInput: { min: 0, max: 20 } }}
              sx={{ width: 92 }}
              aria-label={`${label}工具调用上限`}
            />
          </Stack>
        ))}
      </Stack>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
        空值表示不额外限制；工具上限 0 表示不允许启动工具。
      </Typography>
    </Box>
  );
}
