import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { runsApi } from '../../api/runs';
import { useI18n } from '../../i18n/I18nProvider';

const MODEL_MODES = ['', 'mock', 'user'];

/** 检查点的冻结状态：已完成节点、交接、模型调用与累计 Token，均为记录原值。 */
function FrozenSummary({ frozen, t }) {
  return (
    <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap', rowGap: 0.5, mt: 0.5 }}>
      <Chip
        size="small"
        label={t('checkpoint.frozenNodes', {
          done: frozen.completedNodes,
          total: frozen.totalNodes
        })}
      />
      <Chip size="small" label={t('checkpoint.frozenHandoffs', { count: frozen.handoffs })} />
      <Chip size="small" label={t('checkpoint.frozenModels', { count: frozen.modelCalls })} />
      <Chip
        size="small"
        label={t('checkpoint.frozenTokens', {
          input: frozen.inputTokens,
          output: frozen.outputTokens
        })}
      />
    </Stack>
  );
}

/**
 * Checkpoint 时间旅行面板：检视冻结状态，并从某处开一条分支运行。
 *
 * <p>分叉不会改写原运行：它按已落库的计划重建受控图，走与候选计划确认相同的物化路径，并在
 * 新的分支运行上重新记录证据。面板明确写出它不复制节点级固定模型与上游交接载荷。
 */
export default function CheckpointTimeTravelPanel({ runId, onForked }) {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [nodeKey, setNodeKey] = useState('');
  const [addendum, setAddendum] = useState('');
  const [modelMode, setModelMode] = useState('');
  const [error, setError] = useState('');

  const checkpoints = useQuery({
    queryKey: ['run-checkpoints', runId],
    queryFn: () => runsApi.checkpoints(runId),
    enabled: Boolean(runId)
  });
  const forks = useQuery({
    queryKey: ['run-forks', runId],
    queryFn: () => runsApi.forks(runId),
    enabled: Boolean(runId)
  });
  const timeline = useQuery({
    queryKey: ['run-timeline', runId],
    queryFn: () => runsApi.timeline(runId),
    enabled: Boolean(runId)
  });

  const nodeKeys = [
    ...new Set(
      (timeline.data?.spans ?? []).filter(span => span.kind === 'NODE').map(span => span.nodeKey)
    )
  ].filter(Boolean);

  const fork = useMutation({
    mutationFn: payload => runsApi.fork(runId, payload),
    onSuccess: created => {
      setOpen(false);
      setError('');
      queryClient.invalidateQueries({ queryKey: ['run-forks', runId] });
      onForked?.(created.runId);
    },
    onError: failure => setError(failure.message)
  });

  const items = checkpoints.data?.checkpoints ?? [];

  return (
    <Box data-testid="checkpoint-time-travel">
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.75 }}>
        <Typography variant="subtitle2">{t('checkpoint.title')}</Typography>
        <Box sx={{ flex: 1 }} />
        <Button
          size="small"
          variant="outlined"
          data-testid="checkpoint-fork-open"
          onClick={() => setOpen(true)}
        >
          {t('checkpoint.forkAction')}
        </Button>
      </Stack>

      {checkpoints.error && <Alert severity="error">{checkpoints.error.message}</Alert>}
      {!checkpoints.isLoading && !items.length && (
        <Alert severity="info" data-testid="checkpoint-empty">
          {t('checkpoint.empty')}
        </Alert>
      )}

      <Stack spacing={0.75}>
        {items.map(item => (
          <Box
            key={item.id}
            data-testid={`checkpoint-${item.stage}`}
            sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2, p: 1 }}
          >
            <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                {item.stage}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {item.createdAt}
              </Typography>
            </Stack>
            <Typography variant="caption" color="text.secondary">
              {t('checkpoint.recorded', {
                rounds: item.rounds,
                tools: item.tools,
                ms: item.elapsedMs
              })}
            </Typography>
            <FrozenSummary frozen={item.frozen} t={t} />
            {item.frozen.completedNodeKeys?.length > 0 && (
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                {item.frozen.completedNodeKeys.join(' · ')}
              </Typography>
            )}
          </Box>
        ))}
      </Stack>

      {(forks.data?.forks ?? []).length > 0 && (
        <>
          <Divider sx={{ my: 1 }} />
          <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
            {t('checkpoint.branches')}
          </Typography>
          <Stack spacing={0.5}>
            {forks.data.forks.map(item => (
              <Typography
                key={item.runId}
                variant="caption"
                data-testid={`fork-${item.runId}`}
                sx={{ cursor: 'pointer', color: 'primary.main' }}
                onClick={() => onForked?.(item.runId)}
              >
                {item.runId} · {item.sourceNodeKey ?? t('checkpoint.wholeGraph')} · {item.modelMode}
              </Typography>
            ))}
          </Stack>
        </>
      )}

      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{t('checkpoint.forkTitle')}</DialogTitle>
        <DialogContent>
          <Stack spacing={1.25} sx={{ mt: 0.5 }}>
            <Alert severity="info">{t('checkpoint.forkBoundary')}</Alert>
            <TextField
              select
              size="small"
              label={t('checkpoint.sourceNode')}
              value={nodeKey}
              onChange={event => setNodeKey(event.target.value)}
              data-testid="fork-node"
            >
              <MenuItem value="">{t('checkpoint.wholeGraph')}</MenuItem>
              {nodeKeys.map(key => (
                <MenuItem key={key} value={key}>
                  {key}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              size="small"
              multiline
              minRows={2}
              label={t('checkpoint.addendum')}
              value={addendum}
              onChange={event => setAddendum(event.target.value)}
              slotProps={{ htmlInput: { maxLength: 2000 } }}
              data-testid="fork-addendum"
            />
            <TextField
              select
              size="small"
              label={t('checkpoint.modelOverride')}
              value={modelMode}
              onChange={event => setModelMode(event.target.value)}
              data-testid="fork-model"
            >
              {MODEL_MODES.map(mode => (
                <MenuItem key={mode || 'inherit'} value={mode}>
                  {mode ? mode : t('checkpoint.inheritModel')}
                </MenuItem>
              ))}
            </TextField>
            {error && <Alert severity="error">{error}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>{t('common.cancel')}</Button>
          <Button
            variant="contained"
            data-testid="fork-submit"
            loading={fork.isPending}
            onClick={() =>
              fork.mutate({
                sourceNodeKey: nodeKey || null,
                promptAddendum: addendum || null,
                modelMode: modelMode || null
              })
            }
          >
            {t('checkpoint.forkSubmit')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
