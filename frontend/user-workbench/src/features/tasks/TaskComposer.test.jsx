import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ToastProvider } from '../../components/ToastProvider';
import TaskComposer from './TaskComposer';

const workspace = {
  id: 'ws-1',
  branchName: 'agent/work-1',
  repositoryPath: 'D:/repos/demo',
  baseBranch: 'main'
};

function json(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body)
  };
}

describe('TaskComposer', () => {
  let workspaces;
  let created;

  beforeEach(() => {
    workspaces = [];
    created = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url, options) => {
        if (url === '/api/workspaces') return json(200, workspaces);
        if (url === '/api/entry-policies') return json(200, []);
        if (url === '/api/entry-routes/preview') {
          return json(200, {
            ruleCode: 'DEFAULT',
            templateId: null,
            routedKind: 'CODE',
            reasons: ['按入口类型选择兼容流程']
          });
        }
        if (url === '/api/auth/csrf') return json(200, { token: 'csrf-token' });
        if (url === '/api/tasks' && options?.method === 'POST') {
          created.push(JSON.parse(options.body));
          return json(200, { runId: 'run-1' });
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

  async function chooseWorkspace(user) {
    await user.click(await screen.findByRole('button', { name: /选择工作区/ }));
    await user.click(await screen.findByRole('menuitem', { name: /agent\/work-1/ }));
  }

  async function fillGoal(user) {
    await user.type(
      screen.getByPlaceholderText(/描述你想构建的功能/),
      '给排序网页补充可单步演示的柱状图'
    );
  }

  it('默认收起高级配置，点击后展开', async () => {
    const user = userEvent.setup({ delay: null });
    setup();

    const summary = await screen.findByRole('button', { name: /高级运行配置/ });
    expect(summary).toHaveAttribute('aria-expanded', 'false');
    await user.click(summary);
    expect(summary).toHaveAttribute('aria-expanded', 'true');
  });

  it('切换入口类型后主操作从“生成计划”变为“开始运行”', async () => {
    const user = userEvent.setup({ delay: null });
    setup();

    expect(await screen.findByRole('button', { name: '生成计划' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '通用目标' }));
    expect(screen.getByRole('button', { name: '开始运行' })).toBeInTheDocument();
  });

  it('选定工作区后默认整个工作区授权并允许修改，直接提交 directoryScopes ["."]', async () => {
    workspaces = [workspace];
    const user = userEvent.setup({ delay: null });
    setup();

    await chooseWorkspace(user);
    const bar = await screen.findByRole('group', { name: '工作区授权范围' });
    expect(within(bar).getByRole('button', { name: '整个工作区' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
    expect(screen.getByRole('checkbox', { name: '允许修改授权文件' })).toBeChecked();

    await fillGoal(user);
    await user.click(screen.getByRole('button', { name: '生成计划' }));

    await waitFor(() => expect(created).toHaveLength(1));
    expect(created[0]).toMatchObject({
      workspaceId: 'ws-1',
      fileScopes: [],
      directoryScopes: ['.'],
      excludedPaths: [],
      writeEnabled: true
    });
  });

  it('测试入口默认只读，仍使用整个工作区授权', async () => {
    workspaces = [workspace];
    const user = userEvent.setup({ delay: null });
    setup();

    await chooseWorkspace(user);
    await user.click(screen.getByRole('button', { name: '测试' }));
    expect(screen.getByRole('checkbox', { name: '允许修改授权文件' })).not.toBeChecked();

    await fillGoal(user);
    await user.click(screen.getByRole('button', { name: '生成计划' }));

    await waitFor(() => expect(created).toHaveLength(1));
    expect(created[0]).toMatchObject({
      directoryScopes: ['.'],
      writeEnabled: false
    });
  });

  it('切到指定子目录后发送目录范围与排除路径', { timeout: 20000 }, async () => {
    workspaces = [workspace];
    const user = userEvent.setup({ delay: null });
    setup();

    await chooseWorkspace(user);
    await user.click(screen.getByRole('button', { name: '指定子目录' }));
    await user.type(screen.getByLabelText('目录范围（每行一个）'), 'backend/src');
    await user.type(screen.getByLabelText('排除路径（每行一个，可选）'), 'backend/src/generated');

    await fillGoal(user);
    await user.click(screen.getByRole('button', { name: '生成计划' }));

    await waitFor(() => expect(created).toHaveLength(1));
    expect(created[0]).toMatchObject({
      fileScopes: [],
      directoryScopes: ['backend/src'],
      excludedPaths: ['backend/src/generated']
    });
  });

  it('指定子目录模式为空时拒绝提交并提示', async () => {
    workspaces = [workspace];
    const user = userEvent.setup({ delay: null });
    setup();

    await chooseWorkspace(user);
    await user.click(screen.getByRole('button', { name: '指定子目录' }));
    await fillGoal(user);
    await user.click(screen.getByRole('button', { name: '生成计划' }));

    expect(await screen.findByText('请至少填写一个目录范围。')).toBeInTheDocument();
    expect(created).toHaveLength(0);
  });

  it('切到指定文件后发送 fileScopes 并清空目录范围', { timeout: 20000 }, async () => {
    workspaces = [workspace];
    const user = userEvent.setup({ delay: null });
    setup();

    await chooseWorkspace(user);
    await user.click(screen.getByRole('button', { name: '指定文件' }));
    await user.type(
      screen.getByLabelText('授权文件（每行一个相对路径）'),
      'frontend/user-workbench/src/App.jsx'
    );

    await fillGoal(user);
    await user.click(screen.getByRole('button', { name: '生成计划' }));

    await waitFor(() => expect(created).toHaveLength(1));
    expect(created[0]).toMatchObject({
      fileScopes: ['frontend/user-workbench/src/App.jsx'],
      directoryScopes: [],
      excludedPaths: []
    });
  });

  it('低频预算区默认折叠', async () => {
    const user = userEvent.setup({ delay: null });
    setup();

    await user.click(await screen.findByRole('button', { name: /高级运行配置/ }));
    const budget = screen.getByRole('button', { name: /高级预算调优/ });
    expect(budget).toHaveAttribute('aria-expanded', 'false');
    await user.click(budget);
    expect(budget).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByLabelText('最大轮次')).toBeInTheDocument();
  });
});
