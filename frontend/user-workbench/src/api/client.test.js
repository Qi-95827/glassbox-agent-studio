import { describe, expect, it, vi, beforeEach } from 'vitest';
import { get, post, resetCsrfToken, setUnauthorizedHandler } from './client';

function response(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => (body == null ? '' : JSON.stringify(body))
  };
}

describe('api client', () => {
  beforeEach(() => {
    resetCsrfToken();
    setUnauthorizedHandler(null);
    vi.unstubAllGlobals();
  });

  it('并发初始化 CSRF 只请求一次，并携带校验头', async () => {
    const calls = [];
    const fetchMock = vi.fn(async (url, options = {}) => {
      calls.push({ url, options });
      if (url === '/api/auth/csrf') return response(200, { token: 'csrf-1' });
      return response(200, { ok: true });
    });
    vi.stubGlobal('fetch', fetchMock);

    await Promise.all([post('/tasks', { goal: 'a' }), post('/tasks', { goal: 'b' })]);

    const csrfCalls = calls.filter(call => call.url === '/api/auth/csrf');
    expect(csrfCalls).toHaveLength(1);
    const posts = calls.filter(call => call.url === '/api/tasks');
    expect(posts).toHaveLength(2);
    expect(posts[0].options.headers['X-XSRF-TOKEN']).toBe('csrf-1');
    expect(posts[0].options.credentials).toBe('same-origin');
  });

  it('403 请求校验失败时刷新 token 并重试一次', async () => {
    const tokens = ['csrf-old', 'csrf-new'];
    let csrfIndex = 0;
    let postCount = 0;
    const fetchMock = vi.fn(async (url, options = {}) => {
      if (url === '/api/auth/csrf') {
        return response(200, { token: tokens[csrfIndex++] });
      }
      postCount += 1;
      if (postCount === 1) return response(403, { message: '请求校验失败' });
      expect(options.headers['X-XSRF-TOKEN']).toBe('csrf-new');
      return response(200, { accepted: true });
    });
    vi.stubGlobal('fetch', fetchMock);

    const body = await post('/runs/r1/pause');
    expect(body).toEqual({ accepted: true });
    expect(postCount).toBe(2);
  });

  it('英文界面下按稳定 errorCode 刷新 token 重试，不再依赖中文文案', async () => {
    const tokens = ['csrf-old', 'csrf-new'];
    let csrfIndex = 0;
    let postCount = 0;
    const fetchMock = vi.fn(async (url, options = {}) => {
      if (url === '/api/auth/csrf') return response(200, { token: tokens[csrfIndex++] });
      postCount += 1;
      if (postCount === 1) {
        return response(403, {
          errorCode: 'CSRF_INVALID',
          message: 'Request validation failed. Refresh the page and try again.'
        });
      }
      expect(options.headers['X-XSRF-TOKEN']).toBe('csrf-new');
      return response(200, { accepted: true });
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(post('/runs/r1/pause')).resolves.toEqual({ accepted: true });
    expect(postCount).toBe(2);
  });

  it('带回稳定错误码的权限拒绝不触发 CSRF 重试，并保留 errorCode', async () => {
    let postCount = 0;
    const fetchMock = vi.fn(async url => {
      if (url === '/api/auth/csrf') return response(200, { token: 'csrf-1' });
      postCount += 1;
      return response(403, {
        errorCode: 'ACCESS_DENIED',
        message: 'This account is not allowed to access the resource.'
      });
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(post('/admin/grants')).rejects.toMatchObject({
      status: 403,
      errorCode: 'ACCESS_DENIED'
    });
    // 只发一次：真正的权限拒绝不能靠刷新 token 重试来“绕过”。
    expect(postCount).toBe(1);
  });

  it('CSRF 重试只有一次，二次仍失败时直接抛错', async () => {
    let csrfIndex = 0;
    let postCount = 0;
    const fetchMock = vi.fn(async url => {
      if (url === '/api/auth/csrf') return response(200, { token: `csrf-${csrfIndex++}` });
      postCount += 1;
      return response(403, { errorCode: 'CSRF_INVALID', message: 'Request validation failed.' });
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(post('/runs/r1/pause')).rejects.toMatchObject({ status: 403 });
    expect(postCount).toBe(2);
  });

  it('并发首屏 401 在会话确认后重试，不触发登出', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    let taskCalls = 0;
    const fetchMock = vi.fn(async url => {
      if (url === '/api/auth/status') {
        return response(200, {
          initialized: true,
          currentUser: { id: 'u1', username: 'admin' }
        });
      }
      if (url === '/api/tasks') {
        taskCalls += 1;
        return taskCalls === 1 ? response(401, { message: '请先登录。' }) : response(200, []);
      }
      return response(404, {});
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(get('/tasks')).resolves.toEqual([]);
    expect(taskCalls).toBe(2);
    expect(handler).not.toHaveBeenCalled();
  });

  it('会话确认失败才触发登出回调', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    const fetchMock = vi.fn(async url => {
      if (url === '/api/auth/status') {
        return response(200, { initialized: true, currentUser: null });
      }
      return response(401, { message: '请先登录。' });
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(get('/tasks')).rejects.toThrow('请先登录。');
    expect(handler).toHaveBeenCalledTimes(1);
  });
});
