import { get } from './client';

/**
 * L1 上下文产物回读。
 *
 * <p>引用本身不是授权凭证：服务端会校验该产物属于哪个运行，再按会话归属放行。
 * 前端只负责把引用原样带上，不做任何本地缓存或猜测。
 */
export const contextArtifactsApi = {
  read: reference => get(`/context-artifacts/${encodeURIComponent(reference)}`)
};
