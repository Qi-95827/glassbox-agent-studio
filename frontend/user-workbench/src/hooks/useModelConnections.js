import { useQuery } from '@tanstack/react-query';
import { modelConnectionsApi } from '../api/modelConnections';

/** 当前用户的模型连接与内置项；设置页和任务模型选择器共用同一缓存。 */
export function useModelConnections(enabled = true) {
  return useQuery({
    queryKey: ['model-connections'],
    queryFn: modelConnectionsApi.list,
    enabled,
    staleTime: 30_000
  });
}
