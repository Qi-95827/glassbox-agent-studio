import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { runsApi } from '../api/runs';
import { isLiveStatus } from '../constants/labels';
import useDocumentVisible from './useDocumentVisible';

const MAX_CACHED_RUN_STREAMS = 10;

/** SSE 高频事件合并窗口：250~300ms 内的多次事件只触发一次缓存失效，避免整页重渲染风暴。 */
const INVALIDATE_DEBOUNCE_MS = 280;

/** 轮询兜底间隔；仅在「运行仍会变化 + SSE 未连通 + 标签页可见」时生效。 */
export const RUN_POLL_INTERVAL_MS = 1800;

function pruneOldRunEntries(cursorMap, eventsMap, currentRunId) {
  while (eventsMap.size > MAX_CACHED_RUN_STREAMS) {
    const oldestKey = eventsMap.keys().next().value;
    if (oldestKey === currentRunId) break;
    eventsMap.delete(oldestKey);
    cursorMap.delete(oldestKey);
  }
}

/**
 * 轮询间隔的唯一判据。
 *
 * <p>三个条件必须同时成立：运行仍在变化、SSE 没有连通、标签页可见。任何一项不成立都返回
 * `false` 关闭定时器——终态运行与已建立 SSE 的运行不再产生重复的 REST 请求。
 *
 * @param status 服务端权威状态；`undefined` 表示首屏尚未拿到状态。
 * @param streamConnected 运行级 SSE 是否已连通。
 * @param visible 标签页是否可见。
 */
export function pollingInterval(status, streamConnected, visible) {
  if (!visible) return false;
  if (streamConnected) return false;
  return isLiveStatus(status) ? RUN_POLL_INTERVAL_MS : false;
}

/** 单个节点的实时缓冲上限：只服务「正在看」的观感，不需要保留全文。 */
const LIVE_BUFFER_LIMIT = 4000;

/** 运行终态事件：到达后实时缓冲不再有意义，落库快照已经可以完全替代它。 */
const RUN_TERMINAL_EVENTS = new Set([
  'RUN_COMPLETED',
  'CANCELLED',
  'FAILED',
  'LIMITED',
  'INTERRUPTED',
  'BLOCKED'
]);

const EMPTY_LIVE = { content: '', reasoning: '', gate: null };

/**
 * 运行级 SSE：优先实时事件；连续三次连接失败后静默关闭，由 1.8s 轮询兜底。
 *
 * <p>浏览器 EventSource 会自动携带 Last-Event-ID 重连；服务端按 run_seq 补发遗漏事件。
 * 瞬态增量帧只保存在内存中，不写 React Query 缓存，也不落库。
 *
 * <p>`live` 为 false 时不建立连接：已进入终态的运行不会再有新事件，为它开 SSE 只会
 * 常驻一个被服务端立即关闭的空连接。
 *
 * <p>增量帧按 `nodeId` 分桶：并行节点会交错推送，共用一个字符串缓冲会把两个节点的正文
 * 拼在一起。缓冲在「该节点开启新一轮模型调用」时清空，在运行进入终态时整体清空。
 */
export function useRunStream(runId, live) {
  const queryClient = useQueryClient();
  const [connected, setConnected] = useState(false);
  const [streamsByNode, setStreamsByNode] = useState({});
  const [streamNodeId, setStreamNodeId] = useState(null);

  const applyDelta = (nodeId, channel, text) => {
    if (!nodeId || !text) return;
    setStreamNodeId(nodeId);
    setStreamsByNode(previous => {
      const current = previous[nodeId] ?? EMPTY_LIVE;
      const merged = (current[channel] ?? '') + text;
      return {
        ...previous,
        [nodeId]: {
          ...current,
          [channel]: merged.slice(-LIVE_BUFFER_LIMIT)
        }
      };
    });
  };

  const applyGate = (nodeId, gate, tool) => {
    if (!nodeId) return;
    setStreamNodeId(nodeId);
    setStreamsByNode(previous => ({
      ...previous,
      [nodeId]: { ...(previous[nodeId] ?? EMPTY_LIVE), gate: { ...gate, tool } }
    }));
  };

  /** 新一轮模型调用开始：丢掉该节点的上一轮正文，避免 T1 的尾巴混进 T2。 */
  const clearNode = nodeId => {
    setStreamsByNode(previous => {
      if (!previous[nodeId]) return previous;
      const next = { ...previous };
      delete next[nodeId];
      return next;
    });
  };

  useEffect(() => {
    if (!runId || !live || typeof EventSource === 'undefined') {
      setConnected(false);
      return undefined;
    }
    let source = null;
    let closed = false;
    let failures = 0;
    let retryTimer = null;
    let invalidateTimer = null;
    const flushInvalidate = () => {
      invalidateTimer = null;
      queryClient.invalidateQueries({ queryKey: ['run-events', runId] });
      queryClient.invalidateQueries({ queryKey: ['execution-view', runId] });
      queryClient.invalidateQueries({ queryKey: ['run', runId] });
      // SSE 连通期间 useNodeDetail 已停止轮询；不在这里失效的话，
      // 正开着的节点抽屉/玻璃盒会一直停在打开那一刻的快照。
      queryClient.invalidateQueries({ queryKey: ['node-detail', runId] });
    };
    const invalidate = () => {
      // 合并高频事件：只有最后一个事件真正触发一次失效。
      if (invalidateTimer != null) return;
      invalidateTimer = setTimeout(flushInvalidate, INVALIDATE_DEBOUNCE_MS);
    };
    const handleRunEvent = message => {
      invalidate();
      try {
        const event = JSON.parse(message.data);
        // MODEL_FINISHED 表示该轮回答已经落库：不清空会让同一条回答既出现在历史轮次卡里，
        // 又继续挂在底部「实时输出」区；节点终态事件同理。
        if (
          event?.nodeId &&
          ['MODEL_STARTED', 'MODEL_FINISHED', 'HANDOFF', 'HANDOFF_ACCEPTED'].includes(event?.type)
        ) {
          clearNode(event.nodeId);
        } else if (RUN_TERMINAL_EVENTS.has(event?.type)) setStreamsByNode({});
      } catch {
        // 事件正文异常不应影响缓存失效；实时缓冲由后续帧自行修正。
      }
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
      source.addEventListener('RUN_EVENT', handleRunEvent);
      source.addEventListener('MODEL_DELTA', message => {
        try {
          const payload = JSON.parse(message.data);
          applyDelta(payload?.nodeId, 'content', payload?.text);
        } catch {
          // 非本项目格式的瞬态片段忽略，不影响持久化事件。
        }
      });
      source.addEventListener('MODEL_REASONING_DELTA', message => {
        try {
          const payload = JSON.parse(message.data);
          applyDelta(payload?.nodeId, 'reasoning', payload?.text);
        } catch {
          // 推理通道与正文通道互相独立，解析失败不污染正文。
        }
      });
      source.addEventListener('TOOL_GATE_PROGRESS', message => {
        try {
          const payload = JSON.parse(message.data);
          // 安检门进度同时驱动节点微状态；具体判定详情由节点抽屉的流水线读取落库结果。
          applyGate(payload?.nodeId, payload?.detail ?? {}, payload?.text);
          invalidate();
        } catch {
          // 瞬态帧解析失败只影响实时提示，不影响持久化事实。
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
      if (invalidateTimer) clearTimeout(invalidateTimer);
      source?.close();
      setConnected(false);
    };
  }, [runId, live, queryClient]);

  // 切换运行时必须清空：上一个运行的实时正文不能出现在新运行的节点抽屉里。
  useEffect(() => {
    setStreamsByNode({});
    setStreamNodeId(null);
  }, [runId]);

  const active = streamNodeId ? streamsByNode[streamNodeId] : null;
  return {
    connected,
    // 兼容只关心「最近一次」调用方的旧接口；新代码应使用 streamsByNode。
    streamText: active?.content ?? '',
    reasoningText: active?.reasoning ?? '',
    lastGateEvent: active?.gate ?? null,
    streamsByNode,
    streamNodeId
  };
}

/**
 * 运行详情 + 服务端执行视图。
 *
 * <p>首屏先读一次权威状态：`status` 未知时不建立 SSE，避免为终态运行白白开连接。
 * 拿到状态后只有 `isLiveStatus` 才订阅流；SSE 连通期间完全停止 REST 轮询。
 */
export function useRunExecution(runId) {
  const visible = useDocumentVisible();
  const streamRef = useRef(false);

  const runQuery = useQuery({
    queryKey: ['run', runId],
    queryFn: ({ signal }) => runsApi.get(runId, false, { signal }),
    enabled: Boolean(runId),
    refetchInterval: query =>
      pollingInterval(query.state.data?.run?.STATUS, streamRef.current, visible)
  });

  const viewQuery = useQuery({
    queryKey: ['execution-view', runId],
    queryFn: ({ signal }) => runsApi.executionView(runId, { signal }),
    enabled: Boolean(runId),
    refetchInterval: query =>
      pollingInterval(query.state.data?.runStatus, streamRef.current, visible)
  });

  const serverStatus = viewQuery.data?.runStatus ?? runQuery.data?.run?.STATUS;
  const status = serverStatus ?? 'PENDING';
  // 服务端状态未知时不算「运行中」：否则首屏会为一个早已结束的运行建立 SSE。
  const live = serverStatus != null && isLiveStatus(serverStatus);
  const stream = useRunStream(runId, Boolean(runId) && live);

  useEffect(() => {
    // 供 refetchInterval 闭包读取最新连接状态；定时器在渲染之后才触发，读到的一定是当前值。
    streamRef.current = stream.connected;
  }, [stream.connected]);

  return {
    runQuery,
    viewQuery,
    detail: runQuery.data,
    view: viewQuery.data,
    status,
    live,
    streamConnected: stream.connected,
    streamText: stream.streamText,
    streamReasoning: stream.reasoningText,
    streamsByNode: stream.streamsByNode,
    streamNodeId: stream.streamNodeId,
    lastGateEvent: stream.lastGateEvent,
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
 * 标签页隐藏时停止轮询，切回后由 React Query 的窗口聚焦恢复机制继续增量拉取。
 */
export function useRunEvents(runId, enabled = true, live = false) {
  const queryClient = useQueryClient();
  const visible = useDocumentVisible();
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
    refetchInterval: live && visible ? RUN_POLL_INTERVAL_MS : false,
    staleTime: 0
  });
}

export function useNodeDetail(runId, nodeId, live = false, paused = false) {
  const visible = useDocumentVisible();
  return useQuery({
    queryKey: ['node-detail', runId, nodeId],
    queryFn: ({ signal }) => runsApi.nodeDetail(runId, nodeId, { signal }),
    enabled: Boolean(runId && nodeId),
    // 待审批的节点不会产生新记录，连接正常时也不需要重复拉取明细。
    refetchInterval: live && visible && !paused ? RUN_POLL_INTERVAL_MS : false
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
