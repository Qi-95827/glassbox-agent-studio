import { createContext, useContext, useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  CircularProgress,
  FormControlLabel,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import { useSession } from '../../hooks/useTasks';
import { authApi } from '../../api/auth';
import { setUnauthorizedHandler, get } from '../../api/client';
import AppLogo from '../../components/AppLogo';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

const AuthContext = createContext(null);

export function useAuth() {
  return useContext(AuthContext);
}

function Splash({ message }) {
  return (
    <Stack
      spacing={2}
      sx={{
        minHeight: '100vh',
        bgcolor: 'background.default',
        alignItems: 'center',
        justifyContent: 'center'
      }}
    >
      <AppLogo size={40} />
      <CircularProgress size={22} />
      <Typography variant="body2" color="text.secondary">
        {message}
      </Typography>
    </Stack>
  );
}

/**
 * 本地账号入口：登录态只来自服务端会话。
 *
 * <p>初始化完成前保留旧单机模式；初始化后 401 会清空本地登录态并回到登录页。
 */
export default function AuthGate({ children }) {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const session = useSession();
  const [error, setError] = useState('');
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [claim, setClaim] = useState(true);

  const status = session.data;
  const user = status?.currentUser ?? null;

  useEffect(() => {
    setUnauthorizedHandler(() => {
      queryClient.setQueryData(['auth', 'status'], previous => ({
        initialized: previous?.initialized ?? true,
        currentUser: null
      }));
    });
    return () => setUnauthorizedHandler(null);
  }, [queryClient]);

  const finish = async () => {
    setPassword('');
    setError('');
    // 登录会更换 Session ID；取消旧状态请求，并先发一个认证 GET 触发服务端完成轮换，
    // 避免首屏多个并发请求带着旧 Session ID 得到 401。
    await queryClient.cancelQueries({ queryKey: ['auth', 'status'] });
    try {
      await get('/tasks');
    } catch {
      // 真正失效时由下面的状态确认与 401 回调处理。
    }
    try {
      const latest = await authApi.status();
      queryClient.setQueryData(['auth', 'status'], latest);
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    } catch (failure) {
      setError(failure.message);
    }
  };

  const signIn = useMutation({
    mutationFn: payload =>
      status?.initialized ? authApi.login(payload) : authApi.initialize(payload),
    onSuccess: finish,
    onError: failure => setError(failure.message)
  });

  const signOut = useMutation({
    mutationFn: authApi.logout,
    onSuccess: () => {
      queryClient.setQueryData(['auth', 'status'], previous => ({
        initialized: previous?.initialized ?? true,
        currentUser: null
      }));
      queryClient.clear();
      setError('');
    },
    onError: async failure => {
      try {
        const latest = await authApi.status();
        if (latest.currentUser) {
          setError(t('auth.logoutFailedSession', { message: failure.message }));
          return;
        }
      } catch {
        setError(t('auth.logoutFailedUnknown', { message: failure.message }));
        return;
      }
      queryClient.clear();
    }
  });

  if (session.isLoading) return <Splash message={t('auth.loading')} />;
  if (session.isError) {
    return (
      <Box sx={{ p: 4, maxWidth: 640, mx: 'auto' }}>
        <Alert
          severity="error"
          action={<Button onClick={() => session.refetch()}>{t('auth.retry')}</Button>}
        >
          {session.error.message}
        </Alert>
      </Box>
    );
  }

  if (!user) {
    const setup = !status?.initialized;
    const busy = signIn.isPending;
    const submit = event => {
      event.preventDefault();
      if (!username.trim() || password.length < 8) {
        setError(t('auth.invalidCredentials'));
        return;
      }
      signIn.mutate(
        setup
          ? {
              username: username.trim(),
              password,
              displayName: displayName.trim() || username.trim(),
              claimLegacyHistory: claim
            }
          : { username: username.trim(), password }
      );
    };

    return (
      <Box
        sx={{
          minHeight: '100vh',
          display: 'grid',
          placeItems: 'center',
          bgcolor: 'background.default',
          px: 2,
          py: 6,
          backgroundImage:
            'radial-gradient(circle at 20% 20%, rgba(26,115,232,.06), transparent 42%), radial-gradient(circle at 80% 80%, rgba(52,168,83,.06), transparent 45%)'
        }}
      >
        <Box
          component="form"
          onSubmit={submit}
          sx={{
            width: 440,
            maxWidth: '100%',
            bgcolor: 'background.paper',
            border: `1px solid ${googleTokens.outline}`,
            borderRadius: 6,
            p: { xs: 3, sm: 4.5 }
          }}
        >
          <Stack direction="row" spacing={1.2} sx={{ mb: 3, alignItems: 'center' }}>
            <AppLogo size={30} />
            <Typography sx={{ fontSize: 20, fontWeight: 600, letterSpacing: '-0.01em' }}>
              Agent Studio
            </Typography>
          </Stack>
          <Typography sx={{ fontSize: 24, fontWeight: 500, lineHeight: 1.35 }}>
            {setup ? t('auth.initTitle') : t('auth.loginTitle')}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1, mb: 3 }}>
            {setup ? t('auth.initDescription') : t('auth.loginDescription')}
          </Typography>
          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}
          <Stack spacing={2}>
            <TextField
              label={t('auth.username')}
              value={username}
              onChange={event => setUsername(event.target.value)}
              placeholder={t('auth.usernamePlaceholder')}
              autoComplete="username"
              autoFocus
              fullWidth
              size="small"
            />

            {setup && (
              <TextField
                label={t('auth.displayName')}
                value={displayName}
                onChange={event => setDisplayName(event.target.value)}
                placeholder={t('auth.displayNamePlaceholder')}
                fullWidth
                size="small"
              />
            )}
            <TextField
              label={t('auth.password')}
              type="password"
              value={password}
              onChange={event => setPassword(event.target.value)}
              placeholder={t('auth.passwordPlaceholder')}
              autoComplete={setup ? 'new-password' : 'current-password'}
              fullWidth
              size="small"
            />

            {setup && (
              <FormControlLabel
                control={
                  <Checkbox
                    size="small"
                    checked={claim}
                    onChange={event => setClaim(event.target.checked)}
                  />
                }
                label={<Typography variant="body2">{t('auth.claimLegacy')}</Typography>}
              />
            )}
            <Button type="submit" variant="contained" fullWidth loading={busy} sx={{ height: 42 }}>
              {setup ? t('auth.initSubmit') : t('auth.loginSubmit')}
            </Button>
          </Stack>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2.5 }}>
            {t('auth.securityNote')}
          </Typography>
        </Box>
      </Box>
    );
  }

  return (
    <AuthContext.Provider value={{ user, logout: signOut.mutate, loggingOut: signOut.isPending }}>
      {children}
      {error && (
        <Alert
          severity="error"
          onClose={() => setError('')}
          action={
            <Button
              color="inherit"
              size="small"
              loading={signOut.isPending}
              onClick={() => signOut.mutate()}
            >
              {t('auth.retryLogout')}
            </Button>
          }
          sx={{ position: 'fixed', top: 68, right: 16, zIndex: 3000, maxWidth: 520 }}
        >
          {error}
        </Alert>
      )}
    </AuthContext.Provider>
  );
}
