import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nProvider } from '../../i18n/I18nProvider';
import EvalCaseDetailDrawer from './EvalCaseDetailDrawer';

const item = {
  id: 'case-1',
  category: 'TOOL_MCP',
  sampleId: 'tool-01',
  titleZh: '授权读取',
  titleEn: 'Authorized read',
  repeatIndex: 1,
  passed: true,
  codeScore: 1,
  costUsd: 0,
  durationMs: 120,
  linkedRunId: 'run-linked-1',
  rawOutput: 'read ok',
  metrics: { selectionAccuracy: 1, gatewayPassRate: 1 },
  assertions: [
    { type: 'TOOL_CALLED', expected: 'workspace_read', actual: ['workspace_read'], passed: true }
  ],
  judgeVerdict: { mode: 'DISABLED' },
  trace: {
    prompt: '读取授权的 src/app.js',
    setup: { authorizedPaths: ['src/app.js'] },
    toolCalls: [
      {
        name: 'workspace_read',
        status: 'SUCCEEDED',
        path: 'src/app.js',
        checks: [
          {
            gate: 'PATH_WHITELIST',
            label: '路径白名单与读写隔离',
            status: 'PASS',
            detail: '目标路径命中只读授权范围'
          }
        ]
      }
    ]
  }
};

describe('评测题玻璃盒穿透抽屉', () => {
  it('展示题目、断言、安检门并支持深链跳运行玻璃盒', async () => {
    const onOpenRun = vi.fn();
    render(
      <I18nProvider initialLocale="zh-CN">
        <EvalCaseDetailDrawer
          open
          item={item}
          categoryLabel={{ TOOL_MCP: '工具 / Tools' }}
          onClose={() => {}}
          onOpenRun={onOpenRun}
        />
      </I18nProvider>
    );

    expect(screen.getByText('授权读取')).toBeInTheDocument();
    expect(screen.getByText(/工具 \/ Tools/)).toBeInTheDocument();
    expect(screen.getByText('TOOL_CALLED')).toBeInTheDocument();
    expect(screen.getByText(/目标路径命中只读授权范围/)).toBeInTheDocument();
    expect(screen.getByText(/read ok/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /在运行玻璃盒中打开/ }));
    expect(onOpenRun).toHaveBeenCalledWith('run-linked-1');
  });
});
