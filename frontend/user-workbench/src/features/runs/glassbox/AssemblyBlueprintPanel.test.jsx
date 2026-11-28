import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import AssemblyBlueprintPanel from './AssemblyBlueprintPanel';
import { setModuleLocale } from '../../../i18n';

const node = {
  id: 'write',
  label: '按授权修改',
  role: 'CODER',
  workspaceRead: true,
  workspaceWrite: false,
  definitionId: 'workspace-edit',
  definitionVersion: 1
};

const attempts = [
  {
    number: 1,
    assembly: {
      components: {
        model: { mode: 'MOCK', name: 'mock' },
        tools: { SIMULATED_INSPECTION: 2, WORKSPACE_SEARCH: 1 },
        input: { sources: [] },
        memoryMatches: []
      }
    }
  }
];

describe('AssemblyBlueprintPanel 工具漏斗', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
  });

  it('以服务端真实漏斗展示已授予与被裁剪工具，不再出现不存在的 SIMULATED_ISSUE', () => {
    render(
      <AssemblyBlueprintPanel
        node={node}
        attempts={attempts}
        toolFunnel={{
          definitionId: 'workspace-edit',
          definitionVersion: 1,
          attemptNumber: 1,
          granted: [
            { kind: 'SIMULATED_INSPECTION', version: 2 },
            { kind: 'WORKSPACE_SEARCH', version: 1 }
          ],
          filtered: [
            { kind: 'WORKSPACE_WRITE', reason: 'NODE_NOT_WRITE_AUTHORIZED' },
            { kind: 'WORKSPACE_READ', reason: 'RUN_POLICY_FILTERED' }
          ]
        }}
      />
    );

    expect(screen.getByTestId('assembly-funnel-attempt')).toHaveTextContent('尝试 #1');

    expect(screen.getAllByText(/模拟检查工具/).length).toBeGreaterThan(0);
    // 服务端真实裁剪原因按编码翻译，而不是前端按节点字段瞎猜。
    const write = screen.getByTestId('assembly-filtered-WORKSPACE_WRITE');
    expect(write).toHaveTextContent('修改授权文件');
    expect(write).toHaveTextContent('节点未声明写入授权');
    expect(screen.getByTestId('assembly-filtered-WORKSPACE_READ')).toHaveTextContent(
      '平台按角色与运行策略裁剪'
    );
    // 错误枚举彻底消失。
    expect(screen.queryByText(/SIMULATED_ISSUE/)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('SIMULATED_ISSUE');
  });

  it('服务端未给出漏斗时只说明缺少装配证据，不伪造裁剪行', () => {
    render(<AssemblyBlueprintPanel node={node} attempts={attempts} toolFunnel={null} />);

    expect(screen.getByTestId('assembly-filtered-unknown')).toHaveTextContent(
      '服务端未返回工具漏斗明细'
    );
    expect(screen.queryByTestId('assembly-filtered-WORKSPACE_WRITE')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('SIMULATED_ISSUE');
  });

  it('多轮尝试按 attemptNo 升序传入时取最新一次装配快照而非第 1 次', () => {
    const multiAttempts = [
      {
        number: 1,
        assembly: {
          components: {
            model: { mode: 'MOCK', name: 'mock' },
            tools: { WORKSPACE_READ: 1 },
            input: { sources: [{ kind: 'TASK_GOAL', reference: 'round-1' }] },
            memoryMatches: []
          }
        }
      },
      {
        number: 2,
        assembly: {
          components: {
            model: { mode: 'DEEPSEEK', name: 'deepseek-chat' },
            tools: { WORKSPACE_READ: 1, WORKSPACE_WRITE: 2 },
            input: { sources: [{ kind: 'REVISION_FEEDBACK', reference: 'round-2' }] },
            memoryMatches: []
          }
        }
      }
    ];
    render(<AssemblyBlueprintPanel node={node} attempts={multiAttempts} toolFunnel={null} />);

    expect(screen.getAllByText(/round-2/).length).toBeGreaterThan(0);
    expect(screen.getByTestId('assembly-funnel-attempt')).toHaveTextContent('尝试 #2');
    expect(screen.queryByText(/round-1/)).not.toBeInTheDocument();
  });

  const mcpFunnel = {
    definitionId: 'workspace-edit',
    definitionVersion: 1,
    attemptNumber: 1,
    granted: [],
    filtered: [{ kind: 'MCP', reason: 'MCP_TOOLS_DENIED_BY_ROLE' }],
    mcpDecisions: [
      { exposedName: 'mcp::notes::search', riskLevel: 'LOW', allowed: true, reasonCode: '' },
      {
        exposedName: 'mcp::notes::create',
        riskLevel: 'HIGH',
        allowed: false,
        reasonCode: 'ANALYST_HIGH_RISK_MCP_FORBIDDEN'
      },
      {
        exposedName: 'mcp::notes::remove',
        riskLevel: 'HIGH',
        allowed: false,
        reasonCode: 'REVIEWER_MCP_FORBIDDEN'
      },
      {
        exposedName: 'mcp::notes::dup',
        riskLevel: 'LOW',
        allowed: false,
        reasonCode: 'DUPLICATE_TOOL_NAME'
      }
    ]
  };

  it('展示外部 MCP 工具逐项准入明细与原因编码翻译', () => {
    render(<AssemblyBlueprintPanel node={node} attempts={attempts} toolFunnel={mcpFunnel} />);

    expect(screen.getByTestId('assembly-mcp-decisions')).toBeInTheDocument();
    expect(screen.getByTestId('assembly-filtered-MCP')).toHaveTextContent(
      '外部 MCP 工具被角色策略剥离'
    );

    const admitted = screen.getByTestId('assembly-mcp-mcp::notes::search');
    expect(admitted).toHaveTextContent('mcp::notes::search');
    expect(admitted).toHaveTextContent('低风险');
    expect(admitted).toHaveTextContent('已放行');
    expect(admitted).toHaveTextContent('已注册给本节点');

    const highRisk = screen.getByTestId('assembly-mcp-mcp::notes::create');
    expect(highRisk).toHaveTextContent('高风险');
    expect(highRisk).toHaveTextContent('已剥离');
    expect(highRisk).toHaveTextContent('分析角色只能使用低风险只读工具');

    expect(screen.getByTestId('assembly-mcp-mcp::notes::remove')).toHaveTextContent(
      '评审角色禁止使用外部 MCP 工具'
    );
    expect(screen.getByTestId('assembly-mcp-mcp::notes::dup')).toHaveTextContent(
      '与已注册工具同名，已跳过'
    );
  });

  it('英文环境下逐项准入明细同样按编码翻译', () => {
    setModuleLocale('en-US');
    render(<AssemblyBlueprintPanel node={node} attempts={attempts} toolFunnel={mcpFunnel} />);

    const admitted = screen.getByTestId('assembly-mcp-mcp::notes::search');
    expect(admitted).toHaveTextContent('Low risk');
    expect(admitted).toHaveTextContent('Admitted');

    const rejected = screen.getByTestId('assembly-mcp-mcp::notes::create');
    expect(rejected).toHaveTextContent('High risk');
    expect(rejected).toHaveTextContent('Trimmed');
    expect(rejected).toHaveTextContent('Analyst role may only use low-risk read-only tools');
    expect(screen.getByTestId('assembly-filtered-MCP')).toHaveTextContent(
      'External MCP tools were trimmed by role policy'
    );
  });

  it('没有 MCP 准入决策时不渲染冗余空块', () => {
    render(<AssemblyBlueprintPanel node={node} attempts={attempts} toolFunnel={null} />);

    expect(screen.queryByTestId('assembly-mcp-decisions')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('外部 MCP 工具逐项准入');
  });

  it('服务端未给漏斗时回退到装配快照里的逐工具准入字段', () => {
    const withSnapshotDecisions = [
      {
        number: 1,
        assembly: {
          components: {
            model: { mode: 'MOCK', name: 'mock' },
            tools: {},
            input: { sources: [] },
            memoryMatches: [],
            mcpToolDecisions: [
              {
                exposedName: 'mcp::notes::search',
                riskLevel: 'LOW',
                allowed: true,
                reasonCode: ''
              }
            ]
          }
        }
      }
    ];
    render(
      <AssemblyBlueprintPanel node={node} attempts={withSnapshotDecisions} toolFunnel={null} />
    );

    expect(screen.getByTestId('assembly-mcp-mcp::notes::search')).toHaveTextContent('已放行');
  });
});
