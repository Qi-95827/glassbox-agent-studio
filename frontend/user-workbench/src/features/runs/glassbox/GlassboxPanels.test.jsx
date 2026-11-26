import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import ToolGatewayPanel from './ToolGatewayPanel';
import LlmTracePanel from './LlmTracePanel';
import HandoffEnvelopePanel from './HandoffEnvelopePanel';

describe('Glassbox inspector panels', () => {
  it('展示 ToolGateway 入参、6 道安检门状态与拦截原因', () => {
    render(
      <ToolGatewayPanel
        tools={[
          {
            id: 'tc-1',
            name: 'WORKSPACE_WRITE',
            version: 1,
            status: 'FAILED',
            durationMs: 12,
            trace: {
              inputArgs: '{"path":"secret.env"}',
              gatewayChecks: [
                {
                  gate: 'PATH_WHITELIST',
                  label: '路径白名单与读写隔离',
                  status: 'DENY',
                  detail: '目标文件不在任务白名单中'
                },
                {
                  gate: 'BUDGET_AND_LOOP',
                  label: '预算与防循环检查',
                  status: 'PASS',
                  detail: '未超限'
                }
              ],
              fullResult: '{"status":"REJECTED"}'
            }
          }
        ]}
      />
    );
    // 横向流水线按服务端记录顺序渲染关卡，状态与判定原文都必须可见。
    const pipeline = screen.getByTestId('gate-pipeline');
    expect(pipeline).toBeInTheDocument();
    const stages = screen.getAllByTestId(/^gate-stage-/);
    expect(stages.map(node => node.getAttribute('data-status'))).toEqual(['DENY', 'PASS']);
    expect(screen.getByTestId('gate-stage-PATH_WHITELIST')).toBeInTheDocument();
    // 缺省展开被拦截的那道门，直接给出拦截原因。
    expect(screen.getByTestId('gate-detail')).toHaveTextContent('目标文件不在任务白名单中');
    // 关卡名按 gate 编码本地化渲染（后端 label 是中文历史文案，英文界面不能直接复用）。
    expect(screen.getAllByText(/路径白名单/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/预算与循环保护/).length).toBeGreaterThan(0);
  });

  it('展示模型调用的 Messages 与 tool_calls 决策', () => {
    render(
      <LlmTracePanel
        models={[
          {
            id: 'mc-1',
            name: 'mock',
            durationMs: 25,
            inputTokens: 120,
            outputTokens: 45,
            payload: {
              requestMessages: [
                { role: 'SYSTEM', name: 'analyst', content: '你是分析专家', charCount: 6 }
              ],
              requestTools: [],
              responseContent: '准备搜索代码',
              responseToolCalls: [
                { id: 'call-1', name: 'workspace_search', argumentsJson: '{"query":"sort"}' }
              ],
              truncated: false
            }
          }
        ]}
      />
    );
    expect(screen.getByText(/tool_call: workspace_search/)).toBeInTheDocument();
    expect(screen.getByText(/准备搜索代码/)).toBeInTheDocument();
  });

  it('展示结构化交接信封的结论、产物与校验清单', () => {
    render(
      <HandoffEnvelopePanel
        inbound={[
          {
            senderNodeLabel: '分析节点',
            receiverNodeLabel: '开发节点',
            senderAgentId: 'aaaa1111',
            receiverAgentId: 'bbbb2222',
            senderAttemptId: 'cccc3333',
            receiverAttemptId: 'dddd4444',
            dependencyNodeId: 'node-1',
            acceptedAt: '2026-11-21T10:00:00Z',
            sourceResult: '完成分析',
            toolEvidence: [],
            structured: {
              summary: '确定采用模块化 sort.js 实现',
              decisions: ['拆分为算法与视图两个文件'],
              artifacts: ['sort.js'],
              openRisks: ['大数据量动画帧率待验证'],
              validationChecks: [
                { gate: 'DEPENDENCY_MATCH', label: '直接上游依赖匹配', status: 'PASS', detail: '' }
              ]
            }
          }
        ]}
      />
    );
    expect(screen.getByText('确定采用模块化 sort.js 实现')).toBeInTheDocument();
    expect(screen.getByText('sort.js')).toBeInTheDocument();
    expect(screen.getByText(/直接上游依赖匹配 · PASS/)).toBeInTheDocument();
    expect(screen.getByText(/命中当前节点「开发节点」的 DAG 计划依赖边/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '查看怎么算 PASS' }));
    expect(screen.getByText(/交接记录携带有效的 dependencyNodeId/)).toBeInTheDocument();
  });

  it('自动过滤交接摘要中的纯 Markdown 标题并提取实质结论', () => {
    render(
      <HandoffEnvelopePanel
        inbound={[
          {
            senderNodeLabel: '梳理核心流程',
            receiverNodeLabel: '审查功能完整性',
            senderAgentId: 'aaaa1111',
            receiverAgentId: 'bbbb2222',
            senderAttemptId: 'cccc3333',
            receiverAttemptId: 'dddd4444',
            dependencyNodeId: 'node-2',
            acceptedAt: '2026-11-21T10:00:00Z',
            sourceResult:
              '## 一、已观察到的证据（本步实测）\n结论：本步无写入工具，先交付设计规格。',
            toolEvidence: [],
            structured: {
              summary: '## 一、已观察到的证据（本步实测）',
              decisions: ['### 2.2 计时实现要点'],
              artifacts: ['index.html'],
              openRisks: ['**结论**: 缺少写入权限'],
              validationChecks: []
            }
          }
        ]}
      />
    );
    expect(screen.getAllByText('结论：本步无写入工具，先交付设计规格。').length).toBeGreaterThan(0);
    expect(screen.getByText('2.2 计时实现要点')).toBeInTheDocument();
  });
});
