import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { planningApi } from '../api/planning';

/** 候选计划：生成期间按 1.2 秒轮询，进入就绪/失败后自动停止。 */
export function usePlanCandidate(enabled = true) {
  const [candidateId, setCandidateId] = useState(null);

  const candidate = useQuery({
    queryKey: ['plan-candidate', candidateId],
    queryFn: () => planningApi.candidate(candidateId),
    enabled: enabled && Boolean(candidateId),
    refetchInterval: query => (query.state.data?.status === 'GENERATING' ? 1200 : false)
  });

  const generate = useMutation({
    mutationFn: payload => planningApi.createCandidate(payload),
    onSuccess: created => setCandidateId(created.id)
  });

  const cancel = useMutation({
    mutationFn: () => planningApi.cancelCandidate(candidateId)
  });

  const createRun = useMutation({
    mutationFn: payload => planningApi.createRun(candidateId, payload)
  });

  const revise = useMutation({
    mutationFn: payload => planningApi.reviseDraft(candidateId, payload)
  });

  return {
    candidateId,
    current: candidate.data ?? null,
    isLoading: generate.isPending || candidate.isLoading,
    error: generate.error || candidate.error || cancel.error || createRun.error || revise.error,
    generate: payload => generate.mutate(payload),
    cancel: () => cancel.mutate(),
    createRun: payload => createRun.mutateAsync(payload),
    /**
     * 应用画布修改：落库成功后立刻回读候选，让「确认并创建运行」拿到新的候选摘要。
     *
     * <p>服务端拒绝（结构校验未通过）时不回读——候选没变，回读只会白刷一次。
     */
    reviseDraft: async payload => {
      const result = await revise.mutateAsync(payload);
      if (result?.applied) await candidate.refetch();
      return result;
    }
  };
}
