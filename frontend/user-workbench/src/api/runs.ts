import { get as clientGet, post as clientPost, put as clientPut } from './client';
import type {
  AcceptedResponse,
  AgentMetricsRow,
  ArchitectureXray,
  ExecutionView,
  HandoverRecord,
  JsonObject,
  NodeDetail,
  PlanProposal,
  PlanProposalApplied,
  RunCheckpointList,
  RunDelivery,
  RunDeliveryFile,
  RunDetail,
  RunDiff,
  RunEventPage,
  RunForkList,
  RunForked,
  RunIterationResult,
  RunRecoverResult,
  RunRound,
  RunTimeline,
  SupplementSubmitResult,
  ToolApproval
} from '../types/workbench';

/**
 * 只读请求可选项：与 `client.js` 的 `request(path, options)` 一致。
 *
 * <p>调用方目前只用到 `signal`（切运行/切节点时取消旧请求）；`headers` 保留给需要附加请求头的调用。
 */
export interface RequestOptions {
  signal?: AbortSignal;
  headers?: Record<string, string>;
}

/** `client.js` 的 GET：已确认下发 `{ ...options, method: 'GET' }`，不携带 body。 */
type GetRequest = <T>(path: string, options?: RequestOptions) => Promise<T>;

/** `client.js` 的写请求：`data` 会被 `request()` 序列化成 JSON body（FormData 原样透传）。 */
type MutateRequest = <T>(path: string, data?: unknown, options?: RequestOptions) => Promise<T>;

// `client.js` 仍是 JS：它能推出来的返回类型只有 `any`。这里在唯一的边界上一次性声明成显式泛型签名，
// 业务代码因此可以逐端点写清返回类型，而不需要在每个调用点做类型断言或滥用 `any`。
const get = clientGet as GetRequest;
const post = clientPost as MutateRequest;
const put = clientPut as MutateRequest;

/**
 * 运行报告下载地址。
 *
 * <p>`view` 为空时返回不带查询串的地址；否则按 `encodeURIComponent` 编码后拼上 `?view=`。
 */
export function reportUrl(runId: string, view?: string | null): string {
  return `/api/runs/${runId}/report${view ? `?view=${encodeURIComponent(view)}` : ''}`;
}

/**
 * 运行相关接口。
 *
 * <p>导出的键名与迁移前的 `runs.js` 完全一致（每个方法同名、同参数顺序、同默认值）；
 * `includeEvents` 的默认值仍是 `false`。
 */
export const runsApi = {
  get: (runId: string, includeEvents = false, options?: RequestOptions): Promise<RunDetail> =>
    get(`/runs/${runId}?includeEvents=${includeEvents ? 'true' : 'false'}`, options),
  executionView: (runId: string, options?: RequestOptions): Promise<ExecutionView> =>
    get(`/runs/${runId}/execution-view`, options),
  nodeDetail: (runId: string, nodeId: string, options?: RequestOptions): Promise<NodeDetail> =>
    get(`/runs/${runId}/execution-view/nodes/${encodeURIComponent(nodeId)}`, options),
  agentMetrics: (runId: string, options?: RequestOptions): Promise<AgentMetricsRow[]> =>
    get(`/runs/${runId}/agent-metrics`, options),
  skills: (runId: string): Promise<JsonObject[]> => get(`/runs/${runId}/skills`),
  memoryLoad: (runId: string): Promise<JsonObject> => get(`/runs/${runId}/memory-load`),
  agentSessions: (runId: string): Promise<JsonObject[]> => get(`/runs/${runId}/agent-sessions`),
  toolGrants: (runId: string): Promise<JsonObject[]> => get(`/runs/${runId}/tool-grants`),
  eventsPage: (
    runId: string,
    after: number,
    limit = 200,
    options?: RequestOptions
  ): Promise<RunEventPage> => get(`/runs/${runId}/events?after=${after}&limit=${limit}`, options),
  pause: (runId: string): Promise<AcceptedResponse> => post(`/runs/${runId}/pause`),
  resume: (runId: string): Promise<AcceptedResponse> => post(`/runs/${runId}/resume`),
  cancel: (runId: string): Promise<AcceptedResponse> => post(`/runs/${runId}/cancel`),
  approvePlan: (runId: string, planVersionId: string): Promise<AcceptedResponse> =>
    post(`/runs/${runId}/plans/${planVersionId}/approve`),
  recover: (runId: string, checkpointId: string): Promise<RunRecoverResult> =>
    post(`/runs/${runId}/recover/${checkpointId}`),
  submitSupplement: (
    runId: string,
    requestId: string,
    payload: unknown
  ): Promise<SupplementSubmitResult> => post(`/runs/${runId}/supplements/${requestId}`, payload),
  takeHandover: (runId: string, note: string): Promise<HandoverRecord> =>
    post(`/runs/${runId}/handover`, { note }),
  returnHandover: (runId: string, note: string): Promise<HandoverRecord> =>
    post(`/runs/${runId}/handover/return`, { note }),
  delivery: (runId: string, options?: RequestOptions): Promise<RunDelivery> =>
    get(`/runs/${runId}/delivery`, options),
  deliveryFile: (runId: string, path: string): Promise<RunDeliveryFile> =>
    get(`/runs/${runId}/delivery/files?path=${encodeURIComponent(path)}`),
  planProposal: (runId: string, payload: unknown): Promise<PlanProposal> =>
    post(`/runs/${runId}/plan-proposals`, payload),
  applyPlanProposal: (runId: string, proposalId: string): Promise<PlanProposalApplied> =>
    post(`/runs/${runId}/plan-proposals/${proposalId}/apply`),
  sourceArtifacts: (runId: string, payload: unknown): Promise<JsonObject[]> =>
    post(`/runs/${runId}/source-artifacts`, payload),
  revokeScope: (runId: string, directory: string): Promise<JsonObject> =>
    post(`/runs/${runId}/workspace-scope/revoke`, { directory }),
  reconcileExternalAction: (actionId: string): Promise<JsonObject> =>
    post(`/external-actions/${actionId}/reconcile`),
  updateDebugControl: (runId: string, payload: unknown): Promise<JsonObject> =>
    put(`/runs/${runId}/debug-control`, payload),
  toolApprovals: (runId: string, options?: RequestOptions): Promise<ToolApproval[]> =>
    get(`/runs/${runId}/tool-approvals`, options),
  decideToolApproval: (
    runId: string,
    approvalId: string,
    payload: unknown
  ): Promise<ToolApproval> => post(`/runs/${runId}/tool-approvals/${approvalId}/decision`, payload),
  timeline: (runId: string, options?: RequestOptions): Promise<RunTimeline> =>
    get(`/runs/${runId}/timeline`, options),
  checkpoints: (runId: string, options?: RequestOptions): Promise<RunCheckpointList> =>
    get(`/runs/${runId}/checkpoints`, options),
  diff: (runId: string, otherRunId: string, options?: RequestOptions): Promise<RunDiff> =>
    get(`/runs/${runId}/diff/${otherRunId}`, options),
  forks: (runId: string, options?: RequestOptions): Promise<RunForkList> =>
    get(`/runs/${runId}/forks`, options),
  fork: (runId: string, payload: unknown): Promise<RunForked> =>
    post(`/runs/${runId}/forks`, payload),
  iterate: (runId: string, payload: unknown): Promise<RunIterationResult> =>
    post(`/runs/${runId}/iterate`, payload),
  rounds: (taskId: string): Promise<RunRound[]> => get(`/tasks/${encodeURIComponent(taskId)}/runs`),
  architectureXray: (runId: string, options?: RequestOptions): Promise<ArchitectureXray> =>
    get(`/runs/${runId}/architecture-xray`, options)
};
