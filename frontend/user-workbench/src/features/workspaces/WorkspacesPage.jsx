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
import WorkspaceKnowledgePanel from './WorkspaceKnowledgePanel';
import MemoryAndChunksPanel from '../memory/MemoryAndChunksPanel';
import WorkspaceGitPanel from './WorkspaceGitPanel';
import { googleTokens } from '../../theme/googleTheme';
import { dateTime, shortId } from '../../utils/format';
import { useI18n } from '../../i18n/I18nProvider';
import ArchitectureLensButton from '../architecture/ArchitectureLensButton';

export default function WorkspacesPage() {
  const { t, locale } = useI18n();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const workspaces = useWorkspaces();
  const [selectedId, setSelectedId] = useState(params.get('id') || '');
  const [tab, setTab] = useState('overview');
  const [createOpen, setCreateOpen] = useState(false);
  const status = useWorkspaceStatus(selectedId);

  usePageChrome({ trail: [{ label: t('nav.workspace') }, { label: t('nav.workspaces') }] });

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
              {t('workspace.title')}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {t('workspace.description')}
            </Typography>
          </Box>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexShrink: 0 }}>
            <ArchitectureLensButton topic="delivery-monitoring" />
            <Button
              variant="contained"
              startIcon={<AddRounded />}
              onClick={() => setCreateOpen(true)}
            >
              {t('workspace.new')}
            </Button>
          </Stack>
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
              {t('workspace.createdCount', { count: list.length })}
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
                      {t('workspace.baseAt', {
                        id: shortId(item.baseCommit),
                        time: dateTime(item.createdAt, locale)
                      })}
                    </Typography>
                  </Box>
                );
              })}
            </Stack>
            {!workspaces.isLoading && list.length === 0 && (
              <EmptyState
                compact
                title={t('workspace.emptyTitle')}
                description={t('workspace.emptyDescription')}
                action={{ label: t('workspace.new'), onClick: () => setCreateOpen(true) }}
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
                title={t('workspace.selectTitle')}
                description={t('workspace.selectDescription')}
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
                      {t('workspace.refresh')}
                    </Button>
                  </Stack>
                  <Tabs
                    value={tab}
                    onChange={(_, value) => setTab(value)}
                    variant="scrollable"
                    scrollButtons={false}
                    sx={{ mt: 1, borderBottom: `1px solid ${googleTokens.outline}` }}
                  >
                    <Tab value="overview" label={t('workspace.tab.overview')} />
                    <Tab value="files" label={t('workspace.tab.files')} />
                    <Tab value="memory" label={t('workspace.tab.memory')} />
                    <Tab value="knowledge" label={t('workspace.tab.knowledge')} />
                    <Tab value="insight" label={t('workspace.tab.insight')} />
                    <Tab value="git" label={t('workspace.tab.git')} />
                  </Tabs>
                </Box>

                <Box sx={{ p: { xs: 2, sm: 2.5 } }}>
                  {tab === 'overview' && (
                    <Stack spacing={1.5}>
                      {status.isLoading && (
                        <Typography variant="body2" color="text.secondary">
                          {t('workspace.loadingStatus')}
                        </Typography>
                      )}
                      {status.isError && <Alert severity="error">{status.error.message}</Alert>}
                      {status.data?.baseChanged && (
                        <Alert severity="warning">{t('workspace.baseChanged')}</Alert>
                      )}
                      <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap' }}>
                        <Chip
                          size="small"
                          label={t('workspace.baseCommit', { id: shortId(selected.baseCommit) })}
                        />
                        <Chip
                          size="small"
                          label={t('workspace.createdAt', {
                            time: dateTime(selected.createdAt, locale)
                          })}
                        />
                        <Chip
                          size="small"
                          label={
                            status.data?.changes ? t('workspace.hasChanges') : t('workspace.clean')
                          }
                          sx={{
                            bgcolor: status.data?.changes ? '#FEF7E0' : '#E6F4EA',
                            color: status.data?.changes ? '#B06000' : '#137333'
                          }}
                        />
                      </Stack>
                      <Box>
                        <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
                          {t('workspace.gitStatus')}
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
                          {status.data?.changes || t('workspace.noChanges')}
                        </Box>
                      </Box>
                      <Divider />
                      <Box>
                        <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
                          {t('workspace.trackedDiff')}
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
                          {status.data?.diff || t('workspace.noDiff')}
                        </Box>
                      </Box>
                      <Alert severity="info">{t('workspace.untrackedNote')}</Alert>
                    </Stack>
                  )}

                  {tab === 'files' && <WorkspaceFileEditor workspaceId={selectedId} />}
                  {tab === 'memory' && <WorkspaceMemoryPanel workspaceId={selectedId} />}
                  {tab === 'knowledge' && <WorkspaceKnowledgePanel workspaceId={selectedId} />}
                  {tab === 'insight' && <MemoryAndChunksPanel workspaceId={selectedId} />}
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
