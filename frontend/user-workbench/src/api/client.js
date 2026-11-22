/**
 * HTTP 客户端。
 *
 * <p>所有请求以同源 Cookie 会话运行；CSRF token 只保存在模块闭包内，并通过共享 Promise
 * 合并首屏并发初始化，避免重复拉取。403 校验失败时刷新 token 重试一次。
 *
 * <p>登录会由服务端更换 Session ID。首屏并发请求中，使用旧 Session ID 的请求可能返回 401；
 * 因此 401 不立即登出，而是先确认服务端会话，仍有登录态时用新 Cookie 重试原请求一次。
 */

import { getCurrentLocale } from '../i18n';

const API_PREFIX = '/api';
const SESSION_PATHS = new Set([
  '/auth/status',
  '/auth/csrf',
  '/auth/login',
  '/auth/initialize',
  '/auth/logout'
]);

let csrfToken = null;
let csrfPromise = null;
let csrfEpoch = 0;
let unauthorizedHandler = null;
let recoveryPromise = null;

export class ApiError extends Error {
  constructor(message, status, body) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

export function setUnauthorizedHandler(handler) {
  unauthorizedHandler = handler;
}

export function resetCsrfToken() {
  csrfToken = null;
  csrfPromise = null;
  csrfEpoch += 1;
}

async function readBody(response) {
  const text = await response.text();
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    return { message: text };
  }
}

async function ensureCsrf(force = false) {
  if (force) {
    csrfToken = null;
    csrfPromise = null;
  }
  if (!force && csrfToken) return csrfToken;
  if (!csrfPromise) {
    const epoch = csrfEpoch;
    csrfPromise = fetch(`${API_PREFIX}/auth/csrf`, { credentials: 'same-origin' })
      .then(async response => {
        if (!response.ok) throw new ApiError('无法初始化 CSRF token', response.status);
        const body = await readBody(response);
        const token = body.token ?? null;
        if (epoch === csrfEpoch) csrfToken = token;
        return token;
      })
      .finally(() => {
        csrfPromise = null;
      });
  }
  return csrfPromise;
}

function send(path, { method, headers, body, signal }) {
  return fetch(`${API_PREFIX}${path}`, {
    method,
    // 所有请求统一附带当前界面语言；服务端据此返回本地化报错与诊断。
    headers: { 'Accept-Language': getCurrentLocale(), ...(headers || {}) },
    body,
    signal,
    credentials: 'same-origin'
  });
}

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

/**
 * 登录切换 Session 后，首个请求可能触发服务端再次轮换 Session ID，并让并发请求短暂 401。
 * 这里等待并确认会话；确认失败才视为真正失效。并发 401 共享同一次确认。
 */
async function recoverSession() {
  if (!recoveryPromise) {
    recoveryPromise = (async () => {
      for (let attempt = 0; attempt < 3; attempt += 1) {
        if (attempt > 0) await delay(140);
        try {
          const response = await send('/auth/status', {
            method: 'GET',
            headers: { Accept: 'application/json' }
          });
          if (!response.ok) continue;
          const body = await readBody(response);
          if (body?.currentUser) return body.currentUser;
        } catch {
          // 网络暂时不可用时继续尝试；最终失败按会话失效处理。
        }
      }
      return null;
    })().finally(() => {
      recoveryPromise = null;
    });
  }
  return recoveryPromise;
}

export async function request(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase();
  const mutating = !['GET', 'HEAD', 'OPTIONS'].includes(method);
  const headers = { Accept: 'application/json', ...(options.headers || {}) };
  let body = options.body;
  if (body != null && typeof body !== 'string' && !(body instanceof FormData)) {
    body = JSON.stringify(body);
  }
  if (body != null && !(body instanceof FormData) && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json';
  }

  if (mutating) {
    const token = await ensureCsrf();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }

  let response = await send(path, { method, headers, body, signal: options.signal });
  let parsed = await readBody(response);

  if (
    mutating &&
    response.status === 403 &&
    String(parsed.message || '').includes('请求校验失败')
  ) {
    const token = await ensureCsrf(true);
    if (token) headers['X-XSRF-TOKEN'] = token;
    response = await send(path, { method, headers, body, signal: options.signal });
    parsed = await readBody(response);
  }

  if (response.status === 401 && !SESSION_PATHS.has(path)) {
    const user = await recoverSession();
    if (user) {
      resetCsrfToken();
      if (mutating) {
        const token = await ensureCsrf(true);
        if (token) headers['X-XSRF-TOKEN'] = token;
      }
      response = await send(path, { method, headers, body, signal: options.signal });
      parsed = await readBody(response);
    }
  }

  if (response.status === 401) {
    if (unauthorizedHandler) unauthorizedHandler();
    throw new ApiError(parsed.message || '登录状态已失效，请重新登录。', 401, parsed);
  }
  if (!response.ok) {
    throw new ApiError(parsed.message || `请求失败（${response.status}）`, response.status, parsed);
  }
  return parsed;
}

export const get = (path, options) => request(path, { ...options, method: 'GET' });
export const post = (path, data, options) =>
  request(path, { ...options, method: 'POST', body: data });
export const put = (path, data, options) =>
  request(path, { ...options, method: 'PUT', body: data });
export const del = (path, options) => request(path, { ...options, method: 'DELETE' });

/** 兼容过渡期组件：允许调用方传入已序列化的 body。 */
export const api = request;
