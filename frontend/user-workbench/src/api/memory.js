import { get, post, put, del } from './client';

/**
 * 记忆与知识的一次性读取封装。
 *
 * <p>所有路径都以工作区为前缀，服务端在进入方法前统一校验工作区归属；
 * 前端不自行拼接所有者参数，避免出现“凭 ID 猜权限”的调用方式。
 */
export const memoryApi = {
  knowledgeDocuments: workspaceId => get(`/workspaces/${workspaceId}/knowledge`),
  knowledgeChunks: (workspaceId, documentId) =>
    get(`/workspaces/${workspaceId}/knowledge/${encodeURIComponent(documentId)}/chunks`),
  /** 召回透视：返回候选的 BM25 / 向量 / RRF 明细与入选原因。 */
  knowledgeXray: (workspaceId, query, limit = 5) =>
    get(`/workspaces/${workspaceId}/knowledge/xray?q=${encodeURIComponent(query)}&limit=${limit}`),
  memories: (workspaceId, filters = {}) => {
    const params = new URLSearchParams();
    if (filters.status) params.set('status', filters.status);
    if (filters.type) params.set('type', filters.type);
    const query = params.toString();
    return get(`/workspaces/${workspaceId}/memories${query ? `?${query}` : ''}`);
  },
  /** 人工冻结/解冻与纠偏：accept 视同放行，ignore 视同冻结。 */
  acceptMemory: (workspaceId, id, version) =>
    post(`/workspaces/${workspaceId}/memories/${id}/accept?version=${version}`, {}),
  ignoreMemory: (workspaceId, id, version) =>
    post(`/workspaces/${workspaceId}/memories/${id}/ignore?version=${version}`, {}),
  updateMemory: (workspaceId, id, payload) =>
    put(`/workspaces/${workspaceId}/memories/${id}`, payload),
  deleteMemory: (workspaceId, id, version) =>
    del(`/workspaces/${workspaceId}/memories/${id}?version=${version}`)
};
