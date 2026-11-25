import { useEffect, useState } from 'react';
import { Alert, Checkbox, FormControlLabel, Stack, TextField, Typography } from '@mui/material';
import { useQueryClient } from '@tanstack/react-query';
import WorkbenchDialog from '../../components/WorkbenchDialog';
import { useToast } from '../../components/ToastProvider';
import { workspacesApi } from '../../api/workspaces';
import { useAuth } from '../auth/AuthGate';
import { useI18n } from '../../i18n/I18nProvider';

/**
 * 创建工作区：仅用于可信本地仓库；工作区不是代码执行沙箱。
 *
 * <p>管理员可以选择「未授权时自动登记」而一步创建；普通账号仍需管理员先登记仓库导入授权，
 * 前端绝不向普通账号展示该选项。
 */
export default function NewWorkspaceDialog({ open, onClose, onCreated }) {
  const { t } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const auth = useAuth();
  const isAdmin = auth?.user?.role === 'ADMIN';
  const [repositoryPath, setRepositoryPath] = useState('');
  const [baseBranch, setBaseBranch] = useState('main');
  const [workBranch, setWorkBranch] = useState('');
  const [autoGrant, setAutoGrant] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    setAutoGrant(true);
    setError('');
  }, [open]);

  const submit = async () => {
    if (!repositoryPath.trim()) {
      setError(t('workspace.create.repositoryRequired'));
      return;
    }
    setBusy(true);
    setError('');
    try {
      const workspace = await workspacesApi.create({
        repositoryPath: repositoryPath.trim(),
        baseBranch: baseBranch.trim() || 'main',
        workBranch: workBranch.trim() || undefined,
        ...(isAdmin && autoGrant ? { autoGrantForAdmin: true } : {})
      });
      queryClient.invalidateQueries({ queryKey: ['workspaces'] });
      toast.success(t('workspace.create.created'));
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
      okText={t('workspace.create.ok')}
      confirmLoading={busy}
      width={620}
      title={t('workspace.create.title')}
      subtitle={t('workspace.create.subtitle')}
    >
      <Stack spacing={1.75} sx={{ pt: 0.5 }}>
        <Alert severity="warning">{t('workspace.create.warning')}</Alert>
        {error && <Alert severity="error">{error}</Alert>}
        <TextField
          label={t('workspace.create.repository')}
          value={repositoryPath}
          onChange={event => setRepositoryPath(event.target.value)}
          placeholder={t('workspace.create.repositoryPlaceholder')}
          size="small"
          autoFocus
        />
        <TextField
          label={t('workspace.create.baseBranch')}
          value={baseBranch}
          onChange={event => setBaseBranch(event.target.value)}
          placeholder={t('workspace.create.baseBranchPlaceholder')}
          size="small"
        />
        <TextField
          label={t('workspace.create.workBranch')}
          value={workBranch}
          onChange={event => setWorkBranch(event.target.value)}
          placeholder={t('workspace.create.workBranchPlaceholder')}
          size="small"
        />
        {isAdmin ? (
          <Stack spacing={0.25}>
            <FormControlLabel
              control={
                <Checkbox
                  size="small"
                  checked={autoGrant}
                  onChange={event => setAutoGrant(event.target.checked)}
                />
              }
              label={<Typography variant="body2">{t('workspace.create.autoGrant')}</Typography>}
            />
            <Typography variant="caption" color="text.secondary">
              {t('workspace.create.autoGrantHint')}
            </Typography>
          </Stack>
        ) : (
          <Typography variant="caption" color="text.secondary">
            {t('workspace.create.userHint')}
          </Typography>
        )}
      </Stack>
    </WorkbenchDialog>
  );
}
