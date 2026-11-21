import { get, post } from './client';

export const BUG_ID = 'MOCK-PY-001';

export const bugsApi = {
  list: () => get('/bugs'),
  checksByRun: runId => get(`/bugs/checks/by-run?${new URLSearchParams({ runId })}`),
  feedback: workspaceId => get(`/bugs/feedback?${new URLSearchParams({ workspaceId })}`),
  delivery: runId => get(`/bugs/delivery?${new URLSearchParams({ runId })}`),
  runCheck: (workspaceId, kind, bugId = BUG_ID) =>
    post(`/bugs/${bugId}/checks`, { workspaceId, kind }),
  sendFeedback: (workspaceId, status, bugId = BUG_ID) =>
    post(`/bugs/${bugId}/feedback`, { workspaceId, status, note: '用户在本地工作台提交' })
};
