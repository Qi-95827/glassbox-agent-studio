import { useState } from 'react';
import { Alert, Box, Button, Chip, Paper, Stack, TextField, Typography } from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { workspacesApi } from '../../api/workspaces';
import { memoryApi } from '../../api/memory';
import RagRetrievalXRayPanel from '../memory/RagRetrievalXRayPanel';
import { useToast } from '../../components/ToastProvider';
import EmptyState from '../../components/EmptyState';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';
import { dateTime } from '../../utils/format';

/** 工作区知识库：索引参考文档、查看文章分块状态并做混合检索。 */
export default function WorkspaceKnowledgePanel({ workspaceId }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [title, setTitle] = useState('');
  const [sourcePath, setSourcePath] = useState('');
  const [content, setContent] = useState('');
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState(null);
  const [xray, setXray] = useState(null);
  const [busy, setBusy] = useState(false);

  const documents = useQuery({
    queryKey: ['workspace-knowledge', workspaceId],
    queryFn: () => workspacesApi.knowledge(workspaceId)
  });

  const refresh = () =>
    queryClient.invalidateQueries({ queryKey: ['workspace-knowledge', workspaceId] });

  const submit = async () => {
    if (!content.trim()) {
      toast.warning(t('knowledge.contentRequired'));
      return;
    }
    setBusy(true);
    try {
      const result = await workspacesApi.indexKnowledge(workspaceId, {
        title: title.trim() || undefined,
        sourcePath: sourcePath.trim() || undefined,
        content,
        sourceType: 'DOCUMENT'
      });
      toast.success(
        t(result.reindexed ? 'knowledge.reindexed' : 'knowledge.indexed', {
          count: result.document.chunkCount
        })
      );
      setContent('');
      setTitle('');
      setSourcePath('');
      await refresh();
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy(false);
    }
  };

  const search = async () => {
    if (!query.trim()) return;
    try {
      const [result, evidence] = await Promise.all([
        workspacesApi.searchKnowledge(workspaceId, query.trim(), 5),
        // 同一次检索另取一份带 BM25/向量/RRF 明细的证据，供「召回透视」漏斗使用。
        memoryApi.knowledgeXray(workspaceId, query.trim(), 5)
      ]);
      setHits(result);
      setXray(evidence);
    } catch (error) {
      toast.error(error.message);
    }
  };

  const remove = async row => {
    try {
      await workspacesApi.deleteKnowledge(workspaceId, row.id);
      setHits(null);
      await refresh();
      toast.success(t('knowledge.deleted'));
    } catch (error) {
      toast.error(error.message);
    }
  };

  const rows = documents.data ?? [];

  return (
    <Stack spacing={1.75}>
      <Alert severity="info">{t('knowledge.notice')}</Alert>

      <Box sx={{ p: 1.75, border: `1px solid ${googleTokens.outline}`, borderRadius: 3 }}>
        <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>
          {t('knowledge.indexTitle')}
        </Typography>
        <Stack spacing={1.25}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
            <TextField
              size="small"
              label={t('knowledge.documentTitle')}
              value={title}
              onChange={event => setTitle(event.target.value)}
              slotProps={{ htmlInput: { maxLength: 300 } }}
              sx={{ flex: 1 }}
            />
            <TextField
              size="small"
              label={t('knowledge.sourcePath')}
              value={sourcePath}
              onChange={event => setSourcePath(event.target.value)}
              slotProps={{ htmlInput: { maxLength: 1000 } }}
              sx={{ flex: 1 }}
            />
          </Stack>
          <TextField
            multiline
            minRows={4}
            label={t('knowledge.content')}
            value={content}
            onChange={event => setContent(event.target.value)}
            helperText={t('knowledge.contentHint', {
              count: content.length.toLocaleString()
            })}
          />
          <Button variant="contained" loading={busy} onClick={submit} sx={{ alignSelf: 'start' }}>
            {t('knowledge.indexAction')}
          </Button>
        </Stack>
      </Box>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
        <TextField
          size="small"
          label={t('knowledge.searchLabel')}
          value={query}
          onChange={event => setQuery(event.target.value)}
          sx={{ flex: 1 }}
          slotProps={{ htmlInput: { maxLength: 300 } }}
        />
        <Button variant="outlined" onClick={search} disabled={!query.trim()}>
          {t('knowledge.searchAction')}
        </Button>
        {hits && (
          <Button
            color="inherit"
            onClick={() => {
              setHits(null);
              setXray(null);
              setQuery('');
            }}
          >
            {t('knowledge.clearSearch')}
          </Button>
        )}
      </Stack>

      {xray && (
        <Paper variant="outlined" sx={{ p: 1.5, borderRadius: 3 }}>
          <RagRetrievalXRayPanel evidence={xray} />
        </Paper>
      )}

      {hits && (
        <Stack spacing={1}>
          <Typography sx={{ fontSize: 13, fontWeight: 600 }}>
            {t('knowledge.hits', { count: hits.length })}
          </Typography>
          {hits.length === 0 && (
            <Typography variant="body2" color="text.secondary">
              {t('knowledge.noHits')}
            </Typography>
          )}
          {hits.map(hit => (
            <Box
              key={hit.chunkId}
              sx={{ p: 1.5, border: `1px solid ${googleTokens.outline}`, borderRadius: 3 }}
            >
              <Stack direction="row" spacing={0.6} useFlexGap flexWrap="wrap" sx={{ mb: 0.5 }}>
                <Chip size="small" label={hit.title || t('knowledge.untitledDocument')} />
                <Chip
                  size="small"
                  variant="outlined"
                  label={t('knowledge.scoreValue', { score: hit.score.toFixed(3) })}
                />
                {hit.sectionTitle && (
                  <Chip size="small" variant="outlined" label={hit.sectionTitle} />
                )}
              </Stack>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                {hit.sourcePath}
              </Typography>
              <Typography
                variant="body2"
                sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', mt: 0.4 }}
              >
                {hit.content}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {t('knowledge.breakdown', { detail: hit.breakdown })}
              </Typography>
            </Box>
          ))}
        </Stack>
      )}

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
                    label={
                      row.status === 'INDEXED'
                        ? t('knowledge.status.INDEXED')
                        : t('knowledge.status.unknown')
                    }
                  />
                  <Typography variant="caption" color="text.secondary">
                    {t('knowledge.chunkCount', { count: row.chunkCount })} ·{' '}
                    {dateTime(row.updatedAt, locale)}
                  </Typography>
                </Stack>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {row.title}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {row.sourcePath}
                </Typography>
              </Box>
              <Button size="small" color="error" onClick={() => remove(row)}>
                {t('knowledge.delete')}
              </Button>
            </Stack>
          </Box>
        ))}
        {documents.isLoading && (
          <Typography variant="body2" color="text.secondary">
            {t('knowledge.loading')}
          </Typography>
        )}
        {documents.isError && (
          <Typography variant="body2" color="error.main">
            {documents.error.message}
          </Typography>
        )}
        {!documents.isLoading && rows.length === 0 && !documents.isError && (
          <EmptyState
            compact
            title={t('knowledge.emptyTitle')}
            description={t('knowledge.emptyDescription')}
          />
        )}
      </Stack>
    </Stack>
  );
}
