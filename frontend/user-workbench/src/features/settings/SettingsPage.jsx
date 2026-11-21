import { useState } from 'react';
import { Alert, Box, Button, Chip, Divider, Stack, Typography } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthGate';
import { usePageChrome } from '../../layout/pageChrome';
import { evaluationApi } from '../../api/evaluations';
import CostPricePanel from '../workflows/CostPricePanel';
import { googleTokens } from '../../theme/googleTheme';

/** 设置：账号、环境边界、自检与价格版本。 */
export default function SettingsPage() {
  const { user, logout, loggingOut } = useAuth();
  const navigate = useNavigate();
  const [selfCheck, setSelfCheck] = useState(null);
  const [checking, setChecking] = useState(false);

  usePageChrome({ trail: [{ label: '工作空间' }, { label: '设置' }] });

  const runCheck = async () => {
    setChecking(true);
    try {
      setSelfCheck(await evaluationApi.selfCheck());
    } catch (error) {
      setSelfCheck({ status: 'ERROR', checks: [{ name: '请求失败', detail: error.message }] });
    } finally {
      setChecking(false);
    }
  };

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Box sx={{ maxWidth: 840, mx: 'auto', px: { xs: 2, sm: 3 }, py: { xs: 3, md: 4 } }}>
        <Typography component="h1" sx={{ fontSize: 22, fontWeight: 500, mb: 0.5 }}>
          设置
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          账号、环境边界与本机自检。密钥与运行时凭据不会在页面回显。
        </Typography>

        <Stack spacing={2}>
          <Box
            sx={{
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`,
              borderRadius: 4,
              p: 2.5
            }}
          >
            <Typography sx={{ fontSize: 15, fontWeight: 500, mb: 1 }}>当前账号</Typography>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <Typography variant="body2">{user?.displayName}</Typography>
              <Typography variant="caption" color="text.secondary">
                {user?.username}
              </Typography>
              <Chip size="small" label={user?.role === 'ADMIN' ? '管理员' : '普通用户'} />
              {user?.legacyClaimed && <Chip size="small" label="已领取旧 LOCAL 历史" />}
            </Stack>
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block', mt: 0.75 }}
            >
              身份来自服务端会话；页面不把请求体中的用户字段作为归属依据。
            </Typography>
            <Button
              variant="outlined"
              sx={{ mt: 1.5 }}
              disabled={loggingOut}
              onClick={() => {
                logout();
                navigate('/');
              }}
            >
              退出登录
            </Button>
          </Box>

          <Box
            sx={{
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`,
              borderRadius: 4,
              p: 2.5
            }}
          >
            <Typography sx={{ fontSize: 15, fontWeight: 500, mb: 1 }}>环境边界</Typography>
            <Stack spacing={0.6}>
              <Typography variant="body2">
                服务端：同源 <code>/api</code>，开发环境代理到 <code>127.0.0.1:8088</code>。
              </Typography>
              <Typography variant="body2">
                模型：模拟模型与 DeepSeek Flash 分开标示；选择真实模型可能产生费用。
              </Typography>
              <Typography variant="body2">
                工作区：仅可信本地仓库；工作区不是代码执行沙箱，写入需要逐任务授权。
              </Typography>
              <Typography variant="body2">
                评测：本地种子评测使用模拟模型与工具，不访问真实模型、仓库或外部服务。
              </Typography>
            </Stack>
            <Alert severity="info" sx={{ mt: 1.5 }}>
              模拟与真实能力在 UI
              中分别标注；任何“完成”都对应服务端持久化记录，不由前端预设动画生成。
            </Alert>
          </Box>

          <Box
            sx={{
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`,
              borderRadius: 4,
              p: 2.5
            }}
          >
            <Stack
              direction="row"

              sx={{ mb: 1, justifyContent: 'space-between', alignItems: 'center' }}
            >
              <Typography sx={{ fontSize: 15, fontWeight: 500 }}>系统自检</Typography>
              <Button variant="outlined" loading={checking} onClick={runCheck}>
                运行自检
              </Button>
            </Stack>
            {selfCheck ? (
              <Stack spacing={0.6}>
                <Typography variant="body2">总体：{selfCheck.status}</Typography>
                {(selfCheck.checks ?? []).map(check => (
                  <Typography key={check.name} variant="body2" color="text.secondary">
                    {check.status} · {check.name}
                    {check.detail ? `：${check.detail}` : ''}
                  </Typography>
                ))}
              </Stack>
            ) : (
              <Typography variant="body2" color="text.secondary">
                检查本地数据库、目录与必要配置；只读操作，不修改数据。
              </Typography>
            )}
          </Box>

          <Box
            sx={{
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`,
              borderRadius: 4,
              p: 2.5
            }}
          >
            <CostPricePanel />
            <Divider sx={{ my: 2 }} />
            <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.5 }}>关于前端重构</Typography>
            <Typography variant="caption" color="text.secondary">
              React 19 + Vite + MUI + React Flow + TanStack Query + React Router。全站使用 Google M3
              设计令牌，已移除 Ant Design 与手写压缩样式。
            </Typography>
          </Box>
        </Stack>
      </Box>
    </Box>
  );
}
