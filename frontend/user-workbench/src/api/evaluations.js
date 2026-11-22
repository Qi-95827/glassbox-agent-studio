import { del, get, post, put } from './client';

export const evaluationApi = {
  list: () => get('/evaluations'),
  detail: id => get(`/evaluations/${id}`),
  startConfigured: payload => post('/evaluations/configured', payload),
  cancel: id => post(`/evaluations/${id}/cancel`),
  compare: (baseline, candidate) =>
    get(`/evaluations/compare?${new URLSearchParams({ baseline, candidate })}`),
  bugEvidence: runId => get(`/evaluations/bug-evidence?${new URLSearchParams({ runId })}`),
  dataset: key => get(`/evaluation-datasets/${encodeURIComponent(key)}`),
  datasetVersions: key => get(`/evaluation-datasets/${encodeURIComponent(key)}/versions`),
  saveSample: (key, code, payload) =>
    put(
      `/evaluation-datasets/${encodeURIComponent(key)}/samples/${encodeURIComponent(code)}`,
      payload
    ),
  deleteSample: (key, code) =>
    del(`/evaluation-datasets/${encodeURIComponent(key)}/samples/${encodeURIComponent(code)}`),
  publishDataset: key => post(`/evaluation-datasets/${encodeURIComponent(key)}/publish`, {}),
  datasetFromRun: (key, payload) =>
    post(`/evaluation-datasets/${encodeURIComponent(key)}/from-run`, payload),
  startDatasetBatch: (key, version) =>
    post(
      `/evaluation-datasets/${encodeURIComponent(key)}/batches${version ? `?version=${version}` : ''}`,
      { maxRounds: 4, maxTools: 2, maxSeconds: 60 }
    ),
  datasetBatches: () => get('/evaluation-datasets/batches'),
  datasetBatch: id => get(`/evaluation-datasets/batches/${id}`),
  monitoring: () => get('/monitoring'),
  monitoringDashboard: ({ range = '7d', status, model } = {}) => {
    const params = new URLSearchParams({ range });
    if (status) params.set('status', status);
    if (model) params.set('model', model);
    return get(`/monitoring/dashboard?${params.toString()}`);
  },
  runDiagnostics: runId => get(`/monitoring/runs/${encodeURIComponent(runId)}/diagnostics`),
  alerts: () => get('/monitoring/alerts'),
  audit: (limit = 20) => get(`/audit?limit=${limit}`),
  selfCheck: () => get('/system/self-check')
};

export const costApi = {
  prices: () => get('/cost/prices'),
  publishPrice: payload => post('/cost/prices', payload),
  estimate: runId => get(`/cost/estimate?${new URLSearchParams({ runId })}`)
};
