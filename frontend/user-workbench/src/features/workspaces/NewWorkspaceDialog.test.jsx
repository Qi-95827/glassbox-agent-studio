import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ToastProvider } from '../../components/ToastProvider';
import NewWorkspaceDialog from './NewWorkspaceDialog';

const { authState } = vi.hoisted(() => ({ authState: { user: null } }));

vi.mock('../auth/AuthGate', () => ({ useAuth: () => authState }));

function json(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body)
  };
}

describe('NewWorkspaceDialog', () => {
  let posted;

  beforeEach(() => {
    posted = null;
    authState.user = null;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url, options) => {
        if (url === '/api/auth/csrf') return json(200, { token: 'csrf-token' });
        if (url === '/api/workspaces' && options?.method === 'POST') {
          posted = JSON.parse(options.body);
          return json(200, { id: 'ws-new', branchName: 'agent/work-1' });
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
        <ToastProvider>
          <NewWorkspaceDialog open onClose={() => {}} />
        </ToastProvider>
      </QueryClientProvider>
    );
  }

  it('管理员可见自动授权开关，并按默认勾选提交 autoGrantForAdmin', async () => {
    authState.user = { role: 'ADMIN' };
    const user = userEvent.setup();
    setup();

    const toggle = await screen.findByRole('checkbox', {
      name: /未授权时自动为当前管理员登记该仓库/
    });
    expect(toggle).toBeChecked();

    await user.type(screen.getByLabelText('本地仓库目录'), 'D:/repos/demo');
    await user.click(screen.getByRole('button', { name: '创建独立分支和工作区' }));

    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted.autoGrantForAdmin).toBe(true);
    expect(posted.repositoryPath).toBe('D:/repos/demo');
    expect(posted.baseBranch).toBe('main');
  });

  it('管理员取消勾选后不再请求自动授权', async () => {
    authState.user = { role: 'ADMIN' };
    const user = userEvent.setup();
    setup();

    const toggle = await screen.findByRole('checkbox', {
      name: /未授权时自动为当前管理员登记该仓库/
    });
    await user.click(toggle);
    expect(toggle).not.toBeChecked();
    await user.type(screen.getByLabelText('本地仓库目录'), 'D:/repos/demo');
    await user.click(screen.getByRole('button', { name: '创建独立分支和工作区' }));

    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted.autoGrantForAdmin).toBeUndefined();
  });

  it('普通账号看不到自动授权入口，只显示联系管理员提示', async () => {
    authState.user = { role: 'USER' };
    const user = userEvent.setup();
    setup();

    expect(await screen.findByText(/请联系管理员/)).toBeInTheDocument();
    expect(
      screen.queryByRole('checkbox', { name: /未授权时自动为当前管理员登记该仓库/ })
    ).toBeNull();

    await user.type(screen.getByLabelText('本地仓库目录'), 'D:/repos/demo');
    await user.click(screen.getByRole('button', { name: '创建独立分支和工作区' }));

    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted.autoGrantForAdmin).toBeUndefined();
    expect(posted.repositoryPath).toBe('D:/repos/demo');
  });

  it('未填写仓库目录时提交前拒绝并给出提示', async () => {
    authState.user = { role: 'ADMIN' };
    const user = userEvent.setup();
    setup();

    await user.click(screen.getByRole('button', { name: '创建独立分支和工作区' }));
    expect(await screen.findByText('请填写本地仓库目录。')).toBeInTheDocument();
    expect(posted).toBeNull();
  });
});
