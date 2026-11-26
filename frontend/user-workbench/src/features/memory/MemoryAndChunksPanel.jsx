import { useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Stack,
  Tab,
  Tabs,
  Typography
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { memoryApi } from '../../api/memory';
import { useToast } from '../../components/ToastProvider';
import EmptyState from '../../components/EmptyState';
import { googleTokens } from '../../theme/googleTheme';
import { dateTime } from '../../utils/format';
import { useI18n } from '../../i18n/I18nProvider';

/** 三层记忆的固定展示顺序；与后端 MemoryType 枚举一致。 */
const MEMORY_TYPES = ['SEMANTIC', 'EPISODIC', 'PROCEDURAL'];

/**
 * 三层记忆与工作区代码切片透视。
 *
 * <p>记忆条目与分块预览都直接来自受归属校验的只读接口：
 * <ul>
 *   <li>记忆按 {@code memoryType} 分组，显示来源运行、命中次数与状态，并支持人工放行/冻结；</li>
 *   <li>切片按文档分组，显示每块的序号、章节标题、Token 估算与字符数，预览由后端截断。</li>
 * </ul>
 * 冻结（ignore）与放行（accept）都会带上版本号，服务端据此拒绝并发覆盖。
 */
export default function MemoryAndChunksPanel({ workspaceId }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState('memory');
  const [busy, setBusy] = useState(null);
  const [documentId, setDocumentId] = useState(null);

  const memories = useQuery({
    queryKey: ['memory-insight', workspaceId],
    queryFn: () => memoryApi.memories(workspaceId),
    enabled: Boolean(workspaceId)
  });
  const documents = useQuery({
    queryKey: ['memory-insight-documents', workspaceId],
    queryFn: () => memoryApi.knowledgeDocuments(workspaceId),
    enabled: Boolean(workspaceId)
  });
  const chunks = useQuery({
    queryKey: ['memory-insight-chunks', workspaceId, documentId],
    queryFn: () => memoryApi.knowledgeChunks(workspaceId, documentId),
    enabled: Boolean(workspaceId && documentId)
  });

  const grouped = useMemo(() => {
    const map = new Map(MEMORY_TYPES.map(type => [type, []]));
    (memories.data ?? []).forEach(row => {
      const type = row.memoryType ?? 'SEMANTIC';
      if (!map.has(type)) map.set(type, []);
      map.get(type).push(row);
    });
    return map;
  }, [memories.data]);

  const act = async (row, action) => {
    setBusy(row.id);
    try {
      if (action === 'accept') await memoryApi.acceptMemory(workspaceId, row.id, row.version);
      else await memoryApi.ignoreMemory(workspaceId, row.id, row.version);
      toast.success(t(action === 'accept' ? 'memoryInsight.accepted' : 'memoryInsight.frozen'));
      await queryClient.invalidateQueries({ queryKey: ['memory-insight', workspaceId] });
    } catch (failure) {
      toast.error(failure?.message || t('memoryInsight.actionFailed'));
    } finally {
      setBusy(null);
    }
  };

  return (
    <Box data-testid="memory-and-chunks">
      <Tabs
        value={tab}
        onChange={(_, value) => setTab(value)}
        sx={{ borderBottom: `1px solid ${googleTokens.outline}`, mb: 1.5, minHeight: 36 }}
      >
        <Tab value="memory" label={t('memoryInsight.tabMemory')} sx={{ minHeight: 36 }} />
        <Tab value="chunks" label={t('memoryInsight.tabChunks')} sx={{ minHeight: 36 }} />
      </Tabs>

      {tab === 'memory' && (
        <Box>
          {memories.isLoading && <CircularProgress size={18} />}
          {memories.error && (
            <Alert severity="error">
              {t('memoryInsight.loadFailed', { message: memories.error.message })}
            </Alert>
          )}
          {!memories.isLoading && (memories.data ?? []).length === 0 && (
            <EmptyState
              compact
              title={t('memoryInsight.emptyMemoryTitle')}
              description={t('memoryInsight.emptyMemoryDescription')}
            />
          )}
          <Stack spacing={1.5}>
            {MEMORY_TYPES.filter(type => (grouped.get(type) ?? []).length > 0).map(type => (
              <Box key={type}>
                <Typography variant="caption" sx={{ fontWeight: 700, display: 'block', mb: 0.5 }}>
                  {t(`memory.type.${type}`)} · {(grouped.get(type) ?? []).length}
                </Typography>
                <Stack spacing={0.5}>
                  {(grouped.get(type) ?? []).map(row => (
                    <Box
                      key={row.id}
                      data-testid={`memory-row-${row.id}`}
                      sx={{
                        p: 1,
                        borderRadius: 2,
                        border: `1px solid ${googleTokens.outline}`,
                        bgcolor: '#FFFFFF'
                      }}
                    >
                      <Stack
                        direction="row"
                        spacing={0.6}
                        sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.4, mb: 0.4 }}
                      >
                        <Chip
                          size="small"
                          label={t(`memory.status.${row.status}`)}
                          sx={{ height: 18, fontSize: 10 }}
                        />
                        <Chip
                          size="small"
                          variant="outlined"
                          label={t('memoryInsight.hits', { count: row.hitCount ?? 0 })}
                          sx={{ height: 18, fontSize: 10 }}
                        />
                        {row.sourceRunId && (
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{ fontFamily: googleTokens.mono, fontSize: 10.5 }}
                          >
                            #{String(row.sourceRunId).slice(0, 8)}
                          </Typography>
                        )}
                        <Typography variant="caption" color="text.secondary">
                          {dateTime(row.updatedAt ?? row.createdAt, locale)}
                        </Typography>
                        <Box sx={{ flex: 1 }} />
                        <Button
                          size="small"
                          disabled={busy === row.id}
                          onClick={() => act(row, 'accept')}
                          sx={{ minHeight: 22, py: 0, fontSize: 11 }}
                        >
                          {t('memoryInsight.unfreeze')}
                        </Button>
                        <Button
                          size="small"
                          color="warning"
                          disabled={busy === row.id}
                          onClick={() => act(row, 'ignore')}
                          sx={{ minHeight: 22, py: 0, fontSize: 11 }}
                        >
                          {t('memoryInsight.freeze')}
                        </Button>
                      </Stack>
                      <Typography variant="body2" sx={{ fontSize: 12.5, whiteSpace: 'pre-wrap' }}>
                        {row.content}
                      </Typography>
                    </Box>
                  ))}
                </Stack>
              </Box>
            ))}
          </Stack>
        </Box>
      )}

      {tab === 'chunks' && (
        <Box>
          {documents.isLoading && <CircularProgress size={18} />}
          {!documents.isLoading && (documents.data ?? []).length === 0 && (
            <EmptyState
              compact
              title={t('memoryInsight.emptyDocumentTitle')}
              description={t('memoryInsight.emptyDocumentDescription')}
            />
          )}
          <Stack spacing={1}>
            {(documents.data ?? []).map(document => (
              <Box key={document.id}>
                <Stack
                  direction="row"
                  spacing={0.75}
                  sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.4 }}
                >
                  <Typography sx={{ fontSize: 12.5, fontWeight: 600 }}>{document.title}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t('knowledge.chunkCount', { count: document.chunkCount ?? 0 })}
                  </Typography>
                  <Box sx={{ flex: 1 }} />
                  <Button
                    size="small"
                    sx={{ minHeight: 22, py: 0, fontSize: 11 }}
                    onClick={() =>
                      setDocumentId(current => (current === document.id ? null : document.id))
                    }
                  >
                    {documentId === document.id
                      ? t('memoryInsight.hideChunks')
                      : t('memoryInsight.showChunks')}
                  </Button>
                </Stack>
                {documentId === document.id && (
                  <Box sx={{ mt: 0.75 }} data-testid={`chunk-list-${document.id}`}>
                    {chunks.isLoading && <CircularProgress size={16} />}
                    {chunks.error && (
                      <Alert severity="error">
                        {t('memoryInsight.loadFailed', { message: chunks.error.message })}
                      </Alert>
                    )}
                    <Stack spacing={0.5}>
                      {(chunks.data ?? []).map(chunk => (
                        <Box
                          key={`${document.id}-${chunk.index}`}
                          sx={{
                            p: 0.9,
                            borderRadius: 2,
                            bgcolor: '#FAFAFA',
                            border: `1px solid ${googleTokens.outline}`
                          }}
                        >
                          <Stack
                            direction="row"
                            spacing={0.6}
                            sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.35 }}
                          >
                            <Chip
                              size="small"
                              label={`#${chunk.index}`}
                              sx={{ height: 18, fontSize: 10, fontFamily: googleTokens.mono }}
                            />
                            {chunk.sectionTitle && (
                              <Typography variant="caption" sx={{ fontWeight: 600 }}>
                                {chunk.sectionTitle}
                              </Typography>
                            )}
                            <Typography variant="caption" color="text.secondary">
                              {t('memoryInsight.chunkMeta', {
                                tokens: chunk.tokenEstimate,
                                chars: chunk.charCount
                              })}
                            </Typography>
                            {chunk.previewTruncated && (
                              <Chip
                                size="small"
                                label={t('memoryInsight.truncated')}
                                sx={{ height: 18, fontSize: 10 }}
                              />
                            )}
                          </Stack>
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{
                              display: 'block',
                              mt: 0.35,
                              fontFamily: googleTokens.mono,
                              whiteSpace: 'pre-wrap'
                            }}
                          >
                            {chunk.preview}
                          </Typography>
                        </Box>
                      ))}
                    </Stack>
                  </Box>
                )}
                <Divider sx={{ mt: 1 }} />
              </Box>
            ))}
          </Stack>
        </Box>
      )}
    </Box>
  );
}
