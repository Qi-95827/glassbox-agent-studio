import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import ReActSwimlane, { buildReActTurns } from './ReActSwimlane';
import { setModuleLocale } from '../../../i18n';

const attempts = [
  {
    number: 1,
    models: [
      {
        id: 'm1',
        name: 'deepseek-flash',
        status: 'COMPLETED',
        durationMs: 1200,
        createdAt: '2026-11-26T10:00:00Z',
        payload: {
          responseContent: '先读取授权文件。',
          reasoningContent: '我需要确认读取范围。',
          streamed: true
        }
      },
      {
        id: 'm2',
        name: 'deepseek-flash',
        status: 'COMPLETED',
        durationMs: 900,
        createdAt: '2026-11-26T10:00:10Z',
        payload: { responseContent: '结论：可以修改。', reasoningContent: '', streamed: false }
      }
    ],
    tools: [
      {
        id: 't1',
        name: 'read_authorized_file',
        version: 1,
        status: 'SUCCEEDED',
        durationMs: 30,
        createdAt: '2026-11-26T10:00:05Z',
        trace: {
          inputArgs: '{"path":"src/App.jsx"}',
          fullResult: 'export default function App(){}',
          gatewayChecks: [
            { gate: 'OWNERSHIP_AND_SCOPE', label: '身份与归属鉴权', status: 'PASS', detail: 'ok' },
            { gate: 'PATH_WHITELIST', label: '路径白名单', status: 'PASS', detail: '命中只读授权' }
          ]
        }
      }
    ]
  }
];

describe('buildReActTurns', () => {
  it('每次模型调用开启一个 Turn，随后的工具调用归入该 Turn', () => {
    const turns = buildReActTurns(attempts);
    expect(turns).toHaveLength(2);
    expect(turns[0].model?.id).toBe('m1');
    expect(turns[0].tools.map(call => call.id)).toEqual(['t1']);
    expect(turns[1].model?.id).toBe('m2');
    expect(turns[1].tools).toEqual([]);
  });

  it('同一 Turn 内可以连续调用多个工具', () => {
    const turns = buildReActTurns([
      {
        number: 1,
        models: [{ id: 'm1', createdAt: '2026-11-26T10:00:00Z' }],
        tools: [
          { id: 't1', createdAt: '2026-11-26T10:00:01Z' },
          { id: 't2', createdAt: '2026-11-26T10:00:02Z' }
        ]
      }
    ]);
    expect(turns).toHaveLength(1);
    expect(turns[0].tools.map(call => call.id)).toEqual(['t1', 't2']);
  });

  it('先有工具记录、无模型记录的历史尝试单独成轮而不是被丢弃', () => {
    const turns = buildReActTurns([
      { number: 2, models: [], tools: [{ id: 't9', createdAt: '2026-11-26T10:00:00Z' }] }
    ]);
    expect(turns).toHaveLength(1);
    expect(turns[0].model).toBeNull();
    expect(turns[0].tools.map(call => call.id)).toEqual(['t9']);
  });

  it('多尝试按各自时间累积成连续编号', () => {
    const turns = buildReActTurns([
      { number: 1, models: [{ id: 'a', createdAt: '2026-11-26T10:00:00Z' }], tools: [] },
      { number: 2, models: [{ id: 'b', createdAt: '2026-11-26T10:05:00Z' }], tools: [] }
    ]);
    expect(turns.map(turn => turn.number)).toEqual([1, 2]);
  });
});

describe('ReActSwimlane', () => {
  it('按 Turn 渲染模型输出、思考链与工具安检流水线', () => {
    setModuleLocale('zh-CN');
    render(<ReActSwimlane attempts={attempts} />);

    expect(screen.getByTestId('react-swimlane')).toBeInTheDocument();
    expect(screen.getAllByTestId('swimlane-turn')).toHaveLength(2);
    expect(screen.getByText('先读取授权文件。')).toBeInTheDocument();
    expect(screen.getByText('结论：可以修改。')).toBeInTheDocument();
    expect(screen.getByTestId('swimlane-tool')).toBeInTheDocument();
    expect(screen.getByTestId('gate-pipeline')).toBeInTheDocument();
  });

  it('思考链默认折叠，点击后才展开', () => {
    setModuleLocale('zh-CN');
    render(<ReActSwimlane attempts={attempts} />);

    expect(screen.queryByText('我需要确认读取范围。')).toBeNull();
    fireEvent.click(screen.getByTestId('swimlane-reasoning-toggle'));
    expect(screen.getByText('我需要确认读取范围。')).toBeInTheDocument();
  });

  it('只有流式完成的调用才标记流式增量', () => {
    setModuleLocale('zh-CN');
    const { container } = render(<ReActSwimlane attempts={attempts} />);
    const badges = Array.from(container.querySelectorAll('*')).filter(
      node => node.textContent === '流式增量' && node.children.length === 0
    );
    // 两次模型调用中只有第一次带 payload.streamed = true。
    expect(badges).toHaveLength(1);
  });

  it('没有记录时显示空状态并说明原因', () => {
    setModuleLocale('zh-CN');
    render(<ReActSwimlane attempts={[]} />);
    expect(screen.getByText('暂无 ReAct 轮次')).toBeInTheDocument();
  });

  it('运行中时在泳道底部渲染实时正文、思考链与当前关卡', () => {
    setModuleLocale('zh-CN');
    render(
      <ReActSwimlane
        attempts={[]}
        liveStream={{
          content: '正在生成的正文',
          reasoning: '正在推理',
          gate: { gate: 'PATH_WHITELIST', label: '路径白名单', status: 'PASS', tool: 'read_file' }
        }}
      />
    );

    expect(screen.getByTestId('swimlane-live')).toBeInTheDocument();
    expect(screen.getByTestId('swimlane-live-content')).toHaveTextContent('正在生成的正文');
    expect(screen.getByText(/正在过检：路径白名单 · PASS/)).toBeInTheDocument();
    // 实时思考链同样默认折叠。
    expect(screen.queryByTestId('swimlane-live-reasoning')).toBeNull();
    fireEvent.click(screen.getByTestId('swimlane-live-reasoning-toggle'));
    expect(screen.getByTestId('swimlane-live-reasoning')).toHaveTextContent('正在推理');
  });

  it('只有通道没有增量时给出等待提示而不是伪造内容', () => {
    setModuleLocale('zh-CN');
    render(
      <ReActSwimlane
        attempts={[]}
        liveStream={{ content: '', reasoning: '', gate: { gate: 'BUDGET_AND_LOOP' } }}
      />
    );
    expect(screen.getByText('已建立实时通道，等待模型首个增量…')).toBeInTheDocument();
    expect(screen.queryByTestId('swimlane-live-content')).toBeNull();
  });

  it('既无落库轮次也无实时增量时不渲染实时块', () => {
    setModuleLocale('zh-CN');
    render(<ReActSwimlane attempts={[]} liveStream={null} />);
    expect(screen.queryByTestId('swimlane-live')).toBeNull();
    expect(screen.getByText('暂无 ReAct 轮次')).toBeInTheDocument();
  });
});
