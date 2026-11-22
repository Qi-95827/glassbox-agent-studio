import { useState } from 'react';
import { Alert, Box, Button, Chip, MenuItem, Stack, TextField, Typography } from '@mui/material';
import HistoryRounded from '@mui/icons-material/HistoryRounded';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { workspacesApi } from '../../api/workspaces';
import { useToast } from '../../components/ToastProvider';
import EmptyState from '../../components/EmptyState';
import MemoryHistoryDialog from './MemoryHistoryDialog';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

const categories = {
  PROJECT_CONVENTION: '项目约定',
  PROJECT_BACKGROUND: '项目背景',
  PRECAUTION: '注意事项'
};

/** 工作区三层记忆：语义/情景/程序；候选必须采纳后才注入任务。 */
export default function WorkspaceMemoryPanel({ workspaceId }) {
  const { t } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [typeFilter, setTypeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const memories = useQuery({
    queryKey: ['workspace-memories', workspaceId, typeFilter, statusFilter],
    queryFn: () =>
      workspacesApi.memories(workspaceId, {
        type: typeFilter || undefined,
        status: statusFilter || undefined
      })
  });
  const [category, setCategory] = useState('PROJECT_CONVENTION');
  const [content, setContent] = useState('');
  const [editing, setEditing] = useState(null);
  const [search, setSearch] = useState('');
  const [results, setResults] = useState(null);
  const [busy, setBusy] = useState(false);
  const [history, setHistory] = useState(null);

  const refresh = () =>
    queryClient.invalidateQueries({ queryKey: ['workspace-memories', workspaceId] });
  const rows = results ?? memories.data ?? [];

  const save = async () => {
    if (!content.trim()) {
      toast.warning('请填写背景内容');
      return;
    }
    setBusy(true);
    try {
      if (editing) {
        await workspacesApi.updateMemory(workspaceId, editing.id, {
          category,
          content,
          version: editing.version
        });
      } else {
        await workspacesApi.createMemory(workspaceId, { category, content });
      }
      setEditing(null);
      setContent('');
      setResults(null);
      await refresh();
      toast.success('项目背景已保存');
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy(false);
    }
  };

  const remove = async row => {
    try {
      await workspacesApi.deleteMemory(workspaceId, row.id, row.version);
      setResults(null);
      await refresh();
      toast.success('已删除，历史记录仍保留');
    } catch (error) {
      toast.error(error.message);
    }
  };

  const decide = async (row, action) => {
    try {
      if (action === 'accept') await workspacesApi.acceptMemory(workspaceId, row.id, row.version);
      else await workspacesApi.ignoreMemory(workspaceId, row.id, row.version);
      await refresh();
      toast.success(t(action === 'accept' ? 'memory.accepted' : 'memory.ignored'));
    } catch (error) {
      toast.error(error.message);
    }
  };

  const promote = async row => {
    try {
      await workspacesApi.promoteMemory(workspaceId, row.id, row.version, {});
      toast.success(t('memory.promoted'));
    } catch (error) {
      toast.error(error.message);
    }
  };

  const runSearch = async () => {
    try {
      setResults(await workspacesApi.searchMemories(workspaceId, search));
    } catch (error) {
      toast.error(error.message);
    }
  };

  return (
    <Stack spacing={1.75}>
      <Alert severity="info">{t('memory.notice')}</Alert>

      <Box sx={{ p: 1.75, border: `1px solid ${googleTokens.outline}`, borderRadius: 3 }}>
        <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>
          {editing ? '编辑项目背景' : '添加项目背景'}
        </Typography>
        <Stack spacing={1.25}>
          <TextField
            select
            size="small"
            label="类别"
            value={category}
            onChange={event => setCategory(event.target.value)}
            sx={{ maxWidth: 240 }}
          >
            {Object.entries(categories).map(([value, label]) => (
              <MenuItem key={value} value={value}>
                {label}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            multiline
            minRows={3}
            label="内容"
            value={content}
            onChange={event => setContent(event.target.value)}
            slotProps={{ htmlInput: { maxLength: 300 } }}
            helperText={`${content.length} / 300`}
          />

          <Stack direction="row" spacing={1}>
            <Button variant="contained" loading={busy} onClick={save}>
              {editing ? '保存修改' : '添加背景'}
            </Button>
            {editing && (
              <Button
                color="inherit"
                onClick={() => {
                  setEditing(null);
                  setContent('');
                }}
              >
                取消编辑
              </Button>
            )}
          </Stack>
        </Stack>
      </Box>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
        <TextField
          select
          size="small"
          label={t('memory.type')}
          value={typeFilter}
          onChange={event => setTypeFilter(event.target.value)}
          sx={{ minWidth: 150 }}
        >
          <MenuItem value="">{t('memory.allTypes')}</MenuItem>
          <MenuItem value="SEMANTIC">{t('memory.type.SEMANTIC')}</MenuItem>
          <MenuItem value="EPISODIC">{t('memory.type.EPISODIC')}</MenuItem>
          <MenuItem value="PROCEDURAL">{t('memory.type.PROCEDURAL')}</MenuItem>
        </TextField>
        <TextField
          select
          size="small"
          label={t('memory.status')}
          value={statusFilter}
          onChange={event => setStatusFilter(event.target.value)}
          sx={{ minWidth: 150 }}
        >
          <MenuItem value="">{t('memory.allStatuses')}</MenuItem>
          <MenuItem value="ACTIVE">{t('memory.status.ACTIVE')}</MenuItem>
          <MenuItem value="CANDIDATE">{t('memory.status.CANDIDATE')}</MenuItem>
          <MenuItem value="ARCHIVED">{t('memory.status.ARCHIVED')}</MenuItem>
        </TextField>
        <TextField
          size="small"
          label="按内容查找项目背景"
          value={search}
          onChange={event => setSearch(event.target.value)}
          sx={{ flex: 1 }}
          slotProps={{ htmlInput: { maxLength: 100 } }}
        />
        <Button variant="outlined" onClick={runSearch} disabled={!search.trim()}>
          查找
        </Button>
        {results && (
          <Button
            color="inherit"
            onClick={() => {
              setResults(null);
              setSearch('');
            }}
          >
            显示全部
          </Button>
        )}
      </Stack>

      <Stack spacing={1}>
        {rows.map(row => (
          <Box key={row.id} sx={{ p: 1.5, borderBottom: `1px solid ${googleTokens.outline}` }}>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1}
              sx={{ justifyContent: 'space-between' }}
            >
              <Box sx={{ minWidth: 0 }}>
                <Stack direction="row" spacing={0.6} sx={{ alignItems: 'center', mb: 0.4 }}>
                  <Chip
                    size="small"
                    label={t(`memory.type.${row.memoryType || 'SEMANTIC'}`)}
                    sx={{ bgcolor: '#E8F0FE', color: '#174EA6' }}
                  />
                  <Chip size="small" variant="outlined" label={t(`memory.status.${row.status}`)} />
                  <Typography variant="caption" color="text.secondary">
                    {categories[row.category] || row.category} · v{row.version}
                    {row.score != null ? ` · score ${Number(row.score).toFixed(2)}` : ''}
                  </Typography>
                </Stack>
                <Typography
                  variant="body2"
                  sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
                >
                  {row.content}
                </Typography>
                {row.matchedReason && (
                  <Typography variant="caption" color="text.secondary">
                    {t('memory.matchedReason')}：{row.matchedReason}
                  </Typography>
                )}
              </Box>
              <Stack direction="row" spacing={0.4} sx={{ flexShrink: 0, flexWrap: 'wrap' }}>
                {row.status === 'CANDIDATE' && (
                  <>
                    <Button size="small" variant="contained" onClick={() => decide(row, 'accept')}>
                      {t('memory.accept')}
                    </Button>
                    <Button size="small" color="inherit" onClick={() => decide(row, 'ignore')}>
                      {t('memory.ignore')}
                    </Button>
                    <Button size="small" variant="outlined" onClick={() => promote(row)}>
                      {t('memory.promote')}
                    </Button>
                  </>
                )}
                <Button size="small" startIcon={<HistoryRounded />} onClick={() => setHistory(row)}>
                  历史
                </Button>
                <Button
                  size="small"
                  onClick={() => {
                    setEditing(row);
                    setCategory(row.category);
                    setContent(row.content);
                  }}
                >
                  编辑
                </Button>
                <Button size="small" color="error" onClick={() => remove(row)}>
                  删除
                </Button>
              </Stack>
            </Stack>
          </Box>
        ))}
        {(memories.isLoading || memories.isError) && (
          <Typography variant="body2" color={memories.isError ? 'error.main' : 'text.secondary'}>
            {memories.isError ? memories.error.message : '正在读取项目背景…'}
          </Typography>
        )}
        {!memories.isLoading && rows.length === 0 && !memories.isError && (
          <EmptyState
            compact
            title={results ? '没有找到匹配的背景' : '还没有项目背景'}
            description="项目约定、背景和注意事项可在任务中显式授权读取。"
          />
        )}
      </Stack>

      {history && (
        <MemoryHistoryDialog
          open
          workspaceId={workspaceId}
          memoryId={history.id}
          onClose={() => setHistory(null)}
        />
      )}
    </Stack>
  );
}
