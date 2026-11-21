import { useEffect, useState } from 'react';
import { Alert, Box, Button, Chip, Divider, Stack, Tab, Tabs, Typography } from '@mui/material';
import AddRounded from '@mui/icons-material/AddRounded';
import RefreshRounded from '@mui/icons-material/RefreshRounded';
import AccountTreeOutlined from '@mui/icons-material/AccountTreeOutlined';
import { useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useWorkspaces, useWorkspaceStatus } from '../../hooks/useWorkspaces';
import { usePageChrome } from '../../layout/pageChrome';
import EmptyState from '../../components/EmptyState';
import NewWorkspaceDialog from './NewWorkspaceDialog';
import WorkspaceFileEditor from './WorkspaceFileEditor';
import WorkspaceMemoryPanel from './WorkspaceMemoryPanel';
import WorkspaceGitPanel from './WorkspaceGitPanel';
import { googleTokens } from '../../theme/googleTheme';
import { dateTime, shortId } from '../../utils/format';

export default function WorkspacesPage() {
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const workspaces = useWorkspaces();
  const [selectedId, setSelectedId] = useState(params.get('id') || '');
  const [tab, setTab] = useState('overview');
  const [createOpen, setCreateOpen] = useState(false);
  const status = useWorkspaceStatus(selectedId);

  usePageChrome({ trail: [{ label: '工作空间' }, { label: '工作区与交付' }] });

  useEffect(() => {
    if (params.get('id') && params.get('id') !== selectedId) {
      setSelectedId(params.get('id'));
    }
  }, [params, selectedId]);

  useEffect(() => {
    if (!selectedId && (workspaces.data ?? []).length > 0) {
      setSelectedId(workspaces.data[0].id);
    }
  }, [workspaces.data, selectedId]);

  const list = workspaces.data ?? [];
  const selected = list.find(item => item.id === selectedId) ?? null;

  const select = id => {
    setSelectedId(id);
    const next = new URLSearchParams(params);
    next.set('id', id);
    setParams(next, { replace: true });
  };

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Box sx={{ maxWidth: 1200, mx: 'auto', px: { xs: 2, sm: 3 }, py: { xs: 3, md: 4 } }}>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}

          spacing={1.5}
          sx={{ mb: 2.5, justifyContent: 'space-between', alignItems: { sm: 'flex-start' } }}
        >
          <Box>
            <Typography component="h1" sx={{ fontSize: 22, fontWeight: 500 }}>
              工作区与交付
            </Typography>
            <Typography variant="body2" color="text.secondary">
              查看 Git 差异、人工审查决定、本地提交与推送 / 合并记录。工作区不是代码执行沙箱。
            </Typography>
          </Box>
          <Button
            variant="contained"
            startIcon={<AddRounded />}
            onClick={() => setCreateOpen(true)}
          >
            新建工作区
          </Button>
        </Stack>

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', lg: '310px minmax(0, 1fr)' },
            gap: 2,
            alignItems: 'start'
          }}
        >
          <Box
            sx={{
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`,
              borderRadius: 4,
              p: 1.25,
              position: { lg: 'sticky' },
              top: 16
            }}
          >
            <Typography sx={{ fontSize: 13, fontWeight: 600, px: 1, py: 0.75 }}>
              已创建工作区（{list.length}）
            </Typography>
            <Stack spacing={0.4}>
              {list.map(item => {
                const active = item.id === selectedId;
                return (
                  <Box
                    key={item.id}
                    role="button"
                    tabIndex={0}
                    onClick={() => select(item.id)}
                    onKeyDown={event => event.key === 'Enter' && select(item.id)}
                    sx={{
                      px: 1.25,
                      py: 1,
                      borderRadius: 2.5,
                      cursor: 'pointer',
                      bgcolor: active ? '#E8F0FE' : 'transparent',
                      '&:hover': { bgcolor: active ? '#E8F0FE' : '#F8F9FA' }
                    }}
                  >
                    <Stack direction="row" spacing={0.6} sx={{ alignItems: 'center' }}>
                      <AccountTreeOutlined
                        sx={{ fontSize: 16, color: active ? '#1967D2' : '#9AA0A6' }}
                      />

                      <Typography
                        noWrap
                        sx={{ fontSize: 13, fontWeight: active ? 600 : 500, flex: 1, minWidth: 0 }}
                      >
                        {item.branchName}
                      </Typography>
                    </Stack>
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      noWrap
                      sx={{ display: 'block' }}
                    >
                      {item.repositoryPath}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      基础 {shortId(item.baseCommit)} · {dateTime(item.createdAt)}
                    </Typography>
                  </Box>
                );
              })}
            </Stack>
            {!workspaces.isLoading && list.length === 0 && (
              <EmptyState
                compact
                title="还没有工作区"
                description="从可信本地仓库创建独立分支后，才能授权 Agent 读取或修改文件。"
                action={{ label: '新建工作区', onClick: () => setCreateOpen(true) }}
              />
            )}
          </Box>

          <Box
            sx={{
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`,
              borderRadius: 4,
              minWidth: 0,
              overflow: 'hidden'
            }}
          >
            {!selected ? (
              <EmptyState
                title="选择一个工作区"
                description="左侧列表按创建时间展示当前账号可访问的工作区。"
              />
            ) : (
              <>
                <Box sx={{ px: { xs: 2, sm: 2.5 }, pt: 2 }}>
                  <Stack
                    direction={{ xs: 'column', sm: 'row' }}

                    spacing={1}
                    sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' } }}
                  >
                    <Box sx={{ minWidth: 0 }}>
                      <Stack
                        direction="row"
                        spacing={0.8}
                        sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                      >
                        <Typography sx={{ fontSize: 17, fontWeight: 500 }}>
                          {selected.branchName}
                        </Typography>
                        <Chip
                          size="small"
                          label={selected.repositoryPath}
                          sx={{ fontFamily: googleTokens.mono, maxWidth: 340 }}
                        />
                      </Stack>
                      <Typography
                        variant="caption"
                        color="text.secondary"
                        sx={{ fontFamily: googleTokens.mono }}
                      >
                        {selected.workspacePath}
                      </Typography>
                    </Box>
                    <Button
                      size="small"
                      variant="outlined"
                      startIcon={<RefreshRounded />}
                      sx={{ flexShrink: 0, whiteSpace: 'nowrap' }}
                      onClick={() => {
                        queryClient.invalidateQueries({
                          queryKey: ['workspace-status', selectedId]
                        });
                        queryClient.invalidateQueries({
                          queryKey: ['workspace-approvals', selectedId]
                        });
                        queryClient.invalidateQueries({
                          queryKey: ['workspace-commits', selectedId]
                        });
                        queryClient.invalidateQueries({
                          queryKey: ['workspace-deliveries', selectedId]
                        });
                        queryClient.invalidateQueries({
                          queryKey: ['workspace-untracked', selectedId]
                        });
                      }}
                    >
                      刷新状态
                    </Button>
                  </Stack>
                  <Tabs
                    value={tab}
                    onChange={(_, value) => setTab(value)}
                    variant="scrollable"
                    scrollButtons={false}
                    sx={{ mt: 1, borderBottom: `1px solid ${googleTokens.outline}` }}
                  >
                    <Tab value="overview" label="概览与差异" />
                    <Tab value="files" label="文件" />
                    <Tab value="memory" label="项目背景" />
                    <Tab value="git" label="Git 与交付" />
                  </Tabs>
                </Box>

                <Box sx={{ p: { xs: 2, sm: 2.5 } }}>
                  {tab === 'overview' && (
                    <Stack spacing={1.5}>
                      {status.isLoading && (
                        <Typography variant="body2" color="text.secondary">
                          正在读取 Git 状态…
                        </Typography>
                      )}
                      {status.isError && <Alert severity="error">{status.error.message}</Alert>}
                      {status.data?.baseChanged && (
                        <Alert severity="warning">目标分支已变化，请重新检查差异与审批。</Alert>
                      )}
                      <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap' }}>
                        <Chip size="small" label={`基础提交 ${shortId(selected.baseCommit)}`} />
                        <Chip size="small" label={`创建于 ${dateTime(selected.createdAt)}`} />
                        <Chip
                          size="small"
                          label={status.data?.changes ? '存在工作区变更' : '工作区干净'}
                          sx={{
                            bgcolor: status.data?.changes ? '#FEF7E0' : '#E6F4EA',
                            color: status.data?.changes ? '#B06000' : '#137333'
                          }}
                        />
                      </Stack>
                      <Box>
                        <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
                          Git 状态
                        </Typography>
                        <Box
                          component="pre"
                          sx={{
                            m: 0,
                            p: 1.5,
                            bgcolor: '#F8F9FA',
                            borderRadius: 2.5,
                            fontSize: 12.5,
                            fontFamily: googleTokens.mono,
                            whiteSpace: 'pre-wrap',
                            overflowWrap: 'anywhere',
                            maxHeight: 220,
                            overflowY: 'auto'
                          }}
                        >
                          {status.data?.changes || '无变更'}
                        </Box>
                      </Box>
                      <Divider />
                      <Box>
                        <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
                          已跟踪文件差异
                        </Typography>
                        <Box
                          component="pre"
                          sx={{
                            m: 0,
                            p: 1.5,
                            bgcolor: '#F8F9FA',
                            borderRadius: 2.5,
                            fontSize: 12.5,
                            fontFamily: googleTokens.mono,
                            whiteSpace: 'pre-wrap',
                            overflowWrap: 'anywhere',
                            maxHeight: 320,
                            overflowY: 'auto'
                          }}
                        >
                          {status.data?.diff || '无差异'}
                        </Box>
                      </Box>
                      <Alert severity="info">
                        未跟踪文件不包含在差异中，必须先在工作区交付面板逐项复核；审批仅记录当前差异，不会提交、推送或合并。
                      </Alert>
                    </Stack>
                  )}

                  {tab === 'files' && <WorkspaceFileEditor workspaceId={selectedId} />}
                  {tab === 'memory' && <WorkspaceMemoryPanel workspaceId={selectedId} />}
                  {tab === 'git' && <WorkspaceGitPanel workspace={selected} />}
                </Box>
              </>
            )}
          </Box>
        </Box>
      </Box>

      <NewWorkspaceDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={created => {
          select(created.id);
          queryClient.invalidateQueries({ queryKey: ['workspaces'] });
        }}
      />
    </Box>
  );
}
