import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
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
    expect(screen.getByText(/路径白名单与读写隔离 · DENY/)).toBeInTheDocument();
    expect(screen.getByText(/目标文件不在任务白名单中/)).toBeInTheDocument();
    expect(screen.getByText(/预算与防循环检查 · PASS/)).toBeInTheDocument();
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
  });
});
