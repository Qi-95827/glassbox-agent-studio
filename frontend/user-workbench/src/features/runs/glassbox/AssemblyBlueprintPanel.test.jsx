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
    expect(screen.getByText('尝试 #2')).toBeInTheDocument();
    expect(screen.queryByText(/round-1/)).not.toBeInTheDocument();
  });
});
