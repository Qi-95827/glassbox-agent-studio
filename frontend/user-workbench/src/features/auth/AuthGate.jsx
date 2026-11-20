import React, { createContext, useContext, useEffect, useState } from 'react';
import { Alert, Button, Card, Checkbox, Input, Spin, Typography } from 'antd';

const AuthContext = createContext(null);

export function useAuth() {
  return useContext(AuthContext);
}

async function call(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase();
  const headers = { ...(options.headers || {}) };
  if (options.body != null) headers['Content-Type'] = 'application/json';
  if (!['GET', 'HEAD'].includes(method)) {
    try {
      const tokenResponse = await fetch('/api/auth/csrf');
      const tokenBody = await tokenResponse.json();
      if (tokenBody.token) headers['X-XSRF-TOKEN'] = tokenBody.token;
    } catch { /* CSRF 初始化失败时让服务端返回明确错误 */ }
  }
  const response = await fetch('/api' + path, { ...options, headers });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || '请求失败');
  return body;
}

/**
 * 本地账号入口。
 *
 * <p>初始化完成前保留旧单机访问；初始化后未登录请求由服务端拒绝。登录状态只来自服务端会话，
 * 页面不把请求体里的用户标识当作归属依据。
 */
export default function AuthGate({ children }) {
  const [status, setStatus] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [claim, setClaim] = useState(true);

  const refresh = async () => {
    try {
      setStatus(await call('/auth/status'));
      setError('');
    } catch (failure) {
      setError(failure.message);
    }
  };
  useEffect(() => { refresh(); }, []);

  const submit = async () => {
    setBusy(true);
    setError('');
    try {
      if (!status.initialized) {
        await call('/auth/initialize', {
          method: 'POST',
          body: JSON.stringify({ username, password, displayName, claimLegacyHistory: claim })
        });
      } else {
        await call('/auth/login', {
          method: 'POST',
          body: JSON.stringify({ username, password })
        });
      }
      setPassword('');
      globalThis.__workbenchCsrf = null;
      await refresh();
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  const logout = async () => {
    setError('');
    setBusy(true);
    let logoutSucceeded = false;
    try {
      await call('/auth/logout', { method: 'POST' });
      logoutSucceeded = true;
    } catch (failure) {
      // 请求失败不代表服务端会话已失效；先复查状态，仍登录时保留页面并提示重试。
      try {
        const latest = await call('/auth/status');
        if (latest.currentUser) {
          setStatus(latest);
          setError('退出失败：' + failure.message + '。服务端会话可能仍有效，请重试。');
          setBusy(false);
          return;
        }
        logoutSucceeded = true;
      } catch {
        setError('退出失败：' + failure.message + '。无法确认会话状态，请检查网络后重试。');
        setBusy(false);
        return;
      }
    }
    if (!logoutSucceeded) {
      setBusy(false);
      return;
    }
    globalThis.__workbenchCsrf = null;
    try {
      setStatus(await call('/auth/status'));
    } catch {
      setStatus({ initialized: true, currentUser: null });
    }
    setBusy(false);
  };

  if (!status) return <div style={{ padding: 40 }}>{error
    ? <Alert type="error" message={error} />
    : <Spin tip="正在读取本地账号状态" />}</div>;

  if (!status.currentUser) {
    const setup = !status.initialized;
    return <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: '#f5f7fb', padding: 20 }}>
      <Card style={{ width: 460, maxWidth: '100%' }}>
        <Typography.Title level={3}>{setup ? '初始化本地管理员' : '登录 Agent Studio'}</Typography.Title>
        <Typography.Paragraph type="secondary">
          {setup
            ? '首次初始化会创建本地管理员。勾选领取后，旧 LOCAL 历史任务和工作区归入该管理员；不勾选可稍后在「我的 Agent · 账号」中领取。'
            : '账号和密码只保存在本机数据库；页面通过服务端会话识别当前用户。'}
        </Typography.Paragraph>
        {error && <Alert type="error" message={error} style={{ marginBottom: 12 }} />}
        <div style={{ marginBottom: 8 }}>账号</div>
        <Input value={username} onChange={event => setUsername(event.target.value)} placeholder="3-32 位小写字母或数字" />
        {setup && <>
          <div style={{ margin: '12px 0 8px' }}>显示名称</div>
          <Input value={displayName} onChange={event => setDisplayName(event.target.value)} placeholder="例如：本地管理员" />
          <div style={{ margin: '12px 0 8px' }}>领取旧 LOCAL 历史数据</div>
          <Checkbox checked={claim} onChange={event => setClaim(event.target.checked)}>
            把现有任务、工作区和运行归入此管理员
          </Checkbox>
        </>}
        <div style={{ margin: '12px 0 8px' }}>密码</div>
        <Input.Password value={password} onChange={event => setPassword(event.target.value)} placeholder="至少 8 位" onPressEnter={submit} />
        <Button type="primary" block loading={busy} style={{ marginTop: 18 }} onClick={submit}>
          {setup ? '初始化并登录' : '登录'}
        </Button>
      </Card>
    </div>;
  }

  return <AuthContext.Provider value={{ user: status.currentUser, refresh, logout }}>
    {children}
    {error && <Alert type="error" showIcon closable message={error} onClose={() => setError('')}
      action={<Button size="small" loading={busy} onClick={logout}>重试退出</Button>}
      style={{ position: 'fixed', top: 76, right: 12, zIndex: 3000, maxWidth: 520 }} />}
  </AuthContext.Provider>;
}
