import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import RunDiagnosticsDrawer from './RunDiagnosticsDrawer';

const navigate = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => navigate };
});

const diagnosticsData = {
  runId: 'run-1',
  taskId: 'task-1',
  taskTitle: '教学剧本 3：ToolGateway 限额与安检演示',
  workspaceId: null,
  runStatus: 'BLOCKED',
  taskStatus: 'BLOCKED',
  healthLevel: 'CRITICAL',
  primaryIssue:
    '节点「实现单文件番茄钟」在 PATH_WHITELIST（路径白名单与读写隔离）安检门被拦截 2 次，导致未能写入目标文件。',
  troubleshootingSteps: [
    {
      stepOrder: 1,
      title: '步骤 1：核对失败/阻塞节点与终态原因',
      status: 'ISSUE_FOUND',
      finding: '运行终态 BLOCKED；异常节点：实现单文件番茄钟。',
      actionLabel: '查看该节点结论',
      targetRoute: '/runs/run-1?nodeId=node-1&glassbox=result'
    },
    {
      stepOrder: 2,
      title: '步骤 2：核查 ToolGateway 安全门与工具调用',
      status: 'ISSUE_FOUND',
      finding:
        '节点「实现单文件番茄钟」的 WORKSPACE_WRITE 调用在 PATH_WHITELIST 安检门被拦截 2 次。',
      actionLabel: '打开该节点的工具安检门',
      targetRoute: '/runs/run-1?nodeId=node-1&glassbox=gateway'
    },
    {
      stepOrder: 3,
      title: '步骤 3：核查模型 I/O、Token 与限流/超时',
      status: 'LOOKS_OK',
      finding: '共 1 次模型调用，均正常返回。',
      actionLabel: '查看模型 I/O 明细',
      targetRoute: '/runs/run-1?nodeId=node-1&glassbox=llm'
    },
    {
      stepOrder: 4,
      title: '步骤 4：核查上游交接完整性与装配权限漏斗',
      status: 'CHECK_RECOMMENDED',
      finding: '该运行包含 2 个节点，但没有交接记录。',
      actionLabel: '查看节点装配与交接',
      targetRoute: '/runs/run-1'
    },
    {
      stepOrder: 5,
      title: '步骤 5：核查工作区差异与 Git 交付状态',
      status: 'LOOKS_OK',
      finding: '该任务没有绑定工作区。',
      actionLabel: '查看运行交付说明',
      targetRoute: '/runs/run-1'
    }
  ],
  generatedAt: '2026-11-22T10:00:00Z'
};

vi.mock('../../hooks/useEvaluations', () => ({
  useRunDiagnostics: () => ({
    isLoading: false,
    isError: false,
    data: diagnosticsData
  })
}));

describe('快捷故障排查向导', () => {
  beforeEach(() => {
    navigate.mockClear();
  });

  it('展示健康等级、核心定位并自动展开命中的问题步骤', () => {
    render(<RunDiagnosticsDrawer open runId="run-1" onClose={() => {}} />);

    expect(screen.getByText('严重异常')).toBeInTheDocument();
    expect(screen.getByText('教学剧本 3：ToolGateway 限额与安检演示')).toBeInTheDocument();
    expect(screen.getAllByText(/PATH_WHITELIST/).length).toBeGreaterThan(0);
    expect(screen.getAllByText('发现问题').length).toBe(2);
    expect(screen.getByText('建议核查')).toBeInTheDocument();
    expect(screen.getAllByText('正常').length).toBe(2);
    expect(screen.getByText(/异常节点：实现单文件番茄钟/)).toBeInTheDocument();
  });

  it('点击步骤快捷按钮直接导航到目标节点玻璃盒 Tab 并关闭抽屉', () => {
    const onClose = vi.fn();
    render(<RunDiagnosticsDrawer open runId="run-1" onClose={onClose} />);

    fireEvent.click(screen.getByRole('button', { name: /打开该节点的工具安检门/ }));

    expect(onClose).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith('/runs/run-1?nodeId=node-1&glassbox=gateway');
  });
});
