import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  Typography
} from '@mui/material';
import DescriptionOutlined from '@mui/icons-material/DescriptionOutlined';
import DownloadRounded from '@mui/icons-material/DownloadRounded';
import VisibilityOutlined from '@mui/icons-material/VisibilityOutlined';
import FolderOpenOutlined from '@mui/icons-material/FolderOpenOutlined';
import TaskAltRounded from '@mui/icons-material/TaskAltRounded';
import { Link as RouterLink } from 'react-router-dom';
import StatusChip from '../../components/StatusChip';
import EmptyState from '../../components/EmptyState';
import MarkdownView from '../../components/MarkdownView';
import { Readout, TraceSection } from '../../components/WorkbenchDialog';
import { useRunDelivery } from '../../hooks/useRunExecution';
import { runsApi, reportUrl } from '../../api/runs';
import RunIterationCard from './RunIterationCard';
import { useToast } from '../../components/ToastProvider';
import { checkStatusLabel, planRoleLabel, statusLabel } from '../../constants/labels';
import { oneLine, parseJson, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

function writtenFiles(detail) {
  const files = new Map();
  for (const tool of detail?.tools ?? []) {
    if (tool.STATUS !== 'COMPLETED') continue;
    if (!['WORKSPACE_WRITE', '修改授权文件'].includes(tool.TOOL_NAME)) continue;
    const versions = parseJson(tool.RESOURCE_VERSION, null);
    if (!versions || typeof versions !== 'object') continue;
    for (const [path, version] of Object.entries(versions)) {
      if (path && typeof version === 'string') {
        files.set(path, {
          path,
          sha256: version,
          toolCallId: tool.ID,
          currentMatches: true
        });
      }
    }
  }
  return [...files.values()];
}

function FileRow({ file, runId, onPreview }) {
  const { t } = useI18n();
  const toast = useToast();
  const [loading, setLoading] = useState(false);

  const load = async download => {
    setLoading(true);
    try {
      const payload = await runsApi.deliveryFile(runId, file.path);
      const matches = payload.currentMatches ?? payload.sha256 === file.sha256;
      if (!matches) {
        toast.warning(t('outputs.versionChanged'));
        return;
      }
      const text = payload.content ?? '';
      if (download) {
        const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = file.path.split('/').at(-1) || 'download.txt';
        anchor.click();
        setTimeout(() => URL.revokeObjectURL(url), 4000);
      } else {
        onPreview({ path: file.path, text });
      }
    } catch (error) {
      toast.error(error.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Stack
      direction={{ xs: 'column', sm: 'row' }}
      spacing={1.2}

      sx={{
        py: 1.25,
        borderBottom: `1px solid ${googleTokens.outline}`,
        alignItems: { xs: 'flex-start', sm: 'center' }
      }}
    >
      <Box
        sx={{
          width: 34,
          height: 34,
          borderRadius: 2,
          bgcolor: '#F1F3F4',
          color: '#5F6368',
          display: 'grid',
          placeItems: 'center',
          flexShrink: 0
        }}
      >
        <DescriptionOutlined sx={{ fontSize: 18 }} />
      </Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography
          sx={{
            fontSize: 13,
            fontWeight: 500,
            fontFamily: googleTokens.mono,
            overflowWrap: 'anywhere'
          }}
        >
          {file.path}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {file.nodeLabel ? `${t('outputs.producedBy', { node: file.nodeLabel })} · ` : ''}
          SHA-256 {file.sha256?.slice(0, 12)}…
        </Typography>
      </Box>
      <Chip
        size="small"
        icon={<TaskAltRounded sx={{ fontSize: '14px !important' }} />}
        label={file.currentMatches === false ? t('outputs.changed') : t('outputs.verified')}
        sx={{
          bgcolor: file.currentMatches === false ? '#FEF7E0' : '#E6F4EA',
          color: file.currentMatches === false ? '#B06000' : '#137333'
        }}
      />

      <Stack direction="row" spacing={0.5} sx={{ flexShrink: 0 }}>
        <Button
          size="small"
          startIcon={<VisibilityOutlined fontSize="small" />}
          onClick={() => load(false)}
          disabled={loading}
        >
          {t('outputs.preview')}
        </Button>
        <Button
          size="small"
          startIcon={
            loading ? <CircularProgress size={13} /> : <DownloadRounded fontSize="small" />
          }
          onClick={() => load(true)}
          disabled={loading}
        >
          {t('outputs.download')}
        </Button>
      </Stack>
    </Stack>
  );
}

/** 交付说明：结论、文件、核对情况与 Git 交付入口。 */
export default function RunOutputsPanel({
  detail,
  runId,
  workspaceId,
  taskId,
  roundIndex,
  onIterateStarted
}) {
  const { t, locale } = useI18n();
  const delivery = useRunDelivery(runId);
  const [preview, setPreview] = useState(null);
  const run = detail?.run;
  const task = detail?.task;
  const data = delivery.data;
  const files = data?.files?.length ? data.files : writtenFiles(detail);
  const checks = data?.checks ?? [];
  const writerNodes = (data?.nodes ?? []).filter(node => node.files?.length);
  const handoffNodes = (data?.nodes ?? []).filter(node => node.inbound?.length);
  const taskStatus = task?.TASK_STATUS || run?.STATUS || 'PENDING';
  const workspaceDelivered = Boolean(task?.WORKSPACE_DELIVERED);
  const passed = checks.filter(check => check.status === 'PASSED').length;
  const failed = checks.filter(check => ['FAILED', 'BLOCKED'].includes(check.status)).length;
  const rawUnverified = data?.unverified ?? [];
  const unverified = workspaceDelivered
    ? rawUnverified.filter(item => !String(item).includes('等待核对工作区差异'))
    : rawUnverified;

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Stack spacing={2} sx={{ maxWidth: 1180, mx: 'auto', px: { xs: 2, sm: 3 }, py: 2.5 }}>
        <Box
          sx={{
            bgcolor: '#F6F9FF',
            border: '1px solid #D2E3FC',
            borderRadius: 4,
            p: { xs: 2, sm: 2.5 }
          }}
        >
          <Stack
            direction="row"

            spacing={1.2}
            sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}
          >
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Typography sx={{ fontSize: 16, fontWeight: 500 }}>{t('outputs.title')}</Typography>
              <StatusChip value={taskStatus} />
              {run?.STATUS && taskStatus !== run.STATUS && (
                <Typography variant="caption" color="text.secondary">
                  {t('home.agentStatus', { status: statusLabel(run.STATUS, locale) })}
                </Typography>
              )}
            </Stack>
            <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap' }}>
              <Chip
                size="small"
                label={t('outputs.fileCount', { count: files.length })}
                sx={{ bgcolor: '#FFFFFF' }}
              />
              <Chip
                size="small"
                label={t('outputs.checkCounts', { passed, failed })}
                sx={{ bgcolor: '#FFFFFF' }}
              />

              {unverified.length > 0 && (
                <Chip
                  size="small"
                  label={t('outputs.unverifiedCount', { count: unverified.length })}
                  sx={{ bgcolor: '#FEF7E0', color: '#B06000' }}
                />
              )}
            </Stack>
          </Stack>
          {run?.REASON &&
            (workspaceDelivered ? (
              <Alert severity="success" sx={{ mt: 1.2 }}>
                {t('outputs.deliveredNotice', {
                  status: statusLabel(run?.STATUS, locale)
                })}
              </Alert>
            ) : (
              <Alert severity="warning" sx={{ mt: 1.2 }}>
                {run.REASON}
              </Alert>
            ))}
          <Box sx={{ mt: 1.5 }}>
            <MarkdownView
              maxHeight={320}
              text={
                run?.RESULT?.trim() ||
                (run?.STATUS ? t('outputs.noFinalOutput') : t('outputs.outputPending'))
              }
            />
          </Box>
        </Box>

        {unverified.length > 0 && (
          <Alert severity="warning">
            <Typography variant="subtitle2">{t('outputs.unverifiedTitle')}</Typography>
            {unverified.map(item => (
              <Typography key={item} variant="body2">
                · {item}
              </Typography>
            ))}
          </Alert>
        )}

        <RunIterationCard
          run={run}
          taskId={taskId ?? task?.ID}
          roundIndex={roundIndex}
          onStarted={onIterateStarted}
        />

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 1.45fr) minmax(0, 1fr)' },
            gap: 2,
            alignItems: 'start'
          }}
        >
          <Box
            sx={{
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`,
              borderRadius: 4,
              p: { xs: 2, sm: 2.5 }
            }}
          >
            <Typography sx={{ fontSize: 15, fontWeight: 500, mb: 0.5 }}>
              {t('outputs.filesTitle')}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {t('outputs.filesDescription')}
            </Typography>
            <Box sx={{ mt: 1 }}>
              {delivery.isLoading && (
                <Stack direction="row" spacing={1} sx={{ py: 2, alignItems: 'center' }}>
                  <CircularProgress size={16} />
                  <Typography variant="body2" color="text.secondary">
                    {t('outputs.loadingDelivery')}
                  </Typography>
                </Stack>
              )}
              {!delivery.isLoading && files.length === 0 && (
                <EmptyState
                  compact
                  icon={<DescriptionOutlined />}
                  title={t('outputs.noFilesTitle')}
                  description={t('outputs.noFilesDescription')}
                />
              )}
              {files.map(file => (
                <FileRow
                  key={`${file.path}:${file.sha256}`}
                  file={file}
                  runId={runId}
                  onPreview={setPreview}
                />
              ))}
            </Box>

            {writerNodes.length > 0 && (
              <>
                <Divider sx={{ my: 1.5 }} />
                <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.75 }}>
                  {t('outputs.byNode')}
                </Typography>
                {writerNodes.map(node => (
                  <Box key={node.nodeId} sx={{ mb: 1 }}>
                    <Typography variant="body2">
                      {node.label} · {planRoleLabel(node.role, locale)}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {t('outputs.writes', {
                        files: node.files
                          .map(file => file.path)
                          .join(locale === 'en-US' ? ', ' : '、')
                      })}
                    </Typography>
                    {node.missingRequiredFiles?.length > 0 && (
                      <Alert severity="warning" sx={{ mt: 0.5 }}>
                        {t('outputs.missingFiles', {
                          files: node.missingRequiredFiles.join(locale === 'en-US' ? ', ' : '、')
                        })}
                      </Alert>
                    )}
                  </Box>
                ))}
              </>
            )}

            <Divider sx={{ my: 1.5 }} />
            <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
              <Button
                size="small"
                startIcon={<FolderOpenOutlined />}
                component={RouterLink}
                to={`/workspaces${workspaceId ? `?id=${workspaceId}` : ''}`}
                disabled={!workspaceId}
              >
                {t('outputs.openWorkspace')}
              </Button>
              <Button size="small" startIcon={<DownloadRounded />} href={reportUrl(runId)}>
                {t('outputs.downloadReport')}
              </Button>
              <Button size="small" href={reportUrl(runId, 'SUMMARY')}>
                {t('outputs.summaryReport')}
              </Button>
              <Button size="small" href={reportUrl(runId, 'REDACTED')}>
                {t('outputs.redactedReport')}
              </Button>
            </Stack>
          </Box>

          <Stack spacing={2}>
            <Box
              sx={{
                bgcolor: '#FFFFFF',
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 4,
                p: { xs: 2, sm: 2.5 }
              }}
            >
              <Typography sx={{ fontSize: 15, fontWeight: 500, mb: 0.5 }}>
                {t('outputs.checksTitle')}
              </Typography>
              {checks.length === 0 && (
                <Typography variant="body2" color="text.secondary">
                  {t('outputs.noChecks')}
                </Typography>
              )}
              <Stack spacing={1.2} sx={{ mt: 0.5 }}>
                {checks.map(check => (
                  <Box key={check.checkId}>
                    <Stack
                      direction="row"
                      spacing={0.75}
                      sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                    >
                      <Typography variant="body2" sx={{ fontWeight: 500 }}>
                        {check.nodeLabel || check.checkId}
                      </Typography>
                      <Chip
                        size="small"
                        label={
                          check.evidenceCurrent === false
                            ? t('outputs.evidenceStale')
                            : checkStatusLabel(check.status, locale)
                        }
                        sx={{
                          bgcolor:
                            check.status === 'PASSED'
                              ? '#E6F4EA'
                              : check.evidenceCurrent === false
                                ? '#FEF7E0'
                                : '#FCE8E6',
                          color:
                            check.status === 'PASSED'
                              ? '#137333'
                              : check.evidenceCurrent === false
                                ? '#B06000'
                                : '#C5221F'
                        }}
                      />
                    </Stack>
                    {check.summary && (
                      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.3 }}>
                        {oneLine(check.summary, 220)}
                      </Typography>
                    )}
                    {check.evidence && (
                      <TraceSection title={t('outputs.checkEvidence')} dense>
                        <Readout maxHeight={150}>{check.evidence}</Readout>
                      </TraceSection>
                    )}
                  </Box>
                ))}
              </Stack>
            </Box>

            {handoffNodes.length > 0 && (
              <Box
                sx={{
                  bgcolor: '#FFFFFF',
                  border: `1px solid ${googleTokens.outline}`,
                  borderRadius: 4,
                  p: { xs: 2, sm: 2.5 }
                }}
              >
                <Typography sx={{ fontSize: 15, fontWeight: 500, mb: 0.75 }}>
                  {t('outputs.inboundHandoffs')}
                </Typography>
                {handoffNodes.map(node => (
                  <TraceSection key={node.nodeId} title={node.label}>
                    {node.inbound.map(handoff => (
                      <Typography
                        key={`${handoff.fromNodeId}:${handoff.attemptId}`}
                        variant="body2"
                        sx={{ overflowWrap: 'anywhere' }}
                      >
                        {handoff.fromLabel} → {handoff.toLabel}
                        {t('outputs.receiveAttempt', { id: shortId(handoff.attemptId) })}
                      </Typography>
                    ))}
                  </TraceSection>
                ))}
              </Box>
            )}

            <Box
              sx={{
                bgcolor: '#FFFFFF',
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 4,
                p: { xs: 2, sm: 2.5 }
              }}
            >
              <Typography sx={{ fontSize: 15, fontWeight: 500 }}>
                {t('outputs.locationTitle')}
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.4 }}>
                {task?.WORKSPACE_ID
                  ? t('outputs.locationWorkspace', { id: shortId(task.WORKSPACE_ID) })
                  : t('outputs.locationText')}
              </Typography>
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ display: 'block', mt: 0.75 }}
              >
                {t('outputs.runStatus', {
                  status: statusLabel(run?.STATUS, locale) || '—',
                  id: shortId(task?.ID)
                })}
              </Typography>
            </Box>
          </Stack>
        </Box>
      </Stack>

      <Dialog open={Boolean(preview)} onClose={() => setPreview(null)} maxWidth="md" fullWidth>
        <DialogTitle sx={{ fontFamily: googleTokens.mono, fontSize: 15 }}>
          {preview?.path}
        </DialogTitle>
        <DialogContent dividers>
          <Readout mono maxHeight={520}>
            {preview?.text || t('outputs.emptyFile')}
          </Readout>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPreview(null)}>{t('common.close')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
