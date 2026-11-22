import { del, get, post, put } from './client';
import { getCurrentLocale } from '../i18n';

export const modelConnectionsApi = {
  presets: () => get('/model-connections/presets'),
  list: () => get('/model-connections'),
  create: payload => post('/model-connections', payload),
  update: (id, payload) => put(`/model-connections/${id}`, payload),
  remove: id => del(`/model-connections/${id}`),
  setDefault: id => post(`/model-connections/${id}/set-default`),
  test: id => post(`/model-connections/${id}/test`),
  testDraft: payload => post('/model-connections/test-draft', payload),
  estimateRun: runId => get(`/model-connections/runs/${runId}/estimate`)
};

/** 模型选择值：mock 或 user:<connectionId>；解析后直接对应后端字段。 */
export function connectionValue(connection) {
  return connection ? `user:${connection.id}` : 'mock';
}

export function parseModelValue(value) {
  const text = String(value ?? '');
  if (text.startsWith('user:'))
    return { modelMode: 'user', modelConnectionId: text.slice(5) || null };
  return { modelMode: text === 'deepseek' ? 'deepseek' : 'mock', modelConnectionId: null };
}

export function selectedConnection(list, value) {
  const { modelConnectionId } = parseModelValue(value);
  if (!modelConnectionId) return null;
  return (list?.connections ?? []).find(item => item.id === modelConnectionId) ?? null;
}

export function modelSelectionLabel(list, value, locale = getCurrentLocale()) {
  const connection = selectedConnection(list, value);
  if (connection) return connection.displayName || connection.modelName;
  const english = String(locale || '')
    .toLowerCase()
    .startsWith('en');
  if (value === 'deepseek') {
    return english ? 'DeepSeek Flash (local key file)' : 'DeepSeek Flash（本机密钥文件）';
  }
  return english ? 'Simulated model (no key)' : '模拟模型（免 Key）';
}
