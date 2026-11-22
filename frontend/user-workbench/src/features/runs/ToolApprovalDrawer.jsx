import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  Drawer,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { runsApi } from '../../api/runs';
import { useI18n } from '../../i18n/I18nProvider';
import { googleTokens } from '../../theme/googleTheme';

const riskColor = risk =>
  risk === 'HIGH' ? { bg: '#FCE8E6', fg: '#C5221F' } : { bg: '#FEF7E0', fg: '#B06000' };

/**
 * Gate 5.5 审批抽屉：批准、改参放行（服务端二次 Gate 5 复验）或拒绝并回传理由。
 */
export default function ToolApprovalDrawer({ runId, live = true }) {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const [edited, setEdited] = useState({});
  const [reasons, setReasons] = useState({});
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);
  const query = useQuery({
    queryKey: ['tool-approvals', runId],
    queryFn: ({ signal }) => runsApi.toolApprovals(runId, { signal }),
    enabled: Boolean(runId),
    refetchInterval: live ? 1500 : false
  });
  const pending = useMemo(
    () => (query.data ?? []).filter(item => item.status === 'PENDING'),
    [query.data]
  );
  useEffect(() => {
    setEdited(previous => {
      const next = { ...previous };
      pending.forEach(item => {
        if (!(item.id in next)) next[item.id] = item.originalArgsJson;
      });
      return next;
    });
  }, [pending]);

  const decide = async (approval, action) => {
    setBusy(approval.id);
    setError(null);
    try {
      const payload = {
        action,
        editedArgsJson: action === 'APPROVE_WITH_EDITED_ARGS' ? edited[approval.id] : null,
        reason: reasons[approval.id] || null
      };
      await runsApi.decideToolApproval(runId, approval.id, payload);
      await queryClient.invalidateQueries({ queryKey: ['tool-approvals', runId] });
      await queryClient.invalidateQueries({ queryKey: ['execution-view', runId] });
      await queryClient.invalidateQueries({ queryKey: ['run-events', runId] });
    } catch (failure) {
      setError(failure?.message || t('approval.failed'));
    } finally {
      setBusy(null);
    }
  };

  if (pending.length === 0) return null;

  return (
    <Drawer
      anchor="right"
      open
      hideBackdrop
      slotProps={{
        paper: {
          sx: {
            width: '100%',
            maxWidth: 420,
            top: 72,
            height: 'calc(100% - 88px)',
            borderRadius: '20px 0 0 20px',
            borderLeft: `1px solid ${googleTokens.outline}`,
            backgroundImage: 'none'
          }
        }
      }}
    >
      <Box sx={{ p: 2 }}>
        <Typography variant="h6" sx={{ fontWeight: 700 }}>
          {t('approval.title')}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {t('approval.subtitle')}
        </Typography>
        {error && (
          <Alert severity="error" sx={{ mt: 1.25 }}>
            {error}
          </Alert>
        )}
        <Stack spacing={1.5} sx={{ mt: 1.5 }}>
          {pending.map(approval => {
            const color = riskColor(approval.riskLevel);
            const changed = edited[approval.id] !== approval.originalArgsJson;
            return (
              <Box
                key={approval.id}
                sx={{
                  p: 1.5,
                  borderRadius: 3,
                  border: `1px solid ${googleTokens.outline}`,
                  bgcolor: '#FFFFFF'
                }}
              >
                <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', mb: 0.75 }}>
                  <Typography variant="subtitle2" sx={{ flex: 1, fontFamily: googleTokens.mono }}>
                    {approval.toolName}
                  </Typography>
                  <Chip
                    size="small"
                    label={t(`approval.risk.${approval.riskLevel}`)}
                    sx={{ bgcolor: color.bg, color: color.fg, fontWeight: 600 }}
                  />
                </Stack>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  {approval.riskReason}
                </Typography>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ display: 'block', mt: 0.5 }}
                >
                  {t('approval.expiresAt', { time: approval.expiresAt })}
                </Typography>
                <TextField
                  fullWidth
                  multiline
                  minRows={4}
                  maxRows={10}
                  size="small"
                  sx={{ mt: 1 }}
                  label={t('approval.args')}
                  value={edited[approval.id] ?? approval.originalArgsJson}
                  onChange={event =>
                    setEdited(previous => ({ ...previous, [approval.id]: event.target.value }))
                  }
                  slotProps={{ input: { sx: { fontFamily: googleTokens.mono, fontSize: 12 } } }}
                />
                <TextField
                  fullWidth
                  size="small"
                  sx={{ mt: 1 }}
                  label={t('approval.reason')}
                  value={reasons[approval.id] ?? ''}
                  onChange={event =>
                    setReasons(previous => ({ ...previous, [approval.id]: event.target.value }))
                  }
                />
                <Divider sx={{ my: 1.25 }} />
                <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap', rowGap: 0.75 }}>
                  <Button
                    size="small"
                    variant="contained"
                    disabled={busy === approval.id}
                    onClick={() => decide(approval, 'APPROVE')}
                  >
                    {t('approval.approve')}
                  </Button>
                  <Button
                    size="small"
                    variant="outlined"
                    disabled={busy === approval.id || !changed}
                    onClick={() => decide(approval, 'APPROVE_WITH_EDITED_ARGS')}
                  >
                    {t('approval.approveEdited')}
                  </Button>
                  <Button
                    size="small"
                    color="error"
                    disabled={busy === approval.id || !(reasons[approval.id] || '').trim()}
                    onClick={() => decide(approval, 'REJECT')}
                  >
                    {t('approval.reject')}
                  </Button>
                </Stack>
              </Box>
            );
          })}
        </Stack>
      </Box>
    </Drawer>
  );
}
