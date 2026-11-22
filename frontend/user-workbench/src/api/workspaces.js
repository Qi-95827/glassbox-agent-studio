import { del, get, post, put } from './client';

export const workspacesApi = {
  list: () => get('/workspaces'),
  create: payload => post('/workspaces', payload),
  fromIdea: files => post('/workspaces/from-idea', { files }),
  status: id => get(`/workspaces/${id}/status`),
  files: (id, keyword = '') =>
    get(`/workspaces/${encodeURIComponent(id)}/files?${new URLSearchParams({ keyword })}`),
  readFile: (id, path) => get(`/workspaces/${id}/file?path=${encodeURIComponent(path)}`),
  writeFile: (id, payload) => put(`/workspaces/${id}/file`, payload),
  createFile: (id, payload) => post(`/workspaces/${id}/file`, payload),
  untracked: id => get(`/workspaces/${id}/untracked`),
  reviewUntracked: (id, payload) => post(`/workspaces/${id}/untracked/review`, payload),
  approvals: id => get(`/workspaces/${id}/approvals`),
  proposeApproval: id => post(`/workspaces/${id}/approvals`),
  decideApproval: (approvalId, decision) =>
    post(`/workspaces/approvals/${approvalId}/decision`, { decision }),
  commits: id => get(`/workspaces/${id}/commits`),
  createCommit: (id, payload) => post(`/workspaces/${id}/commits`, payload),
  deliveries: id => get(`/workspaces/${id}/deliveries`),
  prepareDelivery: (id, payload) => post(`/workspaces/${id}/deliveries`, payload),
  decideDelivery: (deliveryId, approve) =>
    post(`/workspaces/deliveries/${deliveryId}/decision`, { approve }),
  executeDelivery: deliveryId => post(`/workspaces/deliveries/${deliveryId}/execute`),
  memories: (id, params = {}) => {
    const query = new URLSearchParams();
    if (params.status) query.set('status', params.status);
    if (params.type) query.set('type', params.type);
    const suffix = query.toString() ? `?${query}` : '';
    return get(`/workspaces/${id}/memories${suffix}`);
  },
  searchMemories: (id, q) => get(`/workspaces/${id}/memories/search?${new URLSearchParams({ q })}`),
  memoryHistory: (id, memoryId, beforeVersion) => {
    const query = new URLSearchParams({ limit: '20' });
    if (beforeVersion != null) query.set('beforeVersion', String(beforeVersion));
    return get(`/workspaces/${id}/memories/${memoryId}/history?${query}`);
  },
  createMemory: (id, payload) => post(`/workspaces/${id}/memories`, payload),
  updateMemory: (id, memoryId, payload) => put(`/workspaces/${id}/memories/${memoryId}`, payload),
  deleteMemory: (id, memoryId, version) =>
    del(`/workspaces/${id}/memories/${memoryId}?version=${version}`),
  acceptMemory: (id, memoryId, version) =>
    post(`/workspaces/${id}/memories/${memoryId}/accept?version=${version}`),
  ignoreMemory: (id, memoryId, version) =>
    post(`/workspaces/${id}/memories/${memoryId}/ignore?version=${version}`),
  promoteMemory: (id, memoryId, version, payload) =>
    post(`/workspaces/${id}/memories/${memoryId}/promote?version=${version}`, payload),
  repositoryGrants: () => get('/admin/repositories'),
  grantRepository: payload => post('/admin/repositories', payload),
  revokeRepository: id => del(`/admin/repositories/${id}`)
};
