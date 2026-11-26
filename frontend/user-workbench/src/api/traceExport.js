import { get, post } from './client';

/** 绝对路径导出地址：交给浏览器直接下载，避免在内存里再拼一次大 JSON。 */
export function traceExportUrl(runId, format) {
  return `/api/runs/${runId}/trace-export?format=${encodeURIComponent(format)}`;
}

export const runsApiExtras = {
  traceExportUrl,
  evalCasePreview: runId => get(`/runs/${runId}/eval-case-preview`),
  exportEvalCase: (runId, payload = {}) => post(`/runs/${runId}/eval-cases`, payload),
  externalTraces: () => get('/external-traces/runs'),
  externalTrace: id => get(`/external-traces/runs/${id}`),
  createExternalTrace: payload => post('/external-traces/runs', payload)
};
