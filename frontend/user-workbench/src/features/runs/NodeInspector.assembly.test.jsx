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
          // 第 2 次尝试没有装配快照：入口摘要必须回退到最近一次「有快照」的尝试 #1，并标明尝试号。
          assembly: null
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

    // 第 2 次尝试没有快照：必须显示最近一次有快照的 #1 授予的 1 项工具，并标明尝试号。
    expect(screen.getByText('已装配 1 个工具 · 尝试 #1')).toBeInTheDocument();
    expect(screen.queryByText('已装配 3 个工具 · 尝试 #2')).not.toBeInTheDocument();
  });
});
