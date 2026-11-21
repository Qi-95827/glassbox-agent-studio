import { useState } from 'react';
import { Alert, Box, Button, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { authApi } from '../../api/auth';
import { workspacesApi } from '../../api/workspaces';
import { useToast } from '../../components/ToastProvider';
import { useAuth } from '../auth/AuthGate';
import { googleTokens } from '../../theme/googleTheme';

/** 管理员账号与仓库导入授权。 */
export default function AccountsPanel() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const [form, setForm] = useState({ username: '', password: '', displayName: '', role: 'USER' });
  const [grant, setGrant] = useState({ username: '', repositoryPath: '' });

  const users = useQuery({ queryKey: ['auth-users'], queryFn: authApi.users });
  const grants = useQuery({
    queryKey: ['repository-grants'],
    queryFn: workspacesApi.repositoryGrants
  });

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['auth-users'] });
    queryClient.invalidateQueries({ queryKey: ['repository-grants'] });
    queryClient.invalidateQueries({ queryKey: ['auth', 'status'] });
  };

  const addUser = useMutation({
    mutationFn: authApi.addUser,
    onSuccess: () => {
      toast.success('账号已添加');
      setForm({ username: '', password: '', displayName: '', role: 'USER' });
      refresh();
    },
    onError: error => toast.error(error.message)
  });

  const claim = useMutation({
    mutationFn: authApi.claimLegacy,
    onSuccess: () => {
      toast.success('旧 LOCAL 历史数据已归入当前管理员');
      refresh();
    },
    onError: error => toast.error(error.message)
  });

  const createGrant = useMutation({
    mutationFn: workspacesApi.grantRepository,
    onSuccess: () => {
      toast.success('仓库授权已登记');
      setGrant({ username: '', repositoryPath: '' });
      refresh();
    },
    onError: error => toast.error(error.message)
  });

  const revokeGrant = useMutation({
    mutationFn: workspacesApi.revokeRepository,
    onSuccess: () => {
      toast.success('导入授权已撤销；已有工作区仍保留访问');
      refresh();
    },
    onError: error => toast.error(error.message)
  });

  return (
    <Stack spacing={2}>
      <Alert severity="info">
        普通用户只能管理自己的资源。管理员添加账号后，用户使用该账号登录；密码不会回显。
      </Alert>

      {!user?.legacyClaimed && (
        <Button
          variant="outlined"
          loading={claim.isPending}
          onClick={() => claim.mutate()}
          sx={{ alignSelf: 'flex-start' }}
        >
          领取旧 LOCAL 历史数据
        </Button>
      )}

      <Box
        sx={{
          bgcolor: '#FFFFFF',
          border: `1px solid ${googleTokens.outline}`,
          borderRadius: 4,
          p: 2.5
        }}
      >
        <Typography sx={{ fontSize: 15, fontWeight: 500, mb: 1.5 }}>添加账号</Typography>
        <Stack
          direction={{ xs: 'column', md: 'row' }}
          spacing={1.25}
          useFlexGap
          sx={{ flexWrap: 'wrap' }}
        >
          <TextField
            size="small"
            label="账号"
            value={form.username}
            onChange={event => setForm({ ...form, username: event.target.value })}
            sx={{ minWidth: 180 }}
          />

          <TextField
            size="small"
            label="显示名称"
            value={form.displayName}
            onChange={event => setForm({ ...form, displayName: event.target.value })}
            sx={{ minWidth: 180 }}
          />

          <TextField
            size="small"
            type="password"
            label="初始密码（至少 8 位）"
            value={form.password}
            onChange={event => setForm({ ...form, password: event.target.value })}
            sx={{ minWidth: 220 }}
          />

          <TextField
            select
            size="small"
            label="角色"
            value={form.role}
            onChange={event => setForm({ ...form, role: event.target.value })}
            sx={{ width: 130 }}
          >
            <MenuItem value="USER">普通用户</MenuItem>
            <MenuItem value="ADMIN">管理员</MenuItem>
          </TextField>
          <Button
            variant="contained"
            loading={addUser.isPending}
            onClick={() => addUser.mutate(form)}
          >
            添加用户
          </Button>
        </Stack>
        <Stack spacing={0.5} sx={{ mt: 1.5 }}>
          {(users.data ?? []).map(row => (
            <Typography key={row.id} variant="body2">
              {row.displayName}（{row.username}） · {row.role} ·{' '}
              {row.legacyClaimed ? '已领取旧历史' : '未领取旧历史'}
            </Typography>
          ))}
        </Stack>
      </Box>

      <Box
        sx={{
          bgcolor: '#FFFFFF',
          border: `1px solid ${googleTokens.outline}`,
          borderRadius: 4,
          p: 2.5
        }}
      >
        <Typography sx={{ fontSize: 15, fontWeight: 500 }}>仓库导入授权</Typography>
        <Typography variant="caption" color="text.secondary">
          普通账号导入本地仓库前必须由管理员授权规范化仓库根目录；撤销的是导入授权，已有工作区仍保留访问。
        </Typography>
        <Stack
          direction={{ xs: 'column', md: 'row' }}
          spacing={1.25}
          sx={{ mt: 1.5, flexWrap: 'wrap' }}

          useFlexGap
        >
          <TextField
            select
            size="small"
            label="账号"
            value={grant.username}
            onChange={event => setGrant({ ...grant, username: event.target.value })}
            sx={{ minWidth: 180 }}
          >
            {(users.data ?? []).map(row => (
              <MenuItem key={row.id} value={row.username}>
                {row.displayName}（{row.role}）
              </MenuItem>
            ))}
          </TextField>
          <TextField
            size="small"
            label="仓库根目录"
            value={grant.repositoryPath}
            onChange={event => setGrant({ ...grant, repositoryPath: event.target.value })}
            placeholder="例如 D:\\projects\\app"
            sx={{ flex: 1, minWidth: 260 }}
          />

          <Button
            variant="outlined"
            loading={createGrant.isPending}
            disabled={!grant.username || !grant.repositoryPath.trim()}
            onClick={() => createGrant.mutate(grant)}
          >
            授权仓库
          </Button>
        </Stack>
        <Stack spacing={1} sx={{ mt: 1.5 }}>
          {(grants.data ?? []).map(row => (
            <Stack
              key={row.id}
              direction="row"
              spacing={1}
              sx={{ alignItems: 'center', flexWrap: 'wrap' }}
            >
              <Typography variant="body2">
                {row.username} · {row.repositoryRoot} · 授权人 {row.grantedBy}
              </Typography>
              <Button size="small" color="error" onClick={() => revokeGrant.mutate(row.id)}>
                撤销导入授权
              </Button>
            </Stack>
          ))}
        </Stack>
      </Box>
    </Stack>
  );
}
