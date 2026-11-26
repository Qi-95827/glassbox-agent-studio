import { get, post, put } from './client';

export const planningApi = {
  createCandidate: payload => post('/plan-candidates', payload),
  candidate: id => get(`/plan-candidates/${id}`),
  cancelCandidate: id => post(`/plan-candidates/${id}/cancel`),
  createRun: (id, payload) => post(`/plan-candidates/${id}/runs`, payload),
  reviseDraft: (id, payload) => put(`/plan-candidates/${id}/draft`, payload)
};
