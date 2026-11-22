import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ToolApprovalDrawer from './ToolApprovalDrawer';
import { runsApi } from '../../api/runs';

const pending = {
  id: 'approval-1',
  runId: 'run-1',
  nodeId: 'node-1',
  toolName: 'write_authorized_file',
  originalArgsJson: '{"path":"pom.xml"}',
  riskLevel: 'HIGH',
  riskReason: '命中敏感配置路径：pom.xml',
  status: 'PENDING',
  expiresAt: '2026-11-22T12:05:00Z'
};

describe('ToolApprovalDrawer', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('展示待审批工具与风险，拒绝必须填写理由', async () => {
    vi.spyOn(runsApi, 'toolApprovals').mockResolvedValue([pending]);
    const decide = vi.spyOn(runsApi, 'decideToolApproval').mockResolvedValue({});
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <ToolApprovalDrawer runId="run-1" live={false} />
      </QueryClientProvider>
    );
    expect(await screen.findByText('write_authorized_file')).toBeInTheDocument();
    expect(screen.getByText(/命中敏感配置路径：pom.xml/)).toBeInTheDocument();
    const reject = screen.getByRole('button', { name: '拒绝并回传理由' });
    expect(reject).toBeDisabled();
    fireEvent.change(screen.getByLabelText('决策理由（拒绝时必填，会回传模型）'), {
      target: { value: '不要修改构建脚本' }
    });
    expect(reject).not.toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '批准放行' }));
    await waitFor(() =>
      expect(decide).toHaveBeenCalledWith(
        'run-1',
        'approval-1',
        expect.objectContaining({ action: 'APPROVE' })
      )
    );
  });
});
