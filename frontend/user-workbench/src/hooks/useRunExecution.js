import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { runsApi } from '../api/runs';
import { isLiveStatus } from '../constants/labels';

const MAX_CACHED_RUN_STREAMS = 10;

function pruneOldRunEntries(cursorMap, eventsMap, currentRunId) {
  while (eventsMap.size > MAX_CACHED_RUN_STREAMS) {
    const oldestKey = eventsMap.keys().next().value;
    if (oldestKey === currentRunId) break;
    eventsMap.delete(oldestKey);
    cursorMap.delete(oldestKey);
  }
}

/**
 * 运行级 SSE：优先实时事件；连续三次连接失败后静默关闭，由 1.8s 轮询兜底。
 *
 * <p>浏览器 EventSource 会自动携带 Last-Event-ID 重连；服务端按 run_seq 补发遗漏事件。
 * MODEL_DELTA 只保存在内存中，不写 React Query 缓存。
 */
export function useRunStream(runId, live) {
  const queryClient = useQueryClient();
  const [connected, setConnected] = useState(false);
  const [streamText, setStreamText] = useState('');

  useEffect(() => {
    if (!runId || !live || typeof EventSource === 'undefined') {
      setConnected(false);
      return undefined;
    }
    let source = null;
    let closed = false;
    let failures = 0;
    let retryTimer = null;
    const invalidate = () => {
      queryClient.invalidateQueries({ queryKey: ['run-events', runId] });
      queryClient.invalidateQueries({ queryKey: ['execution-view', runId] });
      queryClient.invalidateQueries({ queryKey: ['run', runId] });
    };
    const connect = () => {
      if (closed) return;
      try {
        source = new EventSource(`/api/runs/${runId}/stream`, { withCredentials: true });
      } catch {
        failures += 1;
        if (!closed && failures < 3) retryTimer = setTimeout(connect, 800 * failures);
        return;
      }
      source.onopen = () => {
        failures = 0;
        setConnected(true);
      };
      source.addEventListener('RUN_EVENT', invalidate);
      source.addEventListener('MODEL_DELTA', message => {
        try {
          const payload = JSON.parse(message.data);
          if (payload?.text) setStreamText(previous => (previous + payload.text).slice(-4000));
        } catch {
          // 非本项目格式的瞬态片段忽略，不影响持久化事件。
        }
      });
      source.onerror = () => {
        source?.close();
        setConnected(false);
        failures += 1;
        if (!closed && failures < 3) retryTimer = setTimeout(connect, 800 * failures);
      };
    };
    connect();
    return () => {
      closed = true;
      if (retryTimer) clearTimeout(retryTimer);
      source?.close();
      setConnected(false);
    };
  }, [runId, live, queryClient]);

  return { connected, streamText };
}

/** 运行详情 + 服务端执行视图，运行中自动轮询，进入终态自动停止。 */
export function useRunExecution(runId) {
  const stream = useRunStream(runId, Boolean(runId));
  const runQuery = useQuery({
    queryKey: ['run', runId],
    queryFn: ({ signal }) => runsApi.get(runId, false, { signal }),
    enabled: Boolean(runId),
    refetchInterval: query =>
      isLiveStatus(query.state.data?.run?.STATUS) && !stream.connected ? 1800 : false
  });

  const viewQuery = useQuery({
    queryKey: ['execution-view', runId],
    queryFn: ({ signal }) => runsApi.executionView(runId, { signal }),
    enabled: Boolean(runId),
    refetchInterval: query =>
      isLiveStatus(query.state.data?.runStatus) && !stream.connected ? 1800 : false
  });

  const status = viewQuery.data?.runStatus ?? runQuery.data?.run?.STATUS ?? 'PENDING';
  const live = isLiveStatus(status);

  return {
    runQuery,
    viewQuery,
    detail: runQuery.data,
    view: viewQuery.data,
    status,
    live,
    streamConnected: stream.connected,
    streamText: stream.streamText,
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
      pruneOldRunEntries(cursorByRun.current, eventsByRun.current, runId);
    }
    return () => {
      if (runId) queryClient.cancelQueries({ queryKey: ['run-events', runId] });
    };
  }, [runId, queryClient]);

  return useQuery({
    queryKey: ['run-events', runId],
    queryFn: async ({ signal }) => {
      let cursor = cursorByRun.current.get(runId) ?? 0;
      const working = [...(eventsByRun.current.get(runId) ?? [])];
      const known = new Set(working.map(event => event.ID));
      for (let round = 0; round < 25; round += 1) {
        const page = await runsApi.eventsPage(runId, cursor, 200, { signal });
        if (signal?.aborted) return working;
        const absorbed = absorbEventPage(working, known, cursor, page);
        cursor = absorbed.cursor;
        if (absorbed.received < 200) break;
      }
      if (!signal?.aborted) {
        cursorByRun.current.set(runId, cursor);
        eventsByRun.current.set(runId, working);
        pruneOldRunEntries(cursorByRun.current, eventsByRun.current, runId);
      }
      return working;
    },
    enabled: Boolean(runId) && enabled,
    refetchInterval: live ? 1800 : false,
    staleTime: 0
  });
}

export function useNodeDetail(runId, nodeId, live = false) {
  return useQuery({
    queryKey: ['node-detail', runId, nodeId],
    queryFn: ({ signal }) => runsApi.nodeDetail(runId, nodeId, { signal }),
    enabled: Boolean(runId && nodeId),
    refetchInterval: live ? 1800 : false
  });
}

export function useRunDelivery(runId, enabled = true) {
  return useQuery({
    queryKey: ['delivery', runId],
    queryFn: ({ signal }) => runsApi.delivery(runId, { signal }),
    enabled: Boolean(runId) && enabled,
    retry: 0
  });
}

export function useAgentMetrics(runId, enabled = true) {
  return useQuery({
    queryKey: ['agent-metrics', runId],
    queryFn: ({ signal }) => runsApi.agentMetrics(runId, { signal }),
    enabled: Boolean(runId) && enabled
  });
}
