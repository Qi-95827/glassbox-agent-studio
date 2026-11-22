import { useState } from 'react';
import { Alert, Box, Button, Chip, Stack, Tab, Tabs, Typography } from '@mui/material';
import AutoAwesomeOutlined from '@mui/icons-material/AutoAwesomeOutlined';
import AddRounded from '@mui/icons-material/AddRounded';
import AccountTreeOutlined from '@mui/icons-material/AccountTreeOutlined';
import SmartToyOutlined from '@mui/icons-material/SmartToyOutlined';
import BuildOutlined from '@mui/icons-material/BuildOutlined';
import MenuBookOutlined from '@mui/icons-material/MenuBookOutlined';
import { useAuth } from '../auth/AuthGate';
import { useMyAgents, usePublishAgentComponent } from '../../hooks/useMyAgents';
import { usePageChrome } from '../../layout/pageChrome';
import { useToast } from '../../components/ToastProvider';
import EmptyState from '../../components/EmptyState';
import ComponentCard from './ComponentCard';
import ComponentDialog from './ComponentDialog';
import VersionHistoryDialog from './VersionHistoryDialog';
import AgentCatalogDialog from './AgentCatalogDialog';
import AgentDraftDialog from './AgentDraftDialog';
import AccountsPanel from './AccountsPanel';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

function PanelHeading({ title, description, actions }) {
  return (
    <Stack
      direction={{ xs: 'column', sm: 'row' }}
      spacing={1.5}

      sx={{ mb: 2, alignItems: { sm: 'center' }, justifyContent: 'space-between' }}
    >
      <Box>
        <Typography sx={{ fontSize: 15, fontWeight: 500 }}>{title}</Typography>
        <Typography variant="body2" color="text.secondary">
          {description}
        </Typography>
      </Box>
      <Stack direction="row" spacing={0.75} sx={{ flexShrink: 0 }}>
        {actions}
      </Stack>
    </Stack>
  );
}

export default function AgentsPage() {
  const { t } = useI18n();
  const { user } = useAuth();
  const toast = useToast();
  const { skills, tools, agents } = useMyAgents();
  const publish = usePublishAgentComponent();
  const [tab, setTab] = useState('agents');
  const [editor, setEditor] = useState(null);
  const [versions, setVersions] = useState(null);
  const [catalogOpen, setCatalogOpen] = useState(false);
  const [draftOpen, setDraftOpen] = useState(false);

  usePageChrome({ trail: [{ label: t('nav.workspace') }, { label: t('nav.agents') }] });

  const act = async (type, id, action, message) => {
    try {
      await publish.mutateAsync({ type, id, action });
      toast.success(message);
    } catch (error) {
      toast.error(error.message);
    }
  };

  const skillRows = skills.data ?? [];
  const toolRows = tools.data ?? [];
  const agentRows = agents.data ?? [];
  const loading = skills.isLoading || tools.isLoading || agents.isLoading;
  const error = skills.error || tools.error || agents.error;

  const openNew = () =>
    setEditor({ type: tab === 'skills' ? 'skill' : tab === 'tools' ? 'tool' : 'agent' });

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Box sx={{ maxWidth: 1080, mx: 'auto', px: { xs: 2, sm: 3 }, py: { xs: 3, md: 4 } }}>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}

          spacing={1.5}
          sx={{ mb: 2.5, justifyContent: 'space-between', alignItems: { sm: 'flex-start' } }}
        >
          <Box>
            <Typography component="h1" sx={{ fontSize: 22, fontWeight: 500 }}>
              {t('agents.title')}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {t('agents.description', {
                name: user?.displayName,
                username: user?.username
              })}
            </Typography>
          </Box>
          <Stack direction="row" spacing={0.75}>
            <Button
              variant="outlined"
              startIcon={<AccountTreeOutlined />}
              onClick={() => setCatalogOpen(true)}
            >
              {t('agents.catalog')}
            </Button>
            {tab !== 'accounts' && (
              <Button variant="contained" startIcon={<AddRounded />} onClick={openNew}>
                {tab === 'skills'
                  ? t('agents.newSkill')
                  : tab === 'tools'
                    ? t('agents.newTool')
                    : t('agents.newAgent')}
              </Button>
            )}
          </Stack>
        </Stack>

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error.message}
          </Alert>
        )}

        <Tabs
          value={tab}
          onChange={(_, value) => setTab(value)}
          sx={{ borderBottom: `1px solid ${googleTokens.outline}`, mb: 2.5 }}
        >
          <Tab value="agents" label={t('agents.tab.agents', { count: agentRows.length })} />
          <Tab value="skills" label={t('agents.tab.skills', { count: skillRows.length })} />
          <Tab value="tools" label={t('agents.tab.tools', { count: toolRows.length })} />
          {user?.role === 'ADMIN' && <Tab value="accounts" label={t('agents.tab.accounts')} />}
        </Tabs>

        {tab === 'agents' && (
          <>
            <PanelHeading
              title={t('agents.privateDrafts')}
              description={t('agents.privateDraftsDescription')}
              actions={
                <Button
                  variant="outlined"
                  startIcon={<AutoAwesomeOutlined />}
                  onClick={() => setDraftOpen(true)}
                >
                  {t('agents.generateDraft')}
                </Button>
              }
            />

            <Alert severity="info" sx={{ mb: 2 }}>
              {t('agents.draftNote')}
            </Alert>
            <Stack spacing={1.5}>
              {agentRows.map(row => (
                <ComponentCard
                  key={row.id}
                  row={row}
                  onEdit={() => setEditor({ type: 'agent', initial: row })}
                  onPublish={() => act('agent', row.id, 'publish', t('agents.published'))}
                  onArchive={() => act('agent', row.id, 'archive', t('agents.archived'))}
                  onVersions={() => setVersions({ type: 'agent', id: row.id })}
                >
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 0.75 }}>
                    {row.description || t('agents.noDescription')}
                  </Typography>
                  <Typography
                    variant="body2"
                    sx={{
                      display: '-webkit-box',
                      WebkitLineClamp: 3,
                      WebkitBoxOrient: 'vertical',
                      overflow: 'hidden',
                      mb: 0.75
                    }}
                  >
                    {row.responsibility || '未填写职责'}
                  </Typography>
                  <Stack direction="row" spacing={0.6} useFlexGap sx={{ flexWrap: 'wrap' }}>
                    <Chip
                      size="small"
                      label={row.permissionRole === 'REVIEWER' ? '审查（无工具）' : '分析（只读）'}
                    />

                    <Chip
                      size="small"
                      label={
                        row.modelStrategy === 'FIXED' ? `固定模型 ${row.modelMode}` : '继承任务模型'
                      }
                    />

                    {row.recommendable && (
                      <Chip
                        size="small"
                        label="可推荐"
                        sx={{ bgcolor: '#E6F4EA', color: '#137333' }}
                      />
                    )}
                    <Chip size="small" label={`Skill ${row.skills?.length ?? 0}`} />
                    <Chip size="small" label={`工具 ${row.tools?.length ?? 0}`} />
                  </Stack>
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ display: 'block', mt: 0.9, fontFamily: googleTokens.mono }}
                  >
                    定义引用：{row.definitionRef || '未发布'} · 摘要{' '}
                    {row.specSha256 ? `${row.specSha256.slice(0, 12)}…` : '未发布'}
                  </Typography>
                </ComponentCard>
              ))}
            </Stack>
            {!loading && agentRows.length === 0 && (
              <Box
                sx={{
                  bgcolor: '#FFFFFF',
                  border: `1px solid ${googleTokens.outline}`,
                  borderRadius: 4
                }}
              >
                <EmptyState
                  icon={<SmartToyOutlined />}
                  title="还没有私有 Agent"
                  description="可以先从设计思路生成草稿，再手动检查 Skill、工具与权限角色。"
                  action={{ label: '新建 Agent', onClick: openNew }}
                />
              </Box>
            )}
          </>
        )}

        {tab === 'skills' && (
          <>
            <PanelHeading
              title="私有 Skill"
              description="Skill 是可复用的方法指导；发布后版本固定，只对当前账号可见。"
            />

            <Stack spacing={1.5}>
              {skillRows.map(row => (
                <ComponentCard
                  key={row.id}
                  row={row}
                  onEdit={() => setEditor({ type: 'skill', initial: row })}
                  onPublish={() => act('skill', row.id, 'publish', 'Skill 已发布新版本')}
                  onArchive={() => act('skill', row.id, 'archive', 'Skill 已归档')}
                  onVersions={() => setVersions({ type: 'skill', id: row.id })}
                >
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 0.75 }}>
                    {row.summary || '未填写用途摘要'}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                    输入约定：{row.inputContract || '未设置'}；预期输出：
                    {row.outputContract || '未设置'}
                  </Typography>
                  {row.contentMd && (
                    <Box
                      sx={{
                        mt: 1,
                        p: 1.25,
                        bgcolor: '#F8F9FA',
                        borderRadius: 2,
                        maxHeight: 110,
                        overflow: 'hidden',
                        position: 'relative'
                      }}
                    >
                      <Typography
                        component="pre"
                        variant="caption"
                        sx={{
                          m: 0,
                          whiteSpace: 'pre-wrap',
                          fontFamily: googleTokens.mono,
                          color: '#5F6368'
                        }}
                      >
                        {row.contentMd.slice(0, 420)}
                      </Typography>
                    </Box>
                  )}
                </ComponentCard>
              ))}
            </Stack>
            {!loading && skillRows.length === 0 && (
              <Box
                sx={{
                  bgcolor: '#FFFFFF',
                  border: `1px solid ${googleTokens.outline}`,
                  borderRadius: 4
                }}
              >
                <EmptyState
                  icon={<MenuBookOutlined />}
                  title="还没有私有 Skill"
                  description="把稳定的方法、检查清单或领域约定固化成可复用 Skill。"
                  action={{ label: '新建 Skill', onClick: openNew }}
                />
              </Box>
            )}
          </>
        )}

        {tab === 'tools' && (
          <>
            <PanelHeading
              title="私有只读工具"
              description="工具不能扩大文件权限；扩展名过滤会在任务授权范围内再次收窄。"
            />

            <Alert severity="info" sx={{ mb: 2 }}>
              首批私有工具只使用受信只读执行器（搜索 / 读取）；写入能力仍由系统受控工具完成。
            </Alert>
            <Stack spacing={1.5}>
              {toolRows.map(row => (
                <ComponentCard
                  key={row.id}
                  row={row}
                  onEdit={() => setEditor({ type: 'tool', initial: row })}
                  onPublish={() => act('tool', row.id, 'publish', '工具已发布新版本')}
                  onArchive={() => act('tool', row.id, 'archive', '工具已归档')}
                  onVersions={() => setVersions({ type: 'tool', id: row.id })}
                >
                  <Typography variant="body2" color="text.secondary">
                    {row.description || '未填写说明'}
                  </Typography>
                  <Stack
                    direction="row"
                    spacing={0.6}
                    useFlexGap
                    sx={{ mt: 0.8, flexWrap: 'wrap' }}
                  >
                    <Chip size="small" label={row.executorLabel || row.executorKind} />
                    {(row.fileExtensions ?? []).map(ext => (
                      <Chip
                        key={ext}
                        size="small"
                        label={ext}
                        sx={{ fontFamily: googleTokens.mono }}
                      />
                    ))}
                    {(row.fileExtensions ?? []).length === 0 && (
                      <Chip size="small" label="扩展名未设置" />
                    )}
                  </Stack>
                </ComponentCard>
              ))}
            </Stack>
            {!loading && toolRows.length === 0 && (
              <Box
                sx={{
                  bgcolor: '#FFFFFF',
                  border: `1px solid ${googleTokens.outline}`,
                  borderRadius: 4
                }}
              >
                <EmptyState
                  icon={<BuildOutlined />}
                  title="还没有私有工具"
                  description="创建只读搜索或读取工具，并在发布后绑定到私有 Agent。"
                  action={{ label: '新建工具', onClick: openNew }}
                />
              </Box>
            )}
          </>
        )}

        {tab === 'accounts' && user?.role === 'ADMIN' && <AccountsPanel />}
      </Box>

      <ComponentDialog
        open={Boolean(editor)}
        type={editor?.type ?? 'skill'}
        initial={editor?.initial}
        skills={skillRows}
        tools={toolRows}
        onClose={() => setEditor(null)}
      />

      <VersionHistoryDialog
        open={Boolean(versions)}
        type={versions?.type}
        id={versions?.id}
        onClose={() => setVersions(null)}
      />

      <AgentCatalogDialog open={catalogOpen} onClose={() => setCatalogOpen(false)} />

      <AgentDraftDialog
        open={draftOpen}
        onClose={() => setDraftOpen(false)}
        onApply={initial => {
          setTab('agents');
          setEditor({ type: 'agent', initial });
        }}
      />
    </Box>
  );
}
