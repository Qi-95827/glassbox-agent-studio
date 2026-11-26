import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  absorbEventPage,
  pollingInterval,
  useRunEvents,
  useRunExecution,
  useRunStream
} from './useRunExecution';
import { RUN_POLL_INTERVAL_MS } from './useRunExecution';
import { runsApi } from '../api/runs';

const event = (id, seq = id, runId = 'run-a') => ({
  id,
  runId,
  runSeq: seq,
  type: 'MODEL_FINISHED',
  message: `event ${id}`,
  occurredAt: '2026-11-21T10:00:00Z'
});

function deferred() {
  let resolve;
  const promise = new Promise(resolver => {
    resolve = resolver;
  });
  return { promise, resolve };
}

describe('event cursor merge', () => {
  it('按 nextCursor 推进并映射 UI 字段', () => {
    const events = [];
    const known = new Set();
    const result = absorbEventPage(events, known, 0, {
      events: [event('a'), event('b')],
      nextCursor: 2
    });
    expect(result.cursor).toBe(2);
    expect(result.received).toBe(2);
    expect(events.map(item => item.ID)).toEqual(['a', 'b']);
    expect(events[0].EVENT_TYPE).toBe('MODEL_FINISHED');
    expect(events[0].RUN_ID).toBe('run-a');
    expect(events[0].CREATED_AT).toBe('2026-11-21T10:00:00Z');
  });

  it('重复页不会重复追加事件', () => {
    const events = [];
    const known = new Set();
    absorbEventPage(events, known, 0, { events: [event('a')], nextCursor: 1 });
    absorbEventPage(events, known, 1, { events: [event('a')], nextCursor: 1 });
    expect(events).toHaveLength(1);
  });

  it('缺少 nextCursor 时保留原游标', () => {
    const result = absorbEventPage([], new Set(), 7, { events: [] });
    expect(result.cursor).toBe(7);
  });
});

describe('useRunEvents', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('切换运行时旧响应晚到不会写入新运行', async () => {
    const requests = [];
    vi.spyOn(runsApi, 'eventsPage').mockImplementation((runId, after, limit, options) => {
      const pending = deferred();
      requests.push({ runId, after, options, pending });
      return pending.promise;
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } }
    });
    const wrapper = ({ children }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    const { result, rerender } = renderHook(({ runId }) => useRunEvents(runId, true, false), {
      wrapper,
      initialProps: { runId: 'run-a' }
    });

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].options.signal).toBeDefined();

    rerender({ runId: 'run-b' });
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1].runId).toBe('run-b');

    requests[1].pending.resolve({ events: [event('b')], nextCursor: 1 });
    await waitFor(() => expect(result.current.data?.map(item => item.ID)).toEqual(['b']));

    requests[0].pending.resolve({ events: [event('a')], nextCursor: 1 });
    await new Promise(resolve => setTimeout(resolve, 60));
    expect(result.current.data?.map(item => item.ID)).toEqual(['b']);
  });

  it('分页拉取中途取消不会污染缓存数组', async () => {
    const firstPage = Array.from({ length: 200 }, (_, index) => event(`e-${index}`, index + 1));
    const secondPagePending = deferred();
    let calls = 0;
    vi.spyOn(runsApi, 'eventsPage').mockImplementation(() => {
      calls += 1;
      if (calls === 1) return Promise.resolve({ events: firstPage, nextCursor: 200 });
      return secondPagePending.promise;
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } }
    });
    const wrapper = ({ children }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    const { rerender } = renderHook(({ runId }) => useRunEvents(runId, true, false), {
      wrapper,
      initialProps: { runId: 'run-a' }
    });

    await waitFor(() => expect(calls).toBe(2));
    rerender({ runId: 'run-b' });
    secondPagePending.resolve({ events: [event('e-200', 201)], nextCursor: 201 });
    await new Promise(resolve => setTimeout(resolve, 40));

    expect(client.getQueryData(['run-events', 'run-a'])).toBeUndefined();
  });
});

describe('pollingInterval', () => {
  it('终态运行不再轮询', () => {
    expect(pollingInterval('COMPLETED', false, true)).toBe(false);
    expect(pollingInterval('FAILED', false, true)).toBe(false);
    expect(pollingInterval('CANCELLED', false, true)).toBe(false);
  });

  it('SSE 连通期间暂停重复的 REST 轮询', () => {
    expect(pollingInterval('RUNNING', true, true)).toBe(false);
  });

  it('标签页不可见时不轮询，即使运行仍在进行', () => {
    expect(pollingInterval('RUNNING', false, false)).toBe(false);
    expect(pollingInterval('PAUSED', false, false)).toBe(false);
  });

  it('运行中、未连通且可见时按统一间隔兜底', () => {
    expect(pollingInterval('RUNNING', false, true)).toBe(RUN_POLL_INTERVAL_MS);
    expect(pollingInterval('PENDING', false, true)).toBe(RUN_POLL_INTERVAL_MS);
  });

  it('服务端状态未知时不启动轮询定时器', () => {
    expect(pollingInterval(undefined, false, true)).toBe(false);
  });
});

class FakeEventSource {
  static instances = [];
  constructor(url) {
    this.url = url;
    this.listeners = {};
    this.closed = false;
    FakeEventSource.instances.push(this);
  }
  addEventListener(name, callback) {
    this.listeners[name] = callback;
  }
  close() {
    this.closed = true;
  }
}

const makeWrapper = client =>
  function Wrapper({ children }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };

describe('useRunExecution 的流与轮询协同', () => {
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('终态运行不创建 EventSource', async () => {
    vi.spyOn(runsApi, 'get').mockResolvedValue({ run: { STATUS: 'COMPLETED' } });
    vi.spyOn(runsApi, 'executionView').mockResolvedValue({
      runId: 'run-done',
      runStatus: 'COMPLETED',
      nodes: []
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useRunExecution('run-done'), {
      wrapper: makeWrapper(client)
    });

    await waitFor(() => expect(result.current.status).toBe('COMPLETED'));
    // 再等一轮微任务，确认没有任何迟到的连接被创建。
    await new Promise(resolve => setTimeout(resolve, 40));
    expect(FakeEventSource.instances).toHaveLength(0);
    expect(result.current.streamConnected).toBe(false);
    expect(result.current.live).toBe(false);
  });

  it('运行中的运行建立 EventSource，并在连接后停止轮询', async () => {
    vi.spyOn(runsApi, 'get').mockResolvedValue({ run: { STATUS: 'RUNNING' } });
    vi.spyOn(runsApi, 'executionView').mockResolvedValue({
      runId: 'run-live',
      runStatus: 'RUNNING',
      nodes: []
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useRunExecution('run-live'), {
      wrapper: makeWrapper(client)
    });

    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));
    expect(FakeEventSource.instances[0].url).toBe('/api/runs/run-live/stream');
    expect(result.current.live).toBe(true);

    act(() => FakeEventSource.instances[0].onopen());
    await waitFor(() => expect(result.current.streamConnected).toBe(true));
    // 连接建立后轮询判据必须为 false，否则会出现 SSE 与 REST 双通道重复刷新。
    expect(pollingInterval(result.current.status, result.current.streamConnected, true)).toBe(
      false
    );
  });

  it('高频 RUN_EVENT 在防抖窗口内合并为一次缓存失效', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(() => useRunStream('run-burst', true), {
      wrapper: makeWrapper(client)
    });
    const source = FakeEventSource.instances[0];
    act(() => source.onopen());
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => {
      for (let index = 0; index < 8; index += 1) source.listeners.RUN_EVENT({ data: '{}' });
    });
    // 一次 flush 固定失效 3 个查询键；8 个事件若逐个失效会是 24 次。
    await waitFor(() => expect(invalidate).toHaveBeenCalled());
    await new Promise(resolve => setTimeout(resolve, 420));
    expect(invalidate).toHaveBeenCalledTimes(3);
  });

  it('推理增量与正文增量走各自独立的通道', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useRunStream('run-think', true), {
      wrapper: makeWrapper(client)
    });
    const source = FakeEventSource.instances[0];
    act(() => source.onopen());
    act(() => source.listeners.MODEL_REASONING_DELTA({ data: '{"text":"先看文件"}' }));
    act(() => source.listeners.MODEL_DELTA({ data: '{"text":"结论如下"}' }));

    await waitFor(() => expect(result.current.reasoningText).toBe('先看文件'));
    expect(result.current.streamText).toBe('结论如下');
  });
});
describe('useRunStream', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('连接成功后接收 MODEL_DELTA 瞬态片段', async () => {
    class FakeEventSource {
      static instances = [];
      constructor(url) {
        this.url = url;
        this.listeners = {};
        this.closed = false;
        FakeEventSource.instances.push(this);
      }
      addEventListener(name, callback) {
        this.listeners[name] = callback;
      }
      close() {
        this.closed = true;
      }
    }
    vi.stubGlobal('EventSource', FakeEventSource);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    const { result } = renderHook(() => useRunStream('run-1', true), { wrapper });
    const source = FakeEventSource.instances[0];
    expect(source.url).toBe('/api/runs/run-1/stream');
    act(() => source.onopen());
    await waitFor(() => expect(result.current.connected).toBe(true));
    act(() => source.listeners.MODEL_DELTA({ data: '{"text":"实时片段"}' }));
    await waitFor(() => expect(result.current.streamText).toBe('实时片段'));
  });
});
