import { useEffect, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { runsApi } from '../api/runs';
import { isLiveStatus } from '../constants/labels';

const liveInterval = query => {
  const status = query.state.data?.run?.STATUS ?? query.state.data?.runStatus;
  return isLiveStatus(status) ? 1800 : false;
};

/** 运行详情 + 服务端执行视图，运行中自动轮询，进入终态自动停止。 */
export function useRunExecution(runId) {
  const runQuery = useQuery({
    queryKey: ['run', runId],
    queryFn: () => runsApi.get(runId, false),
    enabled: Boolean(runId),
    refetchInterval: liveInterval
  });

  const viewQuery = useQuery({
    queryKey: ['execution-view', runId],
    queryFn: () => runsApi.executionView(runId),
    enabled: Boolean(runId),
    refetchInterval: liveInterval
  });

  const status = viewQuery.data?.runStatus ?? runQuery.data?.run?.STATUS ?? 'PENDING';

  return {
    runQuery,
    viewQuery,
    detail: runQuery.data,
    view: viewQuery.data,
    status,
    live: isLiveStatus(status),
    loading: runQuery.isLoading || viewQuery.isLoading,
    error: runQuery.error || viewQuery.error,
    refetch: () => {
      runQuery.refetch();
      viewQuery.refetch();
    }
  };
}

/** 事件页合并：游标只在服务端返回时推进，事件按 ID 去重。 */
export function absorbEventPage(events, known, cursor, page) {
  const rows = page?.events ?? [];
  rows.forEach(event => {
    if (known.has(event.id)) return;
    known.add(event.id);
    events.push({
      ID: event.id,
      RUN_ID: event.runId,
      RUN_SEQ: event.runSeq,
      EVENT_TYPE: event.type,
      MESSAGE: event.message,
      CREATED_AT: event.occurredAt,
      PLAN_VERSION_ID: event.planVersionId,
      NODE_ID: event.nodeId,
      ATTEMPT_ID: event.attemptId,
      AGENT_ID: event.agentId,
      CHECK_ID: event.checkId,
      MODEL_CALL_ID: event.modelCallId,
      TOOL_CALL_ID: event.toolCallId,
      REASON_CODE: event.reasonCode
    });
  });
  return {
    events,
    known,
    cursor: page?.nextCursor ?? cursor,
    received: rows.length
  };
}

/**
 * 事件增量读取。
 *
 * <p>每个运行维护独立游标与已合并事件；轮询从 nextCursor 续取，按事件 ID 去重。
 * 切换运行时取消旧查询（AbortSignal 传到 fetch），旧响应不会写入新运行的缓存。
 */
export function useRunEvents(runId, enabled = true, live = false) {
  const queryClient = useQueryClient();
  const cursorByRun = useRef(new Map());
  const eventsByRun = useRef(new Map());
  const previousRun = useRef(null);

  useEffect(() => {
    const previous = previousRun.current;
    if (previous && previous !== runId) {
      queryClient.cancelQueries({ queryKey: ['run-events', previous] });
    }
    previousRun.current = runId;
    if (runId && !cursorByRun.current.has(runId)) {
      cursorByRun.current.set(runId, 0);
      eventsByRun.current.set(runId, []);
    }
    return () => {
      if (runId) queryClient.cancelQueries({ queryKey: ['run-events', runId] });
    };
  }, [runId, queryClient]);

  return useQuery({
    queryKey: ['run-events', runId],
    queryFn: async ({ signal }) => {
      let cursor = cursorByRun.current.get(runId) ?? 0;
      const merged = eventsByRun.current.get(runId) ?? [];
      const known = new Set(merged.map(event => event.ID));
      for (let round = 0; round < 25; round += 1) {
        const page = await runsApi.eventsPage(runId, cursor, 200, { signal });
        const absorbed = absorbEventPage(merged, known, cursor, page);
        cursor = absorbed.cursor;
        if (absorbed.received < 200) break;
      }
      cursorByRun.current.set(runId, cursor);
      eventsByRun.current.set(runId, merged);
      return [...merged];
    },
    enabled: Boolean(runId) && enabled,
    refetchInterval: live ? 1800 : false,
    staleTime: 0
  });
}

export function useNodeDetail(runId, nodeId, live = false) {
  return useQuery({
    queryKey: ['node-detail', runId, nodeId],
    queryFn: () => runsApi.nodeDetail(runId, nodeId),
    enabled: Boolean(runId && nodeId),
    refetchInterval: live ? 1800 : false
  });
}

export function useRunDelivery(runId, enabled = true) {
  return useQuery({
    queryKey: ['delivery', runId],
    queryFn: () => runsApi.delivery(runId),
    enabled: Boolean(runId) && enabled,
    retry: 0
  });
}

export function useAgentMetrics(runId, enabled = true) {
  return useQuery({
    queryKey: ['agent-metrics', runId],
    queryFn: () => runsApi.agentMetrics(runId),
    enabled: Boolean(runId) && enabled
  });
}
