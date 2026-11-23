import { useQuery } from '@tanstack/react-query';
import { evalLabApi } from '../api/evalLab';

const activeRuns = query => {
  const rows = query.state.data ?? [];
  return rows.some(row => ['QUEUED', 'RUNNING'].includes(row.status)) ? 1500 : 12000;
};

export function useEvalLabMeta() {
  return useQuery({ queryKey: ['eval-lab-meta'], queryFn: evalLabApi.meta, staleTime: 60_000 });
}

export function useEvalLabRuns() {
  return useQuery({
    queryKey: ['eval-lab-runs'],
    queryFn: evalLabApi.runs,
    refetchInterval: activeRuns
  });
}

export function useEvalLabRun(id) {
  return useQuery({
    queryKey: ['eval-lab-run', id],
    queryFn: () => evalLabApi.run(id),
    enabled: Boolean(id),
    refetchInterval: query =>
      ['QUEUED', 'RUNNING'].includes(query.state.data?.run?.status) ? 1500 : false
  });
}

export function useEvalHub() {
  return useQuery({ queryKey: ['eval-lab-hub'], queryFn: evalLabApi.hub, staleTime: 15_000 });
}

export function useEvalDatasets() {
  return useQuery({ queryKey: ['eval-lab-datasets'], queryFn: evalLabApi.datasets });
}

export function useEvalCompare(baseline, candidate) {
  return useQuery({
    queryKey: ['eval-lab-compare', baseline, candidate],
    queryFn: () => evalLabApi.compare(baseline, candidate),
    enabled: Boolean(baseline && candidate && baseline !== candidate)
  });
}
