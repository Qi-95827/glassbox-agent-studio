import { useQuery } from '@tanstack/react-query';
import { authApi } from '../api/auth';
import { tasksApi } from '../api/tasks';

export function useSession() {
  return useQuery({
    queryKey: ['auth', 'status'],
    queryFn: authApi.status,
    retry: false,
    staleTime: 0
  });
}

export function useTasks() {
  return useQuery({
    queryKey: ['tasks'],
    queryFn: tasksApi.list,
    refetchInterval: 10000
  });
}

export function useOverview() {
  return useQuery({ queryKey: ['overview'], queryFn: tasksApi.overview, refetchInterval: 10000 });
}

export function useEntryPolicies() {
  return useQuery({
    queryKey: ['entry-policies'],
    queryFn: tasksApi.entryPolicies,
    staleTime: 5 * 60 * 1000
  });
}
