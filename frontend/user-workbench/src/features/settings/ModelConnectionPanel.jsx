import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Stack,
  Typography
} from '@mui/material';
import AddRounded from '@mui/icons-material/AddRounded';
import StarRounded from '@mui/icons-material/StarRounded';
import { modelConnectionsApi } from '../../api/modelConnections';
import { useModelConnections } from '../../hooks/useModelConnections';
import { googleTokens } from '../../theme/googleTheme';
import ModelConnectionDialog from './ModelConnectionDialog';

function statusChip(connection) {
  if (connection.lastTestStatus === 'OK')
    return <Chip size="small" color="success" label="已连通" />;
  if (connection.lastTestStatus === 'FAILED')
    return <Chip size="small" color="error" label="连接失败" />;
  return <Chip size="small" label="尚未测试" />;
}

/** 模型与 API Key（BYOK）主卡片：列表、默认项、连通性测试与编辑入口。 */
export default function ModelConnectionPanel() {
  const queryClient = useQueryClient();
  const query = useModelConnections();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [busyId, setBusyId] = useState(null);
  const [confirmDelete, setConfirmDelete] = useState(null);
  const [error, setError] = useState('');

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['model-connections'] });
  const connections = query.data?.connections ?? [];
  const builtIns = query.data?.builtIns ?? [];

  const act = async (id, action) => {
    setError('');
    setBusyId(id);
    try {
      await action();
      await refresh();
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <Box
      sx={{
        bgcolor: '#FFFFFF',
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 4,
        p: 2.5
      }}
    >
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1}
        sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between', mb: 0.5 }}
      >
        <Box>
          <Typography sx={{ fontSize: 16, fontWeight: 500 }}>模型与 API Key（BYOK）</Typography>
          <Typography variant="caption" color="text.secondary">
            使用你自己的服务商额度。密钥服务端加密存储，页面与网络响应只显示掩码。
          </Typography>
        </Box>
        <Button
          variant="contained"
          startIcon={<AddRounded />}
          onClick={() => {
            setEditing(null);
            setDialogOpen(true);
          }}
        >
          添加模型连接
        </Button>
      </Stack>

      {error && (
        <Alert severity="error" sx={{ mt: 1.5 }}>
          {error}
        </Alert>
      )}

      {query.isLoading ? (
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 2 }}>
          <CircularProgress size={16} />
          <Typography variant="body2" color="text.secondary">
            正在读取模型连接…
          </Typography>
        </Stack>
      ) : connections.length === 0 ? (
        <Alert severity="info" sx={{ mt: 1.5 }}>
          还没有自带 Key 的模型。添加后即可在首页任务里选择「我的模型」，真实调用会走你自己的额度；
          也可以继续使用内置模拟模型体验完整流程。
        </Alert>
      ) : (
        <Stack spacing={1.2} sx={{ mt: 1.5 }}>
          {connections.map(connection => (
            <Box
              key={connection.id}
              sx={{
                border: `1px solid ${connection.isDefault ? googleTokens.primarySoft : googleTokens.outline}`,
                bgcolor: connection.isDefault ? '#F8FBFF' : '#FFFFFF',
                borderRadius: 3,
                p: 1.5
              }}
            >
              <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={1}
                sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between' }}
              >
                <Stack
                  direction="row"
                  spacing={0.75}
                  useFlexGap
                  sx={{ flexWrap: 'wrap', alignItems: 'center' }}
                >
                  <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
                    {connection.displayName}
                  </Typography>
                  <Chip size="small" label={connection.providerLabel} />
                  {connection.isDefault && (
                    <Chip size="small" color="primary" icon={<StarRounded />} label="默认" />
                  )}
                  {!connection.enabled && <Chip size="small" label="已停用" />}
                  {statusChip(connection)}
                  <Typography variant="caption" color="text.secondary">
                    {connection.modelName} · {connection.keyMasked}
                  </Typography>
                </Stack>
                <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap' }}>
                  <Button
                    size="small"
                    disabled={busyId === connection.id}
                    onClick={() =>
                      act(connection.id, () => modelConnectionsApi.test(connection.id))
                    }
                  >
                    测试连通性
                  </Button>
                  {!connection.isDefault && (
                    <Button
                      size="small"
                      disabled={busyId === connection.id || !connection.enabled}
                      onClick={() =>
                        act(connection.id, () => modelConnectionsApi.setDefault(connection.id))
                      }
                    >
                      设为默认
                    </Button>
                  )}
                  <Button
                    size="small"
                    onClick={() => {
                      setEditing(connection);
                      setDialogOpen(true);
                    }}
                  >
                    编辑
                  </Button>
                  <Button
                    size="small"
                    color={confirmDelete === connection.id ? 'error' : 'inherit'}
                    disabled={busyId === connection.id}
                    onClick={() => {
                      if (confirmDelete === connection.id) {
                        setConfirmDelete(null);
                        act(connection.id, () => modelConnectionsApi.remove(connection.id));
                      } else {
                        setConfirmDelete(connection.id);
                      }
                    }}
                  >
                    {confirmDelete === connection.id ? '确认删除' : '删除'}
                  </Button>
                </Stack>
              </Stack>
              {connection.lastTestStatus === 'FAILED' && connection.lastTestMessage && (
                <Typography variant="caption" color="error" sx={{ display: 'block', mt: 0.75 }}>
                  最近失败原因：{connection.lastTestMessage}
                </Typography>
              )}
              {(connection.inputPricePer1m != null || connection.outputPricePer1m != null) && (
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ display: 'block', mt: 0.5 }}
                >
                  参考单价：输入 {connection.inputPricePer1m ?? '未设'} / 输出{' '}
                  {connection.outputPricePer1m ?? '未设'} 每百万 Token（{connection.currency}
                  ），仅用于本机估算。
                </Typography>
              )}
            </Box>
          ))}
        </Stack>
      )}

      <Divider sx={{ my: 2 }} />
      <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.5 }}>内置免 Key 项</Typography>
      <Stack spacing={0.75}>
        {builtIns.map(item => (
          <Stack
            key={item.id}
            direction="row"
            spacing={0.75}
            sx={{ alignItems: 'center', flexWrap: 'wrap' }}
          >
            <Chip size="small" label={item.providerLabel} />
            <Typography variant="body2">{item.displayName}</Typography>
            <Typography variant="caption" color="text.secondary">
              {item.modelName}
            </Typography>
            <Chip size="small" label="只读" />
          </Stack>
        ))}
      </Stack>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
        模拟模型用于教学与快速体验；本地密钥文件只作为兼容兜底，建议改为页面配置。
      </Typography>

      <ModelConnectionDialog
        open={dialogOpen}
        connection={editing}
        presets={query.data?.presets ?? []}
        onClose={() => setDialogOpen(false)}
        onSaved={() => {
          refresh();
        }}
      />
    </Box>
  );
}
