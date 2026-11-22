import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography
} from '@mui/material';
import OpenInNewRounded from '@mui/icons-material/OpenInNewRounded';
import StatusChip from '../../components/StatusChip';
import { nodeTitle, planRoleLabel, statusLabel, stopCauseLabel } from '../../constants/labels';
import { dateTime, duration, nodeDuration, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';
import PlanAdjustmentEditor from '../workflows/PlanAdjustmentEditor';
import RunExtensionsPanel from '../workflows/RunExtensionsPanel';
import BugEvidencePanel from '../bug/BugEvidencePanel';
import RunEvidencePanel from './RunEvidencePanel';

function Section({ title, subtitle, children, action }) {
  return (
    <Box
      sx={{
        bgcolor: '#FFFFFF',
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 4,
        p: { xs: 2, sm: 2.5 }
      }}
    >
      <Stack
        direction="row"

        spacing={1.5}
        sx={{ mb: 1.5, alignItems: 'baseline', justifyContent: 'space-between' }}
      >
        <Box>
          <Typography sx={{ fontSize: 15, fontWeight: 500 }}>{title}</Typography>
          {subtitle && (
            <Typography variant="caption" color="text.secondary">
              {subtitle}
            </Typography>
          )}
        </Box>
        {action}
      </Stack>
      {children}
    </Box>
  );
}

/** 列表视图：节点表 + 计划确认、预算、阻塞、恢复与运行扩展证据。 */
export default function RunListView({
  detail,
  view,
  onAction,
  onSelectNode,
  onRecover,
  onSwitchRun,
  onSubmitSupplement,
  onArchiveTask
}) {
  const { t, locale } = useI18n();
  const [path, setPath] = useState('tests/test_calc.py');
  const [content, setContent] = useState('');
  const [sending, setSending] = useState(false);
  const nodes = view?.nodes ?? [];
  const byId = new Map(nodes.map(node => [node.id, node]));
  const plan = view?.plan;
  const approveAction = (view?.availableActions ?? []).find(
    action => action.actionCode === 'APPROVE_PLAN'
  );
  const waitingPlan = plan?.reviewRequired && !plan?.approvedAt && view?.runStatus === 'PENDING';
  const budget = view?.budget;
  const counts = view?.counts;
  const recovery = view?.recovery;
  const support = view?.recoverySupport;
  const supplement = view?.supplement;
  const handover = view?.handover;
  const run = detail?.run;
  const task = detail?.task;
  const checkpoints = [...(detail?.workflowCheckpoints ?? []), ...(detail?.checkpoints ?? [])];

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Stack spacing={2} sx={{ maxWidth: 1180, mx: 'auto', px: { xs: 2, sm: 3 }, py: 2.5 }}>
        {view?.reason && (
          <Alert severity="warning">
            {view.reason}
            {view.stopCause &&
              ` · ${t('list.stopCause', { cause: stopCauseLabel(view.stopCause, locale) })}`}
          </Alert>
        )}
        {(view?.blocking ?? []).map((item, index) => (
          <Alert key={`${item.code}:${index}`} severity="warning">
            {item.message}
            {item.blockingNodeIds?.length > 0 && (
              <Typography variant="caption" sx={{ display: 'block', mt: 0.5 }}>
                {t('list.downstreamNotStarted')}
                {item.blockingNodeIds
                  .map(id =>
                    byId.get(id) ? nodeTitle(byId.get(id), undefined, locale) : shortId(id)
                  )
                  .join(locale === 'en-US' ? ', ' : '、')}
              </Typography>
            )}
          </Alert>
        ))}

        {waitingPlan && (
          <Alert
            severity="info"
            action={
              approveAction?.enabled ? (
                <Button size="small" variant="contained" onClick={() => onAction(approveAction)}>
                  {t('list.confirmAndRun')}
                </Button>
              ) : undefined
            }
          >
            {t('list.planWaiting')}
          </Alert>
        )}

        <Section
          title={t('list.nodesTitle')}
          subtitle={
            plan
              ? t('list.planSubtitle', {
                  version: plan.version,
                  template: plan.templateId,
                  templateVersion: plan.templateVersion,
                  attempts: plan.maxAttempts
                })
              : t('list.noPlanVersion')
          }
          action={
            <Typography variant="caption" color="text.secondary">
              {t('list.counts', {
                completed: counts?.completed ?? 0,
                total: counts?.total ?? nodes.length,
                models: counts?.modelCalls ?? 0,
                tools: counts?.toolCalls ?? 0
              })}
            </Typography>
          }
        >
          <TableContainer>
            <Table size="small" aria-label={t('list.nodesTitle')}>
              <TableHead>
                <TableRow>
                  <TableCell>{t('list.colNode')}</TableCell>
                  <TableCell>{t('list.colRole')}</TableCell>
                  <TableCell>{t('common.status')}</TableCell>
                  <TableCell>{t('list.colDuration')}</TableCell>
                  <TableCell>{t('list.colAttempts')}</TableCell>
                  <TableCell>{t('list.colDefinition')}</TableCell>
                  <TableCell>{t('list.colDependencies')}</TableCell>
                  <TableCell align="right">{t('common.detail')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {nodes.map(node => (
                  <TableRow key={node.id} hover>
                    <TableCell sx={{ fontWeight: 500 }}>
                      {nodeTitle(node, undefined, locale)}
                    </TableCell>
                    <TableCell>{planRoleLabel(node.role, locale) || '—'}</TableCell>
                    <TableCell>
                      <StatusChip value={node.status} />
                    </TableCell>
                    <TableCell>{duration(nodeDuration(node, view?.generatedAt))}</TableCell>
                    <TableCell>{node.attemptCount ?? 0}</TableCell>
                    <TableCell sx={{ fontFamily: googleTokens.mono, fontSize: 12 }}>
                      {node.definitionId
                        ? `${node.definitionId} v${node.definitionVersion ?? '—'}`
                        : node.agentName || '—'}
                    </TableCell>
                    <TableCell sx={{ maxWidth: 220 }}>
                      {(node.dependencyIds ?? [])
                        .map(id =>
                          byId.get(id) ? nodeTitle(byId.get(id), undefined, locale) : shortId(id)
                        )
                        .join(locale === 'en-US' ? ', ' : '、') || '—'}
                    </TableCell>
                    <TableCell align="right">
                      <Button
                        size="small"
                        endIcon={<OpenInNewRounded fontSize="small" />}
                        onClick={() => onSelectNode(node.id)}
                      >
                        {t('list.view')}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
                {nodes.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={8}>
                      <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
                        {t('list.noNodes')}
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
        </Section>

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', lg: 'repeat(2, minmax(0,1fr))' },
            gap: 2,
            alignItems: 'start'
          }}
        >
          <Section title={t('list.budgetTitle')} subtitle={t('list.budgetSubtitle')}>
            <Stack spacing={0.6}>
              <Typography variant="body2">
                {t('list.usedRounds', {
                  used: budget?.usedRounds ?? run?.ROUNDS ?? '—',
                  remaining: budget?.remainingRounds ?? t('list.noLimit')
                })}
              </Typography>
              <Typography variant="body2">
                {t('list.usedTools', {
                  used: budget?.usedTools ?? run?.TOOLS ?? '—',
                  remaining: budget?.remainingTools ?? t('list.noLimit')
                })}
              </Typography>
              <Typography variant="body2">
                {t('list.elapsed', {
                  used: duration(view?.elapsedMs ?? run?.ELAPSED_MS),
                  remaining:
                    budget?.remainingMs == null ? t('list.noLimit') : duration(budget.remainingMs)
                })}
              </Typography>
              <Typography variant="body2">
                {t('list.nodeCounts', {
                  pending: counts?.pending ?? 0,
                  running: counts?.running ?? 0,
                  reused: counts?.reused ?? 0,
                  checks: counts?.checks ?? 0
                })}
              </Typography>
              {counts?.unknownTokenCalls > 0 && (
                <Typography variant="caption" color="warning.dark">
                  {t('list.unknownTokens', { count: counts.unknownTokenCalls })}
                </Typography>
              )}
            </Stack>
          </Section>

          <Section title={t('list.recoveryTitle')} subtitle={t('list.recoverySubtitle')}>
            <Stack spacing={1}>
              {support && (
                <Typography
                  variant="body2"
                  color={support.supported ? 'text.primary' : 'warning.dark'}
                >
                  {support.supported ? t('list.recoverySupported') : t('list.recoveryUnsupported')}
                  {': '}
                  {support.reason}
                </Typography>
              )}
              {recovery && (
                <Stack spacing={0.4}>
                  <Typography variant="caption" color="text.secondary">
                    {t('list.parentRun')}
                    {recovery.parentRunId ? (
                      <Button size="small" onClick={() => onSwitchRun?.(recovery.parentRunId)}>
                        #{shortId(recovery.parentRunId)}
                      </Button>
                    ) : (
                      t('list.notRecorded')
                    )}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t('list.inherited', {
                      rounds: recovery.inherited?.rounds ?? t('common.unknown'),
                      tools: recovery.inherited?.tools ?? t('common.unknown'),
                      newModels: recovery.newModelCalls,
                      newTools: recovery.newToolCalls
                    })}
                  </Typography>
                </Stack>
              )}
              {handover && (
                <Alert severity={handover.revalidationRequired ? 'warning' : 'info'}>
                  {t('list.handover', {
                    status:
                      handover.status === 'ACTIVE'
                        ? t('list.handoverActive')
                        : t('list.handoverReturned'),
                    time: dateTime(handover.takenAt, locale)
                  })}
                  {handover.revalidationRequired ? t('list.revalidation') : ''}
                </Alert>
              )}
              {checkpoints.length > 0 ? (
                <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
                  {checkpoints.map(checkpoint => {
                    const available = checkpoint.available ?? checkpoint.VALID;
                    const id = checkpoint.id ?? checkpoint.ID;
                    return (
                      <Button
                        key={id}
                        size="small"
                        variant="outlined"
                        disabled={!available}
                        onClick={() => onRecover(id)}
                      >
                        {checkpoint.completedNodes != null
                          ? t('list.recoverAfterSteps', { count: checkpoint.completedNodes })
                          : checkpoint.STAGE === 'BEFORE_REVIEW'
                            ? t('list.recoverBeforeReview')
                            : t('list.recoverBeforeAgent')}
                      </Button>
                    );
                  })}
                </Stack>
              ) : (
                <Typography variant="body2" color="text.secondary">
                  {t('list.noCheckpoints')}
                </Typography>
              )}
            </Stack>
          </Section>
        </Box>

        {supplement && (
          <Section
            title={
              supplement.status === 'APPLIED'
                ? t('list.supplementApplied')
                : t('list.awaitingSupplement')
            }
            subtitle={t('list.supplementSubtitle', {
              version: view.taskVersion,
              node: supplement.nodeLabel || t('list.notRecorded')
            })}
          >
            <Typography variant="body2" sx={{ mb: 1.2 }}>
              {supplement.question}
            </Typography>
            {supplement.status === 'APPLIED' ? (
              <Typography variant="body2" color="text.secondary">
                {t('list.supplementAppliedDescription', {
                  path: supplement.answerPath || t('list.notRecorded')
                })}
              </Typography>
            ) : (
              <Stack spacing={1}>
                <TextField
                  size="small"
                  label={t('list.testFilePath')}
                  value={path}
                  onChange={event => setPath(event.target.value)}
                  sx={{ maxWidth: 320 }}
                />

                <TextField
                  label={t('list.testFileContent')}
                  multiline
                  minRows={5}
                  value={content}
                  onChange={event => setContent(event.target.value)}
                  slotProps={{ htmlInput: { maxLength: 20000 } }}
                />

                <Button
                  variant="contained"
                  loading={sending}
                  sx={{ alignSelf: 'flex-start' }}
                  onClick={async () => {
                    setSending(true);
                    try {
                      await onSubmitSupplement?.(supplement.id, view.taskVersion, path, content);
                    } finally {
                      setSending(false);
                    }
                  }}
                >
                  {t('list.submitSupplement')}
                </Button>
                <Typography variant="caption" color="text.secondary">
                  {t('list.supplementHint', { version: view.taskVersion })}
                </Typography>
              </Stack>
            )}
          </Section>
        )}

        <PlanAdjustmentEditor detail={detail} />

        <RunEvidencePanel runId={run?.ID} detail={detail} />

        {task?.BUG_ID && (
          <Section title={t('list.bugEvidenceTitle')} subtitle={t('list.bugEvidenceSubtitle')}>
            <BugEvidencePanel runId={run?.ID} />
          </Section>
        )}

        <Box
          sx={{
            bgcolor: '#FFFFFF',
            border: `1px solid ${googleTokens.outline}`,
            borderRadius: 4,
            p: { xs: 2, sm: 2.5 }
          }}
        >
          <RunExtensionsPanel detail={detail} />
        </Box>

        <Section
          title={t('list.snapshotTitle')}
          subtitle={t('list.snapshotSubtitle')}
          action={
            <Button size="small" variant="outlined" onClick={onArchiveTask}>
              {task?.ARCHIVED_AT ? t('history.unarchive') : t('list.archiveTask')}
            </Button>
          }
        >
          <Stack spacing={0.5}>
            <Typography variant="body2">
              {t('list.snapshotCounts', {
                events: detail?.events?.length ?? t('list.onDemand'),
                checkpoints: detail?.checkpoints?.length ?? 0,
                agents: detail?.agents?.length ?? 0,
                tools: detail?.tools?.length ?? 0,
                models: detail?.models?.length ?? 0
              })}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {t('list.snapshotMeta', {
                run: shortId(run?.ID),
                task: shortId(task?.ID),
                time: dateTime(run?.CREATED_AT, locale)
              })}
            </Typography>
            <Chip
              size="small"
              label={t('list.executionStatus', { status: statusLabel(run?.STATUS, locale) || '—' })}
              sx={{ alignSelf: 'flex-start', bgcolor: '#F1F3F4', color: '#5F6368' }}
            />
          </Stack>
        </Section>
      </Stack>
    </Box>
  );
}
