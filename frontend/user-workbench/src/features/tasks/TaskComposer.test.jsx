import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ToastProvider } from '../../components/ToastProvider';
import TaskComposer from './TaskComposer';

function json(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body)
  };
}

describe('TaskComposer', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async url => {
        if (url === '/api/workspaces') return json(200, []);
        if (url === '/api/entry-policies') return json(200, []);
        if (url === '/api/entry-routes/preview') {
          return json(200, {
            ruleCode: 'DEFAULT',
            templateId: null,
            routedKind: 'CODE',
            reasons: ['按入口类型选择兼容流程']
          });
        }
        return json(200, {});
      })
    );
  });

  function setup() {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } }
    });
    return render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <ToastProvider>
            <TaskComposer />
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>
    );
  }

  it('默认收起高级配置，点击后展开', async () => {
    const user = userEvent.setup();
    setup();

    const summary = await screen.findByRole('button', { name: /高级运行配置/ });
    expect(summary).toHaveAttribute('aria-expanded', 'false');
    await user.click(summary);
    expect(summary).toHaveAttribute('aria-expanded', 'true');
  });

  it('切换入口类型后主操作从“生成计划”变为“开始运行”', async () => {
    const user = userEvent.setup();
    setup();

    expect(await screen.findByRole('button', { name: '生成计划' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '通用目标' }));
    expect(screen.getByRole('button', { name: '开始运行' })).toBeInTheDocument();
  });
});
