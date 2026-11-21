import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import RunReplayPanel, { scopeEvents } from './RunReplayPanel';

describe('RunReplayPanel loading', () => {
  it('运行详情尚未返回时显示加载状态而不读取工具记录', () => {
    render(<RunReplayPanel view={{ runId: 'run-b' }} detail={undefined} events={[]} />);
    expect(screen.getByText('正在读取当前运行的执行记录…')).toBeInTheDocument();
  });

  it('详情已返回但事件尚未加载时显示事件加载状态', () => {
    render(
      <RunReplayPanel
        view={{ runId: 'run-b' }}
        detail={{ run: { ID: 'run-b' }, tools: [], models: [], agents: [] }}
        events={[]}
        eventsLoading
      />
    );
    expect(screen.getByText('正在读取当前运行的事件…')).toBeInTheDocument();
  });

  it('只展示当前运行的事件，残留的旧运行事件不会触发节点请求', () => {
    render(
      <RunReplayPanel
        view={{ runId: 'run-b' }}
        detail={{ run: { ID: 'run-b' }, tools: [], models: [], agents: [] }}
        events={[
          {
            ID: 1,
            RUN_ID: 'run-a',
            NODE_ID: 'node-of-run-a',
            EVENT_TYPE: 'HANDOFF_SENT',
            MESSAGE: '旧运行事件'
          }
        ]}
      />
    );
    expect(screen.getByText('当前运行没有已记录的事件。')).toBeInTheDocument();
    expect(screen.queryByText('旧运行事件')).not.toBeInTheDocument();
  });
});

describe('scopeEvents', () => {
  it('按运行标识过滤，历史事件缺少标识时保留', () => {
    expect(
      scopeEvents([{ ID: 1, RUN_ID: 'run-a' }, { ID: 2, RUN_ID: 'run-b' }, { ID: 3 }], 'run-b').map(
        item => item.ID
      )
    ).toEqual([2, 3]);
    expect(scopeEvents([{ ID: 1 }], '')).toEqual([]);
  });
});
