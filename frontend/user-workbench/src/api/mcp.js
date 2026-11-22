import { get, post, put, del } from './client';

export const mcpApi = {
  list: options => get('/mcp-servers', options),
  create: payload => post('/mcp-servers', payload),
  update: (id, payload) => put(`/mcp-servers/${id}`, payload),
  remove: id => del(`/mcp-servers/${id}`),
  probe: id => post(`/mcp-servers/${id}/probe`),
  updateTool: (serverId, toolId, payload) =>
    put(`/mcp-servers/${serverId}/tools/${toolId}`, payload)
};
