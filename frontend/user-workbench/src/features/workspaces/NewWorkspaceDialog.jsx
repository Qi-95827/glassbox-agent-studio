import { useState } from 'react';
import { Alert, Stack, TextField } from '@mui/material';
import { useQueryClient } from '@tanstack/react-query';
import WorkbenchDialog from '../../components/WorkbenchDialog';
import { useToast } from '../../components/ToastProvider';
import { workspacesApi } from '../../api/workspaces';

/** 创建工作区：仅用于可信本地仓库；工作区不是代码执行沙箱。 */
export default function NewWorkspaceDialog({ open, onClose, onCreated }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [repositoryPath, setRepositoryPath] = useState('');
  const [baseBranch, setBaseBranch] = useState('main');
  const [workBranch, setWorkBranch] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async () => {
    if (!repositoryPath.trim()) {
      setError('请填写本地仓库目录。');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const workspace = await workspacesApi.create({
        repositoryPath: repositoryPath.trim(),
        baseBranch: baseBranch.trim() || 'main',
        workBranch: workBranch.trim() || undefined
      });
      queryClient.invalidateQueries({ queryKey: ['workspaces'] });
      toast.success('独立工作区已创建');
      onCreated?.(workspace);
      setRepositoryPath('');
      setWorkBranch('');
      onClose();
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <WorkbenchDialog
      open={open}
      onCancel={onClose}
      onOk={submit}
      okText="创建独立分支和工作区"
      confirmLoading={busy}
      width={620}
      title="新建代码工作区"
      subtitle="从本地仓库创建独立分支，Agent 只能在授权文件范围内读写。"
    >
      <Stack spacing={1.75} sx={{ pt: 0.5 }}>
        <Alert severity="warning">
          仅用于可信本地仓库。工作区不是代码执行沙箱；创建分支前请检查仓库状态。
        </Alert>
        {error && <Alert severity="error">{error}</Alert>}
        <TextField
          label="本地仓库目录"
          value={repositoryPath}
          onChange={event => setRepositoryPath(event.target.value)}
          placeholder="例如 D:\\projects\\my-repo"
          size="small"
          autoFocus
        />
        <TextField
          label="基础分支（切出基准）"
          value={baseBranch}
          onChange={event => setBaseBranch(event.target.value)}
          placeholder="例如 main 或 dev"
          size="small"
        />
        <TextField
          label="自定义开发分支名（可选）"
          value={workBranch}
          onChange={event => setWorkBranch(event.target.value)}
          placeholder="留空自动生成 agent/work-xxxx，例如 feat/cyber-muyu"
          size="small"
        />
      </Stack>
    </WorkbenchDialog>
  );
}
