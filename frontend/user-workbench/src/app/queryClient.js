import { QueryClient } from '@tanstack/react-query';

/** 共享缓存：轮询变化由各领域 Hook 的 refetchInterval 控制。 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 4000,
      refetchOnWindowFocus: false
    }
  }
});
