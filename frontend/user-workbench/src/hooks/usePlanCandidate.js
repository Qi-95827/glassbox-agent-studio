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

  return {
    candidateId,
    current: candidate.data ?? null,
    isLoading: generate.isPending || candidate.isLoading,
    error: generate.error || candidate.error || cancel.error || createRun.error,
    generate: payload => generate.mutate(payload),
    cancel: () => cancel.mutate(),
    createRun: payload => createRun.mutateAsync(payload)
  };
}
