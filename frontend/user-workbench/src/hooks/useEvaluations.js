import { useQuery } from '@tanstack/react-query';
import { evaluationApi } from '../api/evaluations';

const liveBatches = query => {
  const rows = query.state.data ?? [];
  return rows.some(row => ['RUNNING', 'CANCELLING'].includes(row.status)) ? 1800 : 15000;
};

export function useEvaluations() {
  return useQuery({
    queryKey: ['evaluations'],
    queryFn: evaluationApi.list,
    refetchInterval: liveBatches
  });
}

export function useEvaluationDetail(evaluationId) {
  return useQuery({
    queryKey: ['evaluation', evaluationId],
    queryFn: () => evaluationApi.detail(evaluationId),
    enabled: Boolean(evaluationId),
    refetchInterval: query =>
      ['RUNNING', 'CANCELLING'].includes(query.state.data?.batch?.status) ? 1800 : false
  });
}

export function useMonitoring(enabled = true) {
  return useQuery({
    queryKey: ['monitoring'],
    queryFn: evaluationApi.monitoring,
    enabled,
    refetchInterval: enabled ? 5000 : false
  });
}

export function useMonitoringAlerts(enabled = true) {
  return useQuery({
    queryKey: ['monitoring-alerts'],
    queryFn: evaluationApi.alerts,
    enabled,
    refetchInterval: enabled ? 5000 : false
  });
}
