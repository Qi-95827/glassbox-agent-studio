import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { absorbEventPage, useRunEvents } from './useRunExecution';
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
});
