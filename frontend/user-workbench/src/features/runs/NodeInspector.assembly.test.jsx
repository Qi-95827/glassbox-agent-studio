import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { setModuleLocale } from '../../i18n';
import NodeInspector from './NodeInspector';

vi.mock('./NodeGlassboxDialog', () => ({ default: () => null }));

describe('NodeInspector 装配入口摘要口径', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
  });

  it('多轮尝试按 attemptNo 升序传入时，入口摘要读取最新一次装配快照的工具数而非第 1 次', () => {
    const node = {
      id: 'write',
      label: '按授权修改',
      role: 'CODER',
      status: 'COMPLETED',
      kind: 'AGENT'
    };
    const detail = {
      result: '第二轮修改完成',
      attempts: [
        {
          number: 1,
          models: [],
          tools: [],
          assembly: {
            components: {
              model: { mode: 'MOCK', name: 'mock' },
              tools: { WORKSPACE_READ: 1 }
            }
          }
        },
        {
          number: 2,
          models: [],
          tools: [],
          assembly: {
            components: {
              model: { mode: 'DEEPSEEK', name: 'deepseek-chat' },
              tools: { WORKSPACE_READ: 1, WORKSPACE_SEARCH: 1, WORKSPACE_WRITE: 2 }
            }
          }
        }
      ],
      checks: [],
      inboundHandoffs: [],
      outboundHandoffs: [],
      checkReceipts: [],
      dependencies: []
    };

    render(
      <NodeInspector
        runId="r1"
        taskId="t1"
        node={node}
        detail={detail}
        loading={false}
        error={null}
      />
    );

    // 必须显示最新第 2 次尝试授予的 3 项工具，而不是第 1 次尝试的 1 项工具。
    expect(screen.getByText('放行工具 3 项')).toBeInTheDocument();
    expect(screen.queryByText('放行工具 1 项')).not.toBeInTheDocument();
  });
});
