import { post } from './client';

/**
 * 计划草稿校验预览。
 *
 * <p>纯校验接口：不落库、不创建候选、不影响任何运行；画布可以在每次结构调整后调用它
 * 拿到结构化冲突。请求带工作区时服务端会额外核对归属。
 */
export const plansApi = {
  validateDraft: (draft, input = null) =>
    post('/plans/validate-draft', input ? { draft, input } : { draft })
};
