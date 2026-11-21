import { useEffect, useState } from 'react';
import { Alert, Box, Button, MenuItem, Stack, TextField, Typography } from '@mui/material';
import BugReportOutlined from '@mui/icons-material/BugReportOutlined';
import PlayCircleOutlineRounded from '@mui/icons-material/PlayCircleOutlineRounded';
import { useQuery } from '@tanstack/react-query';
import { bugsApi } from '../../api/bugs';
import { useWorkspaces } from '../../hooks/useWorkspaces';
import WorkbenchDialog, { Readout } from '../../components/WorkbenchDialog';
import { useToast } from '../../components/ToastProvider';

/** 模拟 Bug 验证：容器内只读执行，测试失败不自动证明复现了特定 Bug。 */
export default function BugVerifyDialog({ open, onClose, onCreateTask, initialWorkspaceId }) {
  const toast = useToast();
  const workspaces = useWorkspaces();
  const bugs = useQuery({ queryKey: ['bugs'], queryFn: bugsApi.list, enabled: open });
  const [workspaceId, setWorkspaceId] = useState(initialWorkspaceId || '');
  const [bugId, setBugId] = useState('MOCK-PY-001');
  const [feedback, setFeedback] = useState([]);
  const [result, setResult] = useState(null);
  const [running, setRunning] = useState(false);

  useEffect(() => {
    if (open && initialWorkspaceId) setWorkspaceId(initialWorkspaceId);
  }, [open, initialWorkspaceId]);

  const bug = (bugs.data ?? []).find(item => item.id === bugId) ?? bugs.data?.[0] ?? null;
  const workspace = (workspaces.data ?? []).find(item => item.id === workspaceId) ?? null;

  const loadFeedback = async id => {
    if (!id) return;
    try {
      setFeedback(await bugsApi.feedback(id));
    } catch {
      setFeedback([]);
    }
  };

  const check = async kind => {
    if (!workspaceId) {
      toast.warning('请先选择工作区');
      return;
    }
    setRunning(true);
    try {
      setResult(await bugsApi.runCheck(workspaceId, kind));
      toast.success(kind === 'REPRODUCE' ? '已完成失败观察' : '已完成回归检查');
    } catch (error) {
      toast.error(error.message);
    } finally {
      setRunning(false);
    }
  };

  const sendFeedback = async status => {
    if (!workspaceId) {
      toast.warning('请先选择工作区');
      return;
    }
    try {
      await bugsApi.sendFeedback(workspaceId, status);
      await loadFeedback(workspaceId);
      toast.success('已记录模拟反馈');
    } catch (error) {
      toast.error(error.message);
    }
  };

  return (
    <WorkbenchDialog
      open={open}
      onCancel={onClose}
      footer={null}
      width={760}
      title="模拟 Bug 验证"
      subtitle={bug ? `${bug.id} · 验证工作区中的 Python unittest 结果` : '读取模拟 Bug 定义'}
    >
      <Stack spacing={1.8}>
        <Alert severity="warning">
          测试在无网络、只读挂载的容器中运行。测试失败只说明观察到失败，不自动证明复现了特定 Bug。
        </Alert>
        {bug && (
          <Box
            sx={{
              p: 1.75,
              borderRadius: 3,
              bgcolor: '#FBFCFD',
              border: '1px solid',
              borderColor: 'divider'
            }}
          >
            <Stack direction="row" spacing={0.75} sx={{ mb: 0.5, alignItems: 'center' }}>
              <BugReportOutlined sx={{ fontSize: 17, color: '#B06000' }} />
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                {bug.title}
              </Typography>
            </Stack>
            <Typography variant="body2" color="text.secondary">
              {bug.description}
            </Typography>
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block', mt: 0.75 }}
            >
              预期失败测试：{bug.expectedFailingTest}；预期失败信息：{bug.expectedFailureText}
            </Typography>
          </Box>
        )}
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25}>
          <TextField
            select
            size="small"
            label="Bug"
            value={bugId}
            onChange={event => setBugId(event.target.value)}
            sx={{ minWidth: 200 }}
          >
            {(bugs.data ?? []).map(item => (
              <MenuItem key={item.id} value={item.id}>
                {item.id} · {item.title}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            size="small"
            label="工作区"
            value={workspaceId}
            onChange={event => {
              setWorkspaceId(event.target.value);
              setResult(null);
              loadFeedback(event.target.value);
            }}
            sx={{ flex: 1, minWidth: 240 }}
          >
            {(workspaces.data ?? []).map(item => (
              <MenuItem key={item.id} value={item.id}>
                {item.branchName} · {item.repositoryPath}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
          <Button
            variant="outlined"
            startIcon={<PlayCircleOutlineRounded />}
            loading={running}
            onClick={() => check('REPRODUCE')}
          >
            观察失败
          </Button>
          <Button
            variant="outlined"
            startIcon={<PlayCircleOutlineRounded />}
            loading={running}
            onClick={() => check('REGRESSION')}
          >
            运行回归检查
          </Button>
        </Stack>
        {result && (
          <Box>
            <Typography variant="body2" sx={{ mb: 0.5 }}>
              结果：{result.status}；耗时：{result.durationMs} 毫秒
            </Typography>
            <Readout maxHeight={180}>{result.output}</Readout>
            <Typography variant="caption" color="text.secondary">
              测试结果对应执行时的文件状态，修改代码后需要重新运行。
            </Typography>
          </Box>
        )}
        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
          <Button size="small" variant="outlined" onClick={() => sendFeedback('WAITING_REVIEW')}>
            待审查
          </Button>
          <Button size="small" variant="outlined" onClick={() => sendFeedback('REJECTED')}>
            拒绝
          </Button>
          <Button
            size="small"
            variant="outlined"
            onClick={() => sendFeedback('USER_CONFIRMED_CLOSED')}
          >
            用户确认关闭
          </Button>
          {feedback.map(item => (
            <Typography key={item.ID} variant="caption" color="text.secondary">
              {item.STATUS} · {item.CREATED_AT}
            </Typography>
          ))}
        </Stack>
        <Alert severity="info">
          从该 Bug
          创建任务时，写入授权仍需在任务表单中重新勾选；关闭需要当前版本回归通过与用户审查。
        </Alert>
        <Button
          variant="contained"
          disabled={!workspace}
          onClick={() => {
            onCreateTask?.({
              workspaceId,
              bugId,
              goal: '修复模拟 Bug：先由固定容器复现失败，再定位并只修改授权文件，回归通过后由独立审查汇总；不要声称未验证的结果。'
            });
            onClose();
          }}
          sx={{ alignSelf: 'flex-start' }}
        >
          基于此 Bug 创建 Agent 任务
        </Button>
      </Stack>
    </WorkbenchDialog>
  );
}
