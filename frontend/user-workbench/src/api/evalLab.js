import { del, get, post, put } from './client';

/** Eval Lab V2 API；所有批次读写都由服务端按账号归属隔离。 */
export const evalLabApi = {
  meta: () => get('/eval-lab/meta'),
  runs: () => get('/eval-lab/runs'),
  run: id => get(`/eval-lab/runs/${encodeURIComponent(id)}`),
  start: payload => post('/eval-lab/runs', payload),
  cancel: id => post(`/eval-lab/runs/${encodeURIComponent(id)}/cancel`, {}),
  compare: (baseline, candidate) =>
    get(`/eval-lab/compare?${new URLSearchParams({ baseline, candidate })}`),
  hub: () => get('/eval-lab/hub'),
  downloadPack: packId => post(`/eval-lab/hub/${encodeURIComponent(packId)}/download`, {}),
  importPack: (packId, jsonl) =>
    post(`/eval-lab/hub/${encodeURIComponent(packId)}/import`, { jsonl }),
  datasets: () => get('/eval-lab/datasets'),
  createDataset: payload => post('/eval-lab/datasets', payload),
  updateDataset: (id, payload) => put(`/eval-lab/datasets/${encodeURIComponent(id)}`, payload),
  deleteDataset: id => del(`/eval-lab/datasets/${encodeURIComponent(id)}`),
  datasetSamples: id => get(`/eval-lab/datasets/${encodeURIComponent(id)}/samples`),
  saveSample: (id, sampleKey, payload) =>
    put(
      `/eval-lab/datasets/${encodeURIComponent(id)}/samples/${encodeURIComponent(sampleKey)}`,
      payload
    ),
  deleteSample: (id, sampleKey) =>
    del(`/eval-lab/datasets/${encodeURIComponent(id)}/samples/${encodeURIComponent(sampleKey)}`),
  importDataset: (id, jsonl) =>
    post(`/eval-lab/datasets/${encodeURIComponent(id)}/import`, { jsonl }),
  exportDataset: id => get(`/eval-lab/datasets/${encodeURIComponent(id)}/export`)
};
