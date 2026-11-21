import { get, post } from './client';
import { parseFileScopes } from '../utils/format';

/** 把数据库列名风格的任务行适配为 UI 模型，页面组件不再直接接触列名。 */
export function normalizeTask(row) {
  if (!row) return null;
  return {
    ...row,
    id: row.ID,
    runId: row.RUN_ID,
    goal: row.GOAL,
    status: row.STATUS,
    taskStatus: row.TASK_STATUS || row.STATUS,
    workspaceDelivered: Boolean(row.WORKSPACE_DELIVERED),
    createdAt: row.CREATED_AT,
    updatedAt: row.UPDATED_AT,
    entryKind: row.ENTRY_KIND,
    modelMode: row.MODEL_MODE,
    workspaceId: row.WORKSPACE_ID,
    bugId: row.BUG_ID,
    archived: Boolean(row.ARCHIVED_AT),
    archivedAt: row.ARCHIVED_AT,
    version: row.VERSION,
    fileScopes: parseFileScopes(row)
  };
}

export const tasksApi = {
  list: async () => (await get('/tasks')).map(normalizeTask),
  overview: () => get('/overview'),
  entryPolicies: () => get('/entry-policies'),
  previewEntryRoute: payload => post('/entry-routes/preview', payload),
  draftIdea: payload => post('/task-ideas/draft', payload),
  create: payload => post('/tasks', payload),
  archive: (taskId, archived) => post(`/tasks/${taskId}/archive`, { archived })
};
