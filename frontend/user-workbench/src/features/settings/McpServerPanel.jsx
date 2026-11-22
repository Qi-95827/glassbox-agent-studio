import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { mcpApi } from '../../api/mcp';
import { useToast } from '../../components/ToastProvider';
import EmptyState from '../../components/EmptyState';
import McpServerDialog from './McpServerDialog';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

/** MCP 工具源管理：注册、探测、逐工具风险等级与启停；凭据永不回显。 */
export default function McpServerPanel() {
  const { t } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState(null);
  const [busy, setBusy] = useState(false);
  const servers = useQuery({
    queryKey: ['mcp-servers'],
    queryFn: ({ signal }) => mcpApi.list({ signal })
  });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['mcp-servers'] });

  const probe = async server => {
    setBusy(true);
    try {
      await mcpApi.probe(server.id);
      await refresh();
      toast.success(t('mcp.probed'));
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy(false);
    }
  };

  const toggleTool = async (server, tool, payload) => {
    try {
      await mcpApi.updateTool(server.id, tool.id, payload);
      await refresh();
    } catch (error) {
      toast.error(error.message);
    }
  };

  const remove = async server => {
    try {
      await mcpApi.remove(server.id);
      await refresh();
      toast.success(t('mcp.deleted'));
    } catch (error) {
      toast.error(error.message);
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
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
        <Box>
          <Typography sx={{ fontSize: 15, fontWeight: 500 }}>{t('mcp.title')}</Typography>
          <Typography variant="caption" color="text.secondary">
            {t('mcp.description')}
          </Typography>
        </Box>
        <Button variant="contained" onClick={() => setDialog({})}>
          {t('mcp.add')}
        </Button>
      </Stack>

      <Alert severity="info" sx={{ mb: 1.5 }}>
        {t('mcp.notice')}
      </Alert>

      <Stack spacing={1.5}>
        {(servers.data ?? []).map(server => (
          <Box
            key={server.id}
            sx={{
              p: 1.5,
              borderRadius: 3,
              border: `1px solid ${googleTokens.outline}`,
              bgcolor: '#FAFAFA'
            }}
          >
            <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <Typography variant="subtitle2" sx={{ flex: 1 }}>
                {server.name} · {server.slug}
              </Typography>
              <Chip size="small" label={server.transport} />
              <Chip
                size="small"
                label={server.enabled ? t('mcp.enabled') : t('mcp.disabled')}
                color={server.enabled ? 'success' : 'default'}
              />
              {server.headersConfigured && <Chip size="small" label={t('mcp.headersSet')} />}
              <Button size="small" variant="outlined" disabled={busy} onClick={() => probe(server)}>
                {t('mcp.probe')}
              </Button>
              <Button size="small" onClick={() => setDialog(server)}>
                {t('common.edit')}
              </Button>
              <Button size="small" color="error" onClick={() => remove(server)}>
                {t('common.delete')}
              </Button>
            </Stack>
            <Stack spacing={0.75} sx={{ mt: 1 }}>
              {(server.tools ?? []).map(tool => (
                <Stack
                  key={tool.id}
                  direction={{ xs: 'column', sm: 'row' }}
                  spacing={0.75}
                  sx={{
                    alignItems: { sm: 'center' },
                    px: 0.75,
                    py: 0.5,
                    bgcolor: '#FFFFFF',
                    borderRadius: 2
                  }}
                >
                  <Typography variant="caption" sx={{ flex: 1, fontFamily: googleTokens.mono }}>
                    mcp::{server.slug}::{tool.name}
                  </Typography>
                  <TextField
                    select
                    size="small"
                    value={tool.riskLevel}
                    onChange={event =>
                      toggleTool(server, tool, {
                        riskLevel: event.target.value,
                        enabled: tool.enabled
                      })
                    }
                    sx={{ minWidth: 110 }}
                  >
                    <MenuItem value="LOW">{t('mcp.risk.LOW')}</MenuItem>
                    <MenuItem value="HIGH">{t('mcp.risk.HIGH')}</MenuItem>
                  </TextField>
                  <Switch
                    size="small"
                    checked={tool.enabled}
                    onChange={event =>
                      toggleTool(server, tool, {
                        riskLevel: tool.riskLevel,
                        enabled: event.target.checked
                      })
                    }
                    inputProps={{ 'aria-label': t('mcp.toggleTool') }}
                  />
                </Stack>
              ))}
              {(server.tools ?? []).length === 0 && (
                <Typography variant="caption" color="text.secondary">
                  {t('mcp.noTools')}
                </Typography>
              )}
            </Stack>
          </Box>
        ))}
        {!servers.isLoading && (servers.data ?? []).length === 0 && (
          <EmptyState compact title={t('mcp.emptyTitle')} description={t('mcp.emptyDescription')} />
        )}
      </Stack>

      <McpServerDialog
        open={Boolean(dialog)}
        initial={dialog?.id ? dialog : null}
        onClose={() => setDialog(null)}
        onSaved={refresh}
      />
    </Box>
  );
}
