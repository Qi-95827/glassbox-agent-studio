import { useEffect, useState } from 'react';
import { Alert, Box, Chip, Divider, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { runsApi } from '../../api/runs';
import { useI18n } from '../../i18n/I18nProvider';
import CodeDiffView from './CodeDiffView';

/** 指标对照：两侧各自一行，缺失值显示为「—」而不是 0，避免把未知当成相等。 */
function MetricRow({ label, base, target }) {
  return (
    <Stack direction="row" spacing={1} sx={{ py: 0.25 }}>
      <Typography variant="caption" color="text.secondary" sx={{ width: 140, flexShrink: 0 }}>
        {label}
      </Typography>
      <Typography variant="caption" sx={{ flex: 1 }} data-testid="diff-base">
        {base ?? '—'}
      </Typography>
      <Typography variant="caption" sx={{ flex: 1 }} data-testid="diff-target">
        {target ?? '—'}
      </Typography>
    </Stack>
  );
}

/**
 * 主线 vs 分支的结构化对照。
 *
 * <p>上半部分是指标双轨，下半部分是逐节点产出对照；节点产出用同一套 {@link CodeDiffView} 做并排
 * 行级对比，因此「哪个节点的话变了」可以直接看出来。文件内容不按版本留存，所以这里只对照声明写入
 * 范围，并把这条限制显式写出来。
 */
export default function RunCompareDiffView({ runId, candidates = [], onOpenRun }) {
  const { t } = useI18n();
  const [otherRunId, setOtherRunId] = useState('');

  useEffect(() => {
    if (!otherRunId && candidates.length) setOtherRunId(candidates[0].runId);
  }, [candidates, otherRunId]);

  const diff = useQuery({
    queryKey: ['run-diff', runId, otherRunId],
    queryFn: () => runsApi.diff(runId, otherRunId),
    enabled: Boolean(runId && otherRunId)
  });

  if (!candidates.length)
    return (
      <Alert severity="info" data-testid="compare-empty">
        {t('compare.noBranches')}
      </Alert>
    );

  return (
    <Box data-testid="run-compare-diff">
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
        <TextField
          select
          size="small"
          label={t('compare.branch')}
          value={otherRunId}
          onChange={event => setOtherRunId(event.target.value)}
          sx={{ minWidth: 260 }}
          data-testid="compare-branch"
        >
          {candidates.map(item => (
            <MenuItem key={item.runId} value={item.runId}>
              {item.label ?? item.runId}
            </MenuItem>
          ))}
        </TextField>
        <Typography
          variant="caption"
          color="primary"
          sx={{ cursor: 'pointer' }}
          onClick={() => onOpenRun?.(otherRunId)}
        >
          {t('compare.openBranch')}
        </Typography>
      </Stack>

      {diff.error && <Alert severity="error">{diff.error.message}</Alert>}

      {diff.data && (
        <>
          <Stack direction="row" spacing={1}>
            <Typography variant="caption" sx={{ width: 140, flexShrink: 0 }} color="text.secondary">
              {t('compare.metric')}
            </Typography>
            <Typography variant="caption" sx={{ flex: 1, fontWeight: 600 }}>
              {t('compare.base', { id: diff.data.baseRunId.slice(0, 8) })}
            </Typography>
            <Typography variant="caption" sx={{ flex: 1, fontWeight: 600 }}>
              {t('compare.target', { id: diff.data.targetRunId.slice(0, 8) })}
            </Typography>
          </Stack>
          <MetricRow
            label={t('compare.status')}
            base={diff.data.base.status}
            target={diff.data.target.status}
          />
          <MetricRow
            label={t('compare.elapsed')}
            base={diff.data.base.elapsedMs}
            target={diff.data.target.elapsedMs}
          />
          <MetricRow
            label={t('compare.nodes')}
            base={`${diff.data.base.completedNodes}/${diff.data.base.totalNodes}`}
            target={`${diff.data.target.completedNodes}/${diff.data.target.totalNodes}`}
          />
          <MetricRow
            label={t('compare.calls')}
            base={`${diff.data.base.modelCalls} / ${diff.data.base.toolCalls}`}
            target={`${diff.data.target.modelCalls} / ${diff.data.target.toolCalls}`}
          />
          <MetricRow
            label={t('compare.tokens')}
            base={`${diff.data.base.inputTokens} / ${diff.data.base.outputTokens}`}
            target={`${diff.data.target.inputTokens} / ${diff.data.target.outputTokens}`}
          />

          <Divider sx={{ my: 1 }} />

          <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
            {t('compare.nodesTitle')}
          </Typography>
          <Stack spacing={1.25}>
            {diff.data.nodes.map(node => (
              <Box
                key={node.key}
                data-testid={`compare-node-${node.key}`}
                sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2, p: 1 }}
              >
                <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', mb: 0.5 }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {node.title ?? node.key}
                  </Typography>
                  <Chip size="small" label={node.role ?? '—'} />
                  <Chip size="small" label={node.base?.status ?? t('compare.absent')} />
                  <Typography variant="caption" color="text.secondary">
                    →
                  </Typography>
                  <Chip size="small" label={node.target?.status ?? t('compare.absent')} />
                </Stack>
                <CodeDiffView
                  left={node.base?.output ?? ''}
                  right={node.target?.output ?? ''}
                  leftLabel={t('compare.base', { id: diff.data.baseRunId.slice(0, 8) })}
                  rightLabel={t('compare.target', { id: diff.data.targetRunId.slice(0, 8) })}
                  testId={`node-diff-${node.key}`}
                />
              </Box>
            ))}
          </Stack>

          <Divider sx={{ my: 1 }} />
          <Typography variant="subtitle2">{t('compare.scopeTitle')}</Typography>
          <Typography variant="caption" color="text.secondary">
            {t('compare.onlyInBase')}: {(diff.data.onlyInBase ?? []).join(', ') || '—'}
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
            {t('compare.onlyInTarget')}: {(diff.data.onlyInTarget ?? []).join(', ') || '—'}
          </Typography>

          {(diff.data.limitations ?? []).map(item => (
            <Typography
              key={item}
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block', mt: 0.5 }}
            >
              · {item}
            </Typography>
          ))}
        </>
      )}
    </Box>
  );
}
