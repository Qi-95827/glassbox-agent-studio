import { get as clientGet } from './client';
import type { ContextArtifact } from '../types/workbench';

/** `client.js` 的 GET 签名；此处只用到「路径进、已解析 JSON 出」。 */
type GetRequest = <T>(path: string, options?: { signal?: AbortSignal }) => Promise<T>;

// `client.js` 仍是 JS，返回类型只能推到 `any`；在这个唯一边界上一次性声明成显式泛型。
const get = clientGet as GetRequest;

/**
 * L1 上下文产物回读。
 *
 * <p>引用本身不是授权凭证：服务端会校验该产物属于哪个运行，再按会话归属放行。
 * 前端只负责把引用原样带上，不做任何本地缓存或猜测。
 */
export const contextArtifactsApi = {
  read: (reference: string): Promise<ContextArtifact> =>
    get(`/context-artifacts/${encodeURIComponent(reference)}`)
};
