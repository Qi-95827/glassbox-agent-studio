import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography
} from '@mui/material';
import { useQueryClient } from '@tanstack/react-query';
import {
  useWorkspaceApprovals,
  useWorkspaceCommits,
  useWorkspaceDeliveries,
  useWorkspaceStatus,
  useWorkspaceUntracked
} from '../../hooks/useWorkspaces';
import { workspacesApi } from '../../api/workspaces';
import StatusChip from '../../components/StatusChip';
import { useToast } from '../../components/ToastProvider';
import DeliveryStepper from './DeliveryStepper';
import { dateTime, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import { statusLabel } from '../../constants/labels';
import { useI18n } from '../../i18n/I18nProvider';

/** Git 与交付：审查决定、本地提交、未跟踪文件复核、推送/合并审批。 */
export default function WorkspaceGitPanel({ workspace }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const status = useWorkspaceStatus(workspace.id);
  const approvals = useWorkspaceApprovals(workspace.id);
  const commits = useWorkspaceCommits(workspace.id);
  const deliveries = useWorkspaceDeliveries(workspace.id);
  const untracked = useWorkspaceUntracked(workspace.id);
  const [approvalId, setApprovalId] = useState('');
  const [message, setMessage] = useState('');
  const [untrackedPath, setUntrackedPath] = useState('');
  const [content, setContent] = useState('');
  const [remote, setRemote] = useState('origin');
  const [target, setTarget] = useState('main');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['workspace-status', workspace.id] });
    queryClient.invalidateQueries({ queryKey: ['workspace-approvals', workspace.id] });
    queryClient.invalidateQueries({ queryKey: ['workspace-commits', workspace.id] });
    queryClient.invalidateQueries({ queryKey: ['workspace-deliveries', workspace.id] });
    queryClient.invalidateQueries({ queryKey: ['workspace-untracked', workspace.id] });
  };

  const run = async (task, success) => {
    setBusy(true);
    try {
      const result = await task();
      refresh();
      toast.success(typeof success === 'function' ? success(result) : success);
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy(false);
    }
  };

  const approved = (approvals.data ?? []).filter(row => row.STATUS === 'APPROVED');
  const hasReviewableChanges =
    Boolean(status.data?.changes) || (untracked.data ?? []).some(file => file.reviewed);
  const hasLocalCommit = (commits.data ?? []).length > 0;

  return (
    <Stack spacing={2.5}>
      <Alert severity="warning">{t('git.boundary')}</Alert>

      <DeliveryStepper workspaceId={workspace.id} />

      <Box>
        <Stack
          direction="row"
          sx={{ mb: 1, justifyContent: 'space-between', alignItems: 'center' }}
        >
          <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{t('git.reviewTitle')}</Typography>
          <Tooltip title={hasReviewableChanges ? t('git.reviewHint') : t('git.noReviewHint')}>
            <span>
              <Button
                size="small"
                variant="outlined"
                loading={busy}
                disabled={!hasReviewableChanges}
                onClick={() =>
                  run(() => workspacesApi.proposeApproval(workspace.id), t('git.reviewCreated'))
                }
              >
                {hasReviewableChanges ? t('git.proposeReview') : t('git.noDiffToReview')}
              </Button>
            </span>
          </Tooltip>
        </Stack>
        <Typography variant="caption" color="text.secondary">
          {t('git.reviewNote')}
        </Typography>
        <Stack spacing={1} sx={{ mt: 1 }}>
          {(approvals.data ?? []).map(row => (
            <Stack
              key={row.ID}
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1}
              sx={{
                p: 1.25,
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 2.5,
                alignItems: { sm: 'center' }
              }}
            >
              <StatusChip
                value={
                  row.STATUS === 'STALE' && !hasReviewableChanges && hasLocalCommit
                    ? 'COMPLETED'
                    : row.STATUS
                }
              />
              <Typography variant="body2" sx={{ flex: 1, fontFamily: googleTokens.mono }}>
                #{shortId(row.ID)} · {dateTime(row.UPDATED_AT || row.CREATED_AT, locale)}
                {row.STATUS === 'STALE' && !hasReviewableChanges && hasLocalCommit
                  ? t('git.usedForCommit')
                  : ''}
              </Typography>
              {row.STATUS === 'PENDING' && (
                <Stack direction="row" spacing={0.5}>
                  <Button
                    size="small"
                    variant="outlined"
                    onClick={() =>
                      run(
                        () => workspacesApi.decideApproval(row.ID, 'APPROVE'),
                        t('git.approvedRecorded')
                      )
                    }
                  >
                    {t('git.approve')}
                  </Button>
                  <Button
                    size="small"
                    color="error"
                    onClick={() =>
                      run(
                        () => workspacesApi.decideApproval(row.ID, 'REJECT'),
                        t('git.rejectedRecorded')
                      )
                    }
                  >
                    {t('git.reject')}
                  </Button>
                </Stack>
              )}
            </Stack>
          ))}
          {(approvals.data ?? []).length === 0 && (
            <Typography variant="body2" color="text.secondary">
              {t('git.noReviews')}
            </Typography>
          )}
        </Stack>
      </Box>

      <Divider />

      <Box>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{t('git.commitTitle')}</Typography>
        <Typography variant="caption" color="text.secondary">
          {t('git.commitNote')}
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25} sx={{ mt: 1.2 }}>
          <TextField
            select
            size="small"
            label={t('git.selectApproval')}
            value={approvalId}
            onChange={event => setApprovalId(event.target.value)}
            sx={{ minWidth: 260 }}
          >
            {approved.map(row => (
              <MenuItem key={row.ID} value={row.ID}>
                {t('git.approvalOption', {
                  id: shortId(row.ID),
                  time: dateTime(row.UPDATED_AT, locale)
                })}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            size="small"
            label={t('git.commitMessage')}
            value={message}
            onChange={event => setMessage(event.target.value)}
            slotProps={{ htmlInput: { maxLength: 200 } }}
            sx={{ flex: 1, minWidth: 240 }}
          />

          <Button
            variant="contained"
            loading={busy}
            disabled={!approvalId || !message.trim()}
            onClick={() =>
              run(async () => {
                await workspacesApi.createCommit(workspace.id, {
                  approvalId,
                  message: message.trim()
                });
                setApprovalId('');
                setMessage('');
              }, t('git.commitCreated'))
            }
          >
            {t('git.createCommit')}
          </Button>
        </Stack>
        <Stack spacing={0.5} sx={{ mt: 1.2 }}>
          {(commits.data ?? []).map(row => (
            <Typography key={row.id} variant="caption" color="text.secondary">
              {row.kind || 'LOCAL_COMMIT'} · {row.status || row.STATUS} ·{' '}
              {(row.commitId || row.ID)?.slice(0, 8)} ·{' '}
              {dateTime(row.createdAt || row.CREATED_AT, locale)}
            </Typography>
          ))}
          {(commits.data ?? []).length === 0 && (
            <Typography variant="caption" color="text.secondary">
              {t('git.noCommits')}
            </Typography>
          )}
        </Stack>
      </Box>

      <Divider />

      <Box>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{t('git.untrackedTitle')}</Typography>
        <Typography variant="caption" color="text.secondary">
          {t('git.untrackedNote')}
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1 }}>
          <TextField
            size="small"
            label={t('git.newFilePath')}
            value={untrackedPath}
            onChange={event => setUntrackedPath(event.target.value)}
            placeholder="notes/verification.txt"
            sx={{ minWidth: 240 }}
          />

          <TextField
            size="small"
            label={t('git.fileContent')}
            value={content}
            onChange={event => setContent(event.target.value)}
            sx={{ flex: 1 }}
          />

          <Button
            variant="outlined"
            loading={busy}
            disabled={!untrackedPath.trim() || !content.trim()}
            onClick={() =>
              run(async () => {
                await workspacesApi.createFile(workspace.id, {
                  path: untrackedPath.trim(),
                  content
                });
                setUntrackedPath('');
                setContent('');
              }, t('git.fileCreated'))
            }
          >
            {t('git.createAndRegister')}
          </Button>
        </Stack>
        <Stack spacing={0.75} sx={{ mt: 1 }}>
          {(untracked.data ?? []).map(file => (
            <Stack
              key={file.path}
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1}

              sx={{
                p: 1.1,
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 2.5,
                alignItems: { sm: 'center' }
              }}
            >
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography
                  variant="body2"
                  sx={{ fontFamily: googleTokens.mono, overflowWrap: 'anywhere' }}
                >
                  {file.path}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {t('git.fileMeta', {
                    size: file.size,
                    sha: file.sha256?.slice(0, 12),
                    state: file.declared
                      ? t('git.reviewed', {
                          source: file.declarationSource || t('git.declared')
                        })
                      : t('git.unreviewed')
                  })}
                </Typography>
              </Box>
              {!file.declared && (
                <Button
                  size="small"
                  variant="outlined"
                  onClick={() =>
                    run(
                      () =>
                        workspacesApi.reviewUntracked(workspace.id, {
                          path: file.path,
                          sha256: file.sha256
                        }),
                      t('git.fileReviewed')
                    )
                  }
                >
                  {t('git.review')}
                </Button>
              )}
            </Stack>
          ))}
          {(untracked.data ?? []).length === 0 && (
            <Typography variant="caption" color="text.secondary">
              {t('git.noUntracked')}
            </Typography>
          )}
        </Stack>
      </Box>

      <Divider />

      <Box>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{t('git.deliveryTitle')}</Typography>
        <Typography variant="caption" color="text.secondary">
          {t('git.deliveryNote')}
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1, flexWrap: 'wrap' }}>
          <TextField
            size="small"
            label={t('git.remote')}
            value={remote}
            onChange={event => setRemote(event.target.value)}
            sx={{ width: 130 }}
          />

          <TextField
            size="small"
            label={t('git.targetBranch')}
            value={target}
            onChange={event => setTarget(event.target.value)}
            sx={{ width: 170 }}
          />

          <TextField
            size="small"
            label={t('git.note')}
            value={note}
            onChange={event => setNote(event.target.value)}
            sx={{ flex: 1, minWidth: 200 }}
          />

          <Button
            variant="outlined"
            loading={busy}
            onClick={() =>
              run(
                () =>
                  workspacesApi.prepareDelivery(workspace.id, {
                    kind: 'PUSH',
                    remote,
                    target,
                    note
                  }),
                t('git.pushPrepared')
              )
            }
          >
            {t('git.preparePush')}
          </Button>
          <Button
            variant="outlined"
            loading={busy}
            onClick={() =>
              run(
                () =>
                  workspacesApi.prepareDelivery(workspace.id, {
                    kind: 'MERGE',
                    remote,
                    target,
                    note
                  }),
                t('git.mergePrepared')
              )
            }
          >
            {t('git.prepareMerge')}
          </Button>
          <Button
            size="small"
            variant="text"
            onClick={async () => {
              const latestCommit = (commits.data ?? [])[0];
              const latestApproval = (approvals.data ?? [])[0];
              const summary = [
                t('git.prTitle'),
                t('git.prDevBranch', {
                  branch: workspace.workBranch || workspace.branch || workspace.id
                }),
                t('git.prTargetBranch', { target: target || 'main', remote: remote || 'origin' }),
                t('git.prBase', { id: shortId(workspace.baseCommit) }),
                latestCommit
                  ? t('git.prCommit', {
                      id: shortId(latestCommit.commitId || latestCommit.ID || latestCommit.id),
                      status: latestCommit.status || latestCommit.STATUS || 'CONFIRMED'
                    })
                  : t('git.prNoCommit'),
                latestApproval
                  ? t('git.prApproval', {
                      id: shortId(latestApproval.ID),
                      status: latestApproval.STATUS
                    })
                  : t('git.prNoApproval'),
                note ? t('git.prNote', { note }) : ''
              ]
                .filter(Boolean)
                .join('\n');
              try {
                await navigator.clipboard.writeText(summary);
                toast.success(t('git.prCopied'));
              } catch {
                toast.info(t('git.copyUnsupported'));
              }
            }}
          >
            {t('git.copyPr')}
          </Button>
        </Stack>
        <Stack spacing={1} sx={{ mt: 1.2 }}>
          {(deliveries.data ?? []).map(request => (
            <Stack
              key={request.id}
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1}

              sx={{
                p: 1.2,
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 2.5,
                alignItems: { sm: 'center' }
              }}
            >
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {request.kind} · {request.remote}:{request.target}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {statusLabel(request.status, locale)} · HEAD {shortId(request.headCommit)} ·{' '}
                  {request.sourceBranch}
                </Typography>
                {request.resultDetail && (
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                    {request.resultDetail}
                  </Typography>
                )}
              </Box>
              <Chip size="small" label={request.status} />
              {request.status === 'PENDING' && (
                <Stack direction="row" spacing={0.5}>
                  <Button
                    size="small"
                    onClick={() =>
                      run(
                        () => workspacesApi.decideDelivery(request.id, true),
                        t('git.approvedRecorded')
                      )
                    }
                  >
                    {t('git.approve')}
                  </Button>
                  <Button
                    size="small"
                    color="error"
                    onClick={() =>
                      run(
                        () => workspacesApi.decideDelivery(request.id, false),
                        t('git.rejectedRecorded')
                      )
                    }
                  >
                    {t('git.reject')}
                  </Button>
                </Stack>
              )}
              {request.status === 'APPROVED' && (
                <Button
                  size="small"
                  variant="contained"
                  loading={busy}
                  onClick={() =>
                    run(
                      () => workspacesApi.executeDelivery(request.id),
                      result =>
                        result.status === 'CONFIRMED'
                          ? t('git.deliveryConfirmed')
                          : result.status === 'CONFLICT'
                            ? t('git.deliveryConflict')
                            : t('git.deliveryUnknown')
                    )
                  }
                >
                  {t('git.execute')}
                </Button>
              )}
            </Stack>
          ))}
          {(deliveries.data ?? []).length === 0 && (
            <Typography variant="caption" color="text.secondary">
              {t('git.noDeliveries')}
            </Typography>
          )}
        </Stack>
      </Box>
    </Stack>
  );
}
