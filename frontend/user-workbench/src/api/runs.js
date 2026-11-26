import { get, post, put } from './client';

export function reportUrl(runId, view) {
  return `/api/runs/${runId}/report${view ? `?view=${encodeURIComponent(view)}` : ''}`;
}

export const runsApi = {
  get: (runId, includeEvents = false, options) =>
    get(`/runs/${runId}?includeEvents=${includeEvents ? 'true' : 'false'}`, options),
  executionView: (runId, options) => get(`/runs/${runId}/execution-view`, options),
  nodeDetail: (runId, nodeId, options) =>
    get(`/runs/${runId}/execution-view/nodes/${encodeURIComponent(nodeId)}`, options),
  agentMetrics: (runId, options) => get(`/runs/${runId}/agent-metrics`, options),
  skills: runId => get(`/runs/${runId}/skills`),
  memoryLoad: runId => get(`/runs/${runId}/memory-load`),
  agentSessions: runId => get(`/runs/${runId}/agent-sessions`),
  toolGrants: runId => get(`/runs/${runId}/tool-grants`),
  eventsPage: (runId, after, limit = 200, options) =>
    get(`/runs/${runId}/events?after=${after}&limit=${limit}`, options),
  pause: runId => post(`/runs/${runId}/pause`),
  resume: runId => post(`/runs/${runId}/resume`),
  cancel: runId => post(`/runs/${runId}/cancel`),
  approvePlan: (runId, planVersionId) => post(`/runs/${runId}/plans/${planVersionId}/approve`),
  recover: (runId, checkpointId) => post(`/runs/${runId}/recover/${checkpointId}`),
  submitSupplement: (runId, requestId, payload) =>
    post(`/runs/${runId}/supplements/${requestId}`, payload),
  takeHandover: (runId, note) => post(`/runs/${runId}/handover`, { note }),
  returnHandover: (runId, note) => post(`/runs/${runId}/handover/return`, { note }),
  delivery: (runId, options) => get(`/runs/${runId}/delivery`, options),
  deliveryFile: (runId, path) =>
    get(`/runs/${runId}/delivery/files?path=${encodeURIComponent(path)}`),
  planProposal: (runId, payload) => post(`/runs/${runId}/plan-proposals`, payload),
  applyPlanProposal: (runId, proposalId) =>
    post(`/runs/${runId}/plan-proposals/${proposalId}/apply`),
  sourceArtifacts: (runId, payload) => post(`/runs/${runId}/source-artifacts`, payload),
  revokeScope: (runId, directory) => post(`/runs/${runId}/workspace-scope/revoke`, { directory }),
  reconcileExternalAction: actionId => post(`/external-actions/${actionId}/reconcile`),
  updateDebugControl: (runId, payload) => put(`/runs/${runId}/debug-control`, payload),
  toolApprovals: (runId, options) => get(`/runs/${runId}/tool-approvals`, options),
  decideToolApproval: (runId, approvalId, payload) =>
    post(`/runs/${runId}/tool-approvals/${approvalId}/decision`, payload),
  timeline: (runId, options) => get(`/runs/${runId}/timeline`, options),
  checkpoints: (runId, options) => get(`/runs/${runId}/checkpoints`, options),
  diff: (runId, otherRunId, options) => get(`/runs/${runId}/diff/${otherRunId}`, options),
  forks: (runId, options) => get(`/runs/${runId}/forks`, options),
  fork: (runId, payload) => post(`/runs/${runId}/forks`, payload),
  iterate: (runId, payload) => post(`/runs/${runId}/iterate`, payload),
  rounds: taskId => get(`/tasks/${encodeURIComponent(taskId)}/runs`),
  architectureXray: (runId, options) => get(`/runs/${runId}/architecture-xray`, options)
};
