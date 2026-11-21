import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { agentsApi } from '../api/agents';

export function useMyAgents() {
  const skills = useQuery({ queryKey: ['my-skills'], queryFn: agentsApi.mySkills });
  const tools = useQuery({ queryKey: ['my-tools'], queryFn: agentsApi.myTools });
  const agents = useQuery({ queryKey: ['my-agents'], queryFn: agentsApi.myAgents });
  return { skills, tools, agents };
}

export function usePublishAgentComponent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ type, id, action }) => {
      const api = {
        skill: action === 'publish' ? agentsApi.publishSkill : agentsApi.archiveSkill,
        tool: action === 'publish' ? agentsApi.publishTool : agentsApi.archiveTool,
        agent: action === 'publish' ? agentsApi.publishAgent : agentsApi.archiveAgent
      }[type];
      return api(id);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['my-skills'] });
      queryClient.invalidateQueries({ queryKey: ['my-tools'] });
      queryClient.invalidateQueries({ queryKey: ['my-agents'] });
    }
  });
}
