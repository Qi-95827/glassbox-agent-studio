import { get, post, put } from './client';

export const agentsApi = {
  catalog: () => get('/agents/catalog'),
  mySkills: () => get('/my/skills'),
  myTools: () => get('/my/tools'),
  myAgents: () => get('/my/agents'),
  createSkill: payload => post('/my/skills', payload),
  updateSkill: (id, payload) => put(`/my/skills/${id}`, payload),
  publishSkill: id => post(`/my/skills/${id}/publish`),
  archiveSkill: id => post(`/my/skills/${id}/archive`),
  skillVersions: id => get(`/my/skills/${id}/versions`),
  createTool: payload => post('/my/tools', payload),
  updateTool: (id, payload) => put(`/my/tools/${id}`, payload),
  publishTool: id => post(`/my/tools/${id}/publish`),
  archiveTool: id => post(`/my/tools/${id}/archive`),
  toolVersions: id => get(`/my/tools/${id}/versions`),
  createAgent: payload => post('/my/agents', payload),
  updateAgent: (id, payload) => put(`/my/agents/${id}`, payload),
  publishAgent: id => post(`/my/agents/${id}/publish`),
  archiveAgent: id => post(`/my/agents/${id}/archive`),
  agentVersions: id => get(`/my/agents/${id}/versions`),
  draftAgent: payload => post('/my/agents/draft', payload)
};
