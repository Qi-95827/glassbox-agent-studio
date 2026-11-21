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
import { Readout, TraceSection } from '../../components/WorkbenchDialog';
import { useRunDelivery } from '../../hooks/useRunExecution';
import { runsApi, reportUrl } from '../../api/runs';
import { useToast } from '../../components/ToastProvider';
import { CHECK_STATUS_LABELS, RUN_STATES } from '../../constants/labels';
import { oneLine, parseJson, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';

const settledLabels = {
  COMPLETED: '运行已完成',
  BLOCKED: '运行待处理',
  CANCELLED: '运行已取消',
  FAILED: '运行失败',
  INTERRUPTED: '运行已中断',
  LIMITED: '运行已达限制'
};

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
  const toast = useToast();
  const [loading, setLoading] = useState(false);

  const load = async download => {
    setLoading(true);
    try {
      const payload = await runsApi.deliveryFile(runId, file.path);
      const matches = payload.currentMatches ?? payload.sha256 === file.sha256;
      if (!matches) {
        toast.warning('当前文件版本已变化，请从本次运行的调用证据查看原版本记录。');
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
          {file.nodeLabel ? `产出节点 ${file.nodeLabel} · ` : ''}
          SHA-256 {file.sha256?.slice(0, 12)}…
        </Typography>
      </Box>
      <Chip
        size="small"
        icon={<TaskAltRounded sx={{ fontSize: '14px !important' }} />}
        label={file.currentMatches === false ? '当前文件已变化' : '版本已核对'}
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
          预览
        </Button>
        <Button
          size="small"
          startIcon={
            loading ? <CircularProgress size={13} /> : <DownloadRounded fontSize="small" />
          }
          onClick={() => load(true)}
          disabled={loading}
        >
          下载
        </Button>
      </Stack>
    </Stack>
  );
}

/** 交付说明：结论、文件、核对情况与 Git 交付入口。 */
export default function RunOutputsPanel({ detail, runId, workspaceId }) {
  const delivery = useRunDelivery(runId);
  const [preview, setPreview] = useState(null);
  const run = detail?.run;
  const task = detail?.task;
  const data = delivery.data;
  const files = data?.files?.length ? data.files : writtenFiles(detail);
  const checks = data?.checks ?? [];
  const writerNodes = (data?.nodes ?? []).filter(node => node.files?.length);
  const handoffNodes = (data?.nodes ?? []).filter(node => node.inbound?.length);
  const status = settledLabels[run?.STATUS];
  const passed = checks.filter(check => check.status === 'PASSED').length;
  const failed = checks.filter(check => ['FAILED', 'BLOCKED'].includes(check.status)).length;
  const unverified = data?.unverified ?? [];

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
              <Typography sx={{ fontSize: 16, fontWeight: 500 }}>本次运行结论</Typography>
              <StatusChip value={run?.STATUS ?? 'PENDING'} />
            </Stack>
            <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap' }}>
              <Chip size="small" label={`产出文件 ${files.length}`} sx={{ bgcolor: '#FFFFFF' }} />
              <Chip
                size="small"
                label={`检查通过 ${passed} / 未通过 ${failed}`}
                sx={{ bgcolor: '#FFFFFF' }}
              />

              {unverified.length > 0 && (
                <Chip
                  size="small"
                  label={`未验证 ${unverified.length}`}
                  sx={{ bgcolor: '#FEF7E0', color: '#B06000' }}
                />
              )}
            </Stack>
          </Stack>
          {run?.REASON && (
            <Alert severity="warning" sx={{ mt: 1.2 }}>
              {run.REASON}
            </Alert>
          )}
          <Typography
            component="pre"
            sx={{
              mt: 1.5,
              mb: 0,
              mx: 0,
              fontFamily: 'inherit',
              fontSize: 14,
              lineHeight: 1.7,
              whiteSpace: 'pre-wrap',
              overflowWrap: 'anywhere',
              maxHeight: 260,
              overflowY: 'auto'
            }}
          >
            {run?.RESULT?.trim() ||
              (status ? '本次运行未留下最终输出。' : '最终输出会在运行结算后显示。')}
          </Typography>
        </Box>

        {unverified.length > 0 && (
          <Alert severity="warning">
            <Typography variant="subtitle2">尚未验证的事项</Typography>
            {unverified.map(item => (
              <Typography key={item} variant="body2">
                · {item}
              </Typography>
            ))}
          </Alert>
        )}

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
            <Typography sx={{ fontSize: 15, fontWeight: 500, mb: 0.5 }}>交付文件</Typography>
            <Typography variant="caption" color="text.secondary">
              只列出本次运行成功写入、且路径属于交付投影的文件；预览会核对当前摘要。
            </Typography>
            <Box sx={{ mt: 1 }}>
              {delivery.isLoading && (
                <Stack direction="row" spacing={1} sx={{ py: 2, alignItems: 'center' }}>
                  <CircularProgress size={16} />
                  <Typography variant="body2" color="text.secondary">
                    正在读取交付投影…
                  </Typography>
                </Stack>
              )}
              {!delivery.isLoading && files.length === 0 && (
                <EmptyState
                  compact
                  icon={<DescriptionOutlined />}
                  title="本次运行没有可交付文件"
                  description="分析或审查类运行通常只产出文本结论；写入授权关闭时也不会产生文件。"
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
                <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.75 }}>按节点汇总</Typography>
                {writerNodes.map(node => (
                  <Box key={node.nodeId} sx={{ mb: 1 }}>
                    <Typography variant="body2">
                      {node.label} ·{' '}
                      {node.role === 'CODER' ? '开发' : node.role === 'ANALYST' ? '分析' : '审查'}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      写入：{node.files.map(file => file.path).join('、')}
                    </Typography>
                    {node.missingRequiredFiles?.length > 0 && (
                      <Alert severity="warning" sx={{ mt: 0.5 }}>
                        缺少必需文件：{node.missingRequiredFiles.join('、')}
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
                查看工作区与 Git 交付
              </Button>
              <Button size="small" startIcon={<DownloadRounded />} href={reportUrl(runId)}>
                下载运行报告
              </Button>
              <Button size="small" href={reportUrl(runId, 'SUMMARY')}>
                精简报告
              </Button>
              <Button size="small" href={reportUrl(runId, 'REDACTED')}>
                脱敏报告
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
              <Typography sx={{ fontSize: 15, fontWeight: 500, mb: 0.5 }}>核对情况</Typography>
              {checks.length === 0 && (
                <Typography variant="body2" color="text.secondary">
                  本次运行没有保存独立的系统检查记录。
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
                            ? '证据已过期'
                            : CHECK_STATUS_LABELS[check.status] || check.status
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
                      <TraceSection title="检查证据" dense>
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
                  节点收到的交接
                </Typography>
                {handoffNodes.map(node => (
                  <TraceSection key={node.nodeId} title={node.label}>
                    {node.inbound.map(handoff => (
                      <Typography
                        key={`${handoff.fromNodeId}:${handoff.attemptId}`}
                        variant="body2"
                        sx={{ overflowWrap: 'anywhere' }}
                      >
                        {handoff.fromLabel} → {handoff.toLabel}（接收尝试{' '}
                        {shortId(handoff.attemptId)}…）
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
              <Typography sx={{ fontSize: 15, fontWeight: 500 }}>交付位置</Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.4 }}>
                {task?.WORKSPACE_ID
                  ? `本运行关联工作区 ${shortId(task.WORKSPACE_ID)}；文件正文仍走工作区读取接口并核对摘要。`
                  : '该任务没有关联代码工作区，交付以文本结论为主。'}
              </Typography>
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ display: 'block', mt: 0.75 }}
              >
                运行状态：{RUN_STATES[run?.STATUS] || run?.STATUS || '—'} · 任务 #
                {shortId(task?.ID)}
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
            {preview?.text || '空文件'}
          </Readout>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPreview(null)}>关闭</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
