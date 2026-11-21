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
  Typography
} from '@mui/material';
import { useQueryClient } from '@tanstack/react-query';
import {
  useWorkspaceApprovals,
  useWorkspaceCommits,
  useWorkspaceDeliveries,
  useWorkspaceUntracked
} from '../../hooks/useWorkspaces';
import { workspacesApi } from '../../api/workspaces';
import StatusChip from '../../components/StatusChip';
import { useToast } from '../../components/ToastProvider';
import DeliveryStepper from './DeliveryStepper';
import { dateTime, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';

const deliveryStatusNames = {
  PENDING: '待批准',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  STARTED: '执行中',
  CONFIRMED: '已确认',
  CONFLICT: '冲突，需核对',
  UNKNOWN: '结果未知，需核对'
};

/** Git 与交付：审查决定、本地提交、未跟踪文件复核、推送/合并审批。 */
export default function WorkspaceGitPanel({ workspace }) {
  const toast = useToast();
  const queryClient = useQueryClient();
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

  return (
    <Stack spacing={2.5}>
      <Alert severity="warning">
        工作区不是沙箱。提交只包含已批准的全部已跟踪差异；推送与合并绑定当前 HEAD
        与目标分支，冲突或未知结果不会自动重试。
      </Alert>

      <DeliveryStepper workspaceId={workspace.id} />

      <Box>
        <Stack
          direction="row"
          sx={{ mb: 1, justifyContent: 'space-between', alignItems: 'center' }}
        >
          <Typography sx={{ fontSize: 14, fontWeight: 600 }}>人工审查</Typography>
          <Button
            size="small"
            variant="outlined"
            loading={busy}
            onClick={() => run(() => workspacesApi.proposeApproval(workspace.id), '审查已创建')}
          >
            发起审查
          </Button>
        </Stack>
        <Typography variant="caption" color="text.secondary">
          仅记录当前差异的审查结果，不会提交、推送或合并；未跟踪文件尚不支持审批。
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
              <StatusChip value={row.STATUS} />
              <Typography variant="body2" sx={{ flex: 1, fontFamily: googleTokens.mono }}>
                #{shortId(row.ID)} · {dateTime(row.UPDATED_AT || row.CREATED_AT)}
              </Typography>
              {row.STATUS === 'PENDING' && (
                <Stack direction="row" spacing={0.5}>
                  <Button
                    size="small"
                    variant="outlined"
                    onClick={() =>
                      run(() => workspacesApi.decideApproval(row.ID, 'APPROVE'), '已记录批准决定')
                    }
                  >
                    批准
                  </Button>
                  <Button
                    size="small"
                    color="error"
                    onClick={() =>
                      run(() => workspacesApi.decideApproval(row.ID, 'REJECT'), '已记录拒绝决定')
                    }
                  >
                    拒绝
                  </Button>
                </Stack>
              )}
            </Stack>
          ))}
          {(approvals.data ?? []).length === 0 && (
            <Typography variant="body2" color="text.secondary">
              还没有审查记录。
            </Typography>
          )}
        </Stack>
      </Box>

      <Divider />

      <Box>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>创建本地提交</Typography>
        <Typography variant="caption" color="text.secondary">
          只提交已批准的全部已跟踪差异，不会推送或合并；状态变化会拒绝提交。
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25} sx={{ mt: 1.2 }}>
          <TextField
            select
            size="small"
            label="选择有效批准"
            value={approvalId}
            onChange={event => setApprovalId(event.target.value)}
            sx={{ minWidth: 260 }}
          >
            {approved.map(row => (
              <MenuItem key={row.ID} value={row.ID}>
                批准 #{shortId(row.ID)} · {dateTime(row.UPDATED_AT)}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            size="small"
            label="提交说明"
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
              }, '本地提交已创建')
            }
          >
            创建本地提交
          </Button>
        </Stack>
        <Stack spacing={0.5} sx={{ mt: 1.2 }}>
          {(commits.data ?? []).map(row => (
            <Typography key={row.id} variant="caption" color="text.secondary">
              {row.kind || 'LOCAL_COMMIT'} · {row.status || row.STATUS} ·{' '}
              {(row.commitId || row.ID)?.slice(0, 8)} · {dateTime(row.createdAt || row.CREATED_AT)}
            </Typography>
          ))}
          {(commits.data ?? []).length === 0 && (
            <Typography variant="caption" color="text.secondary">
              暂无提交记录。
            </Typography>
          )}
        </Stack>
      </Box>

      <Divider />

      <Box>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>未跟踪文件</Typography>
        <Typography variant="caption" color="text.secondary">
          新建文本文件或复核现有未跟踪文件；复核结果绑定内容摘要。
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1 }}>
          <TextField
            size="small"
            label="新文件相对路径"
            value={untrackedPath}
            onChange={event => setUntrackedPath(event.target.value)}
            placeholder="例如 notes/verification.txt"
            sx={{ minWidth: 240 }}
          />

          <TextField
            size="small"
            label="文件内容"
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
              }, '新文件已创建并登记')
            }
          >
            创建并登记
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
                  {file.size} 字节 · {file.sha256?.slice(0, 12)} ·{' '}
                  {file.declared ? `已复核（${file.declarationSource || '已声明'}）` : '未复核'}
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
                      '已复核未跟踪文件'
                    )
                  }
                >
                  复核
                </Button>
              )}
            </Stack>
          ))}
          {(untracked.data ?? []).length === 0 && (
            <Typography variant="caption" color="text.secondary">
              没有未跟踪文件。
            </Typography>
          )}
        </Stack>
      </Box>

      <Divider />

      <Box>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>推送或合并</Typography>
        <Typography variant="caption" color="text.secondary">
          先准备请求，再批准并执行；执行结果冲突或未知时保留记录供人工核对。
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1, flexWrap: 'wrap' }}>
          <TextField
            size="small"
            label="远端"
            value={remote}
            onChange={event => setRemote(event.target.value)}
            sx={{ width: 130 }}
          />

          <TextField
            size="small"
            label="目标分支"
            value={target}
            onChange={event => setTarget(event.target.value)}
            sx={{ width: 170 }}
          />

          <TextField
            size="small"
            label="说明（可选）"
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
                '已准备推送，请核对并批准'
              )
            }
          >
            准备推送
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
                '已准备合并，请核对并批准'
              )
            }
          >
            准备合并
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
                  {deliveryStatusNames[request.status] || request.status} · HEAD{' '}
                  {shortId(request.headCommit)} · {request.sourceBranch}
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
                      run(() => workspacesApi.decideDelivery(request.id, true), '已批准')
                    }
                  >
                    批准
                  </Button>
                  <Button
                    size="small"
                    color="error"
                    onClick={() =>
                      run(() => workspacesApi.decideDelivery(request.id, false), '已拒绝')
                    }
                  >
                    拒绝
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
                          ? '交付已确认'
                          : result.status === 'CONFLICT'
                            ? '存在冲突或远端拒绝，请核对'
                            : '结果未知，请核对远端或工作区'
                    )
                  }
                >
                  执行
                </Button>
              )}
            </Stack>
          ))}
          {(deliveries.data ?? []).length === 0 && (
            <Typography variant="caption" color="text.secondary">
              还没有推送或合并记录。
            </Typography>
          )}
        </Stack>
      </Box>
    </Stack>
  );
}
