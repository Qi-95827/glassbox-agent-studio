import { useQuery } from '@tanstack/react-query';
import { workspacesApi } from '../api/workspaces';

export function useWorkspaces() {
  return useQuery({
    queryKey: ['workspaces'],
    queryFn: workspacesApi.list,
    staleTime: 10000
  });
}

export function useWorkspaceStatus(workspaceId) {
  return useQuery({
    queryKey: ['workspace-status', workspaceId],
    queryFn: () => workspacesApi.status(workspaceId),
    enabled: Boolean(workspaceId)
  });
}

export function useWorkspaceApprovals(workspaceId) {
  return useQuery({
    queryKey: ['workspace-approvals', workspaceId],
    queryFn: () => workspacesApi.approvals(workspaceId),
    enabled: Boolean(workspaceId)
  });
}

export function useWorkspaceCommits(workspaceId) {
  return useQuery({
    queryKey: ['workspace-commits', workspaceId],
    queryFn: () => workspacesApi.commits(workspaceId),
    enabled: Boolean(workspaceId)
  });
}

export function useWorkspaceMemories(workspaceId) {
  return useQuery({
    queryKey: ['workspace-memories', workspaceId],
    queryFn: () => workspacesApi.memories(workspaceId),
    enabled: Boolean(workspaceId)
  });
}

export function useWorkspaceDeliveries(workspaceId) {
  return useQuery({
    queryKey: ['workspace-deliveries', workspaceId],
    queryFn: () => workspacesApi.deliveries(workspaceId),
    enabled: Boolean(workspaceId)
  });
}

export function useWorkspaceUntracked(workspaceId) {
  return useQuery({
    queryKey: ['workspace-untracked', workspaceId],
    queryFn: () => workspacesApi.untracked(workspaceId),
    enabled: Boolean(workspaceId)
  });
}
