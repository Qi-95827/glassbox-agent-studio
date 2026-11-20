import React, { useEffect, useState } from 'react';
import { Alert, Box, Button, Chip, Divider, LinearProgress, Stack, Typography } from '@mui/material';
import AccountTreeOutlined from '@mui/icons-material/AccountTreeOutlined';
import FolderOpenOutlined from '@mui/icons-material/FolderOpenOutlined';
import { TraceSection } from '../workflows/RunTraceInspector.jsx';

const activityNames = { LOCAL_COMMIT: '本地提交', PUSH: '推送', MERGE: '合并' };
const activityStates = {
  PENDING: ['待批准', 'warning'],
  APPROVED: ['已批准', 'info'],
  STARTED: ['进行中', 'info'],
  CONFIRMED: ['已确认', 'success'],
  REJECTED: ['已拒绝', 'default'],
  CONFLICT: ['冲突，需核对', 'warning'],
  UNKNOWN: ['结果未知，需核对', 'warning']
};

function GitActivity({ activity }) {
  const [label, color] = activityStates[activity.status] ?? ['状态未记录', 'default'];
  const commit = activity.commitId || activity.headCommit;
  return <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 0.75, alignItems: { xs: 'flex-start', sm: 'center' } }}>
    <Typography variant="body2" sx={{ fontWeight: 600 }}>{activityNames[activity.kind]}</Typography>
    {activity.target && <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>
      {activity.kind === 'PUSH' && activity.remote ? `${activity.remote}/` : ''}{activity.target}
    </Typography>}
    {commit && <Typography variant="caption" component="code" title={commit}>{commit.slice(0, 12)}</Typography>}
    <Chip size="small" variant="outlined" label={label} color={color} />
  </Stack>;
}

/** 此处显示工作区级 Git 历史，同一个工作区可以被多个运行使用。 */
export default function WorkspaceDeliverySummary({ workspaceId, api, workspaceOpen, onOpenWorkspace }) {
  const [context, setContext] = useState(null);

  useEffect(() => {
    if (workspaceOpen) return undefined;
    let active = true;
    Promise.allSettled([
      api('/workspaces'),
      api(`/workspaces/${workspaceId}/commits`),
      api(`/workspaces/${workspaceId}/deliveries`)
    ]).then(([workspaces, commits, deliveries]) => {
      if (!active) return;
      setContext({
        workspace: workspaces.status === 'fulfilled' ? workspaces.value.find(row => row.id === workspaceId) : null,
        commit: commits.status === 'fulfilled' ? commits.value.find(row => row.kind === 'LOCAL_COMMIT') : null,
        push: deliveries.status === 'fulfilled' ? deliveries.value.find(row => row.kind === 'PUSH') : null,
        merge: deliveries.status === 'fulfilled' ? deliveries.value.find(row => row.kind === 'MERGE') : null,
        historyUnavailable: commits.status === 'rejected' || deliveries.status === 'rejected'
      });
    });
    return () => { active = false; };
  }, [api, workspaceId, workspaceOpen]);

  if (!context) return <LinearProgress aria-label="正在读取 Git 交付记录" />;
  const { workspace, historyUnavailable } = context;
  const history = [context.commit, context.push, context.merge].filter(Boolean);

  return <Box>
    <Stack direction="row" sx={{ gap: 1.1, alignItems: 'baseline', flexWrap: 'wrap', mb: 0.6 }}>
      <Typography component="h4" variant="subtitle2">Git 工作区</Typography>
      {workspace?.branchName && <Chip size="small" color="primary" variant="outlined"
        icon={<AccountTreeOutlined />} label={workspace.branchName}
        sx={{ maxWidth: '100%', '& .MuiChip-label': { overflow: 'hidden', textOverflow: 'ellipsis' } }} />}
    </Stack>
    {workspace?.workspacePath ? <Typography variant="body2" component="code" sx={{ overflowWrap: 'anywhere' }}>
      {workspace.workspacePath}
    </Typography> : <Typography variant="body2" color="text.secondary">无法读取工作区位置。</Typography>}
    <Box sx={{ mt: 1 }}>
      <Button size="small" variant="outlined" startIcon={<FolderOpenOutlined />}
        onClick={() => onOpenWorkspace(workspaceId)}>查看当前 Git 差异</Button>
    </Box>
    <Divider sx={{ mt: 1.6 }} />
    <TraceSection title="工作区 Git 记录" subtitle="查看各类动作的最近一条记录">
      <Stack spacing={1.1}>
        <Typography variant="caption" color="text.secondary">这些记录属于整个工作区，可能来自其他运行。</Typography>
        {historyUnavailable && <Alert severity="warning">部分 Git 记录暂时无法读取。</Alert>}
        {!history.length && !historyUnavailable && <Typography variant="body2" color="text.secondary">
          工作区尚无通过工作台发起的提交、推送或合并记录。
        </Typography>}
        {history.map(activity => <GitActivity key={activity.id} activity={activity} />)}
      </Stack>
    </TraceSection>
  </Box>;
}
