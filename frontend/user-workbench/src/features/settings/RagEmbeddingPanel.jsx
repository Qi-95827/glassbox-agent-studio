import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  FormControlLabel,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ragEmbeddingApi } from '../../api/ragEmbedding';
import { useModelConnections } from '../../hooks/useModelConnections';
import { useToast } from '../../components/ToastProvider';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

/** 可选 Embedding 向量端点：优先复用已保存的 BYOK 连接，绝不在此保存明文 Key。 */
export default function RagEmbeddingPanel() {
  const { t } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const connections = useModelConnections();
  const settings = useQuery({ queryKey: ['rag-embedding'], queryFn: ragEmbeddingApi.get });
  const [enabled, setEnabled] = useState(false);
  const [connectionId, setConnectionId] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [model, setModel] = useState('');
  const [dimension, setDimension] = useState('1536');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!settings.data) return;
    setEnabled(Boolean(settings.data.enabled));
    setConnectionId(settings.data.connectionId || '');
    setBaseUrl(settings.data.baseUrl || '');
    setModel(settings.data.model || '');
    setDimension(String(settings.data.dimension ?? 1536));
  }, [settings.data]);

  const save = async () => {
    setBusy(true);
    try {
      await ragEmbeddingApi.save({
        enabled,
        connectionId,
        baseUrl,
        model,
        dimension: Number(dimension) || 0
      });
      await queryClient.invalidateQueries({ queryKey: ['rag-embedding'] });
      toast.success(t('ragEmbedding.saved'));
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy(false);
    }
  };

  const rows = connections.data?.connections ?? [];

  return (
    <Box
      sx={{
        bgcolor: '#FFFFFF',
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 4,
        p: 2.5
      }}
    >
      <Stack
        direction="row"
        spacing={1}
        sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 1 }}
      >
        <Box>
          <Typography sx={{ fontSize: 15, fontWeight: 500 }}>{t('ragEmbedding.title')}</Typography>
          <Typography variant="caption" color="text.secondary">
            {t('ragEmbedding.description')}
          </Typography>
        </Box>
        <Chip
          size="small"
          color={settings.data?.enabled ? 'success' : 'default'}
          label={settings.data?.enabled ? t('ragEmbedding.enabled') : t('ragEmbedding.disabled')}
        />
      </Stack>
      <Alert severity="info" sx={{ mb: 1.5 }}>
        {t('ragEmbedding.hint')}
      </Alert>
      <Stack spacing={1.25}>
        <FormControlLabel
          control={
            <Switch checked={enabled} onChange={event => setEnabled(event.target.checked)} />
          }
          label={t('ragEmbedding.enableSwitch')}
        />
        <TextField
          select
          size="small"
          label={t('ragEmbedding.connection')}
          value={connectionId}
          onChange={event => setConnectionId(event.target.value)}
          helperText={t('ragEmbedding.connectionHint')}
        >
          <MenuItem value="">{t('ragEmbedding.customEndpoint')}</MenuItem>
          {rows.map(connection => (
            <MenuItem key={connection.id} value={connection.id}>
              {connection.displayName || connection.modelName}
            </MenuItem>
          ))}
        </TextField>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
          <TextField
            size="small"
            label={t('ragEmbedding.baseUrl')}
            value={baseUrl}
            onChange={event => setBaseUrl(event.target.value)}
            disabled={Boolean(connectionId)}
            sx={{ flex: 1 }}
          />
          <TextField
            size="small"
            label={t('ragEmbedding.model')}
            value={model}
            onChange={event => setModel(event.target.value)}
            disabled={Boolean(connectionId)}
            sx={{ flex: 1 }}
          />
          <TextField
            size="small"
            label={t('ragEmbedding.dimension')}
            value={dimension}
            onChange={event => setDimension(event.target.value)}
            sx={{ width: 140 }}
            slotProps={{ htmlInput: { inputMode: 'numeric' } }}
          />
        </Stack>
        <Typography variant="caption" color="text.secondary">
          {t('ragEmbedding.status', {
            status: settings.data?.endpointConfigured
              ? t('ragEmbedding.configured')
              : t('ragEmbedding.notConfigured')
          })}
        </Typography>
        <Button variant="outlined" loading={busy} onClick={save} sx={{ alignSelf: 'start' }}>
          {t('ragEmbedding.save')}
        </Button>
      </Stack>
    </Box>
  );
}
