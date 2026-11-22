import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Stack,
  Typography
} from '@mui/material';
import AddRounded from '@mui/icons-material/AddRounded';
import StarRounded from '@mui/icons-material/StarRounded';
import { modelConnectionsApi } from '../../api/modelConnections';
import { useModelConnections } from '../../hooks/useModelConnections';
import { googleTokens } from '../../theme/googleTheme';
import ModelConnectionDialog from './ModelConnectionDialog';
import { useI18n } from '../../i18n/I18nProvider';

function statusChip(connection, t) {
  if (connection.lastTestStatus === 'OK')
    return <Chip size="small" color="success" label={t('byok.connected')} />;
  if (connection.lastTestStatus === 'FAILED')
    return <Chip size="small" color="error" label={t('byok.connectionFailed')} />;
  return <Chip size="small" label={t('byok.untested')} />;
}

/** 模型与 API Key（BYOK）主卡片：列表、默认项、连通性测试与编辑入口。 */
export default function ModelConnectionPanel() {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const query = useModelConnections();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [busyId, setBusyId] = useState(null);
  const [confirmDelete, setConfirmDelete] = useState(null);
  const [error, setError] = useState('');

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['model-connections'] });
  const connections = query.data?.connections ?? [];
  const builtIns = query.data?.builtIns ?? [];

  const act = async (id, action) => {
    setError('');
    setBusyId(id);
    try {
      await action();
      await refresh();
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusyId(null);
    }
  };

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
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1}
        sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between', mb: 0.5 }}
      >
        <Box>
          <Typography sx={{ fontSize: 16, fontWeight: 500 }}>{t('byok.title')}</Typography>
          <Typography variant="caption" color="text.secondary">
            {t('byok.description')}
          </Typography>
        </Box>
        <Button
          variant="contained"
          startIcon={<AddRounded />}
          onClick={() => {
            setEditing(null);
            setDialogOpen(true);
          }}
        >
          {t('byok.add')}
        </Button>
      </Stack>

      {error && (
        <Alert severity="error" sx={{ mt: 1.5 }}>
          {error}
        </Alert>
      )}

      {query.isLoading ? (
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 2 }}>
          <CircularProgress size={16} />
          <Typography variant="body2" color="text.secondary">
            {t('byok.loading')}
          </Typography>
        </Stack>
      ) : connections.length === 0 ? (
        <Alert severity="info" sx={{ mt: 1.5 }}>
          {t('byok.empty')}
        </Alert>
      ) : (
        <Stack spacing={1.2} sx={{ mt: 1.5 }}>
          {connections.map(connection => (
            <Box
              key={connection.id}
              sx={{
                border: `1px solid ${connection.isDefault ? googleTokens.primarySoft : googleTokens.outline}`,
                bgcolor: connection.isDefault ? '#F8FBFF' : '#FFFFFF',
                borderRadius: 3,
                p: 1.5
              }}
            >
              <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={1}
                sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between' }}
              >
                <Stack
                  direction="row"
                  spacing={0.75}
                  useFlexGap
                  sx={{ flexWrap: 'wrap', alignItems: 'center' }}
                >
                  <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
                    {connection.displayName}
                  </Typography>
                  <Chip size="small" label={connection.providerLabel} />
                  {connection.isDefault && (
                    <Chip
                      size="small"
                      color="primary"
                      icon={<StarRounded />}
                      label={t('common.default')}
                    />
                  )}
                  {!connection.enabled && <Chip size="small" label={t('common.disabled')} />}
                  {statusChip(connection, t)}
                  <Typography variant="caption" color="text.secondary">
                    {connection.modelName} · {connection.keyMasked}
                  </Typography>
                </Stack>
                <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap' }}>
                  <Button
                    size="small"
                    disabled={busyId === connection.id}
                    onClick={() =>
                      act(connection.id, () => modelConnectionsApi.test(connection.id))
                    }
                  >
                    {t('byok.test')}
                  </Button>
                  {!connection.isDefault && (
                    <Button
                      size="small"
                      disabled={busyId === connection.id || !connection.enabled}
                      onClick={() =>
                        act(connection.id, () => modelConnectionsApi.setDefault(connection.id))
                      }
                    >
                      {t('byok.setDefault')}
                    </Button>
                  )}
                  <Button
                    size="small"
                    onClick={() => {
                      setEditing(connection);
                      setDialogOpen(true);
                    }}
                  >
                    {t('common.edit')}
                  </Button>
                  <Button
                    size="small"
                    color={confirmDelete === connection.id ? 'error' : 'inherit'}
                    disabled={busyId === connection.id}
                    onClick={() => {
                      if (confirmDelete === connection.id) {
                        setConfirmDelete(null);
                        act(connection.id, () => modelConnectionsApi.remove(connection.id));
                      } else {
                        setConfirmDelete(connection.id);
                      }
                    }}
                  >
                    {confirmDelete === connection.id ? t('byok.confirmDelete') : t('common.delete')}
                  </Button>
                </Stack>
              </Stack>
              {connection.lastTestStatus === 'FAILED' && connection.lastTestMessage && (
                <Typography variant="caption" color="error" sx={{ display: 'block', mt: 0.75 }}>
                  {t('byok.lastFailure', { message: connection.lastTestMessage })}
                </Typography>
              )}
              {(connection.inputPricePer1m != null || connection.outputPricePer1m != null) && (
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ display: 'block', mt: 0.5 }}
                >
                  {t('byok.priceNote', {
                    input: connection.inputPricePer1m ?? t('byok.notSet'),
                    output: connection.outputPricePer1m ?? t('byok.notSet'),
                    currency: connection.currency
                  })}
                </Typography>
              )}
            </Box>
          ))}
        </Stack>
      )}

      <Divider sx={{ my: 2 }} />
      <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.5 }}>
        {t('byok.builtInTitle')}
      </Typography>
      <Stack spacing={0.75}>
        {builtIns.map(item => (
          <Stack
            key={item.id}
            direction="row"
            spacing={0.75}
            sx={{ alignItems: 'center', flexWrap: 'wrap' }}
          >
            <Chip size="small" label={item.providerLabel} />
            <Typography variant="body2">{item.displayName}</Typography>
            <Typography variant="caption" color="text.secondary">
              {item.modelName}
            </Typography>
            <Chip size="small" label={t('byok.readOnly')} />
          </Stack>
        ))}
      </Stack>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
        {t('byok.builtInNote')}
      </Typography>

      <ModelConnectionDialog
        open={dialogOpen}
        connection={editing}
        presets={query.data?.presets ?? []}
        onClose={() => setDialogOpen(false)}
        onSaved={() => {
          refresh();
        }}
      />
    </Box>
  );
}
