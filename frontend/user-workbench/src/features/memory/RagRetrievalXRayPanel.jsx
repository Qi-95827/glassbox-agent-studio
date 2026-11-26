import { useMemo } from 'react';
import { Box, Chip, Stack, Typography } from '@mui/material';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

const barWidth = score => {
  const value = Math.max(0, Math.min(1, Number(score) || 0));
  return `${Math.round(value * 100)}%`;
};

function ScoreRow({ title, score, suffix }) {
  return (
    <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', minWidth: 0 }}>
      <Typography
        noWrap
        sx={{ fontSize: 11.5, flex: 1, minWidth: 0, color: googleTokens.text }}
        title={title}
      >
        {title}
      </Typography>
      <Box sx={{ width: 92, height: 6, borderRadius: 9999, bgcolor: '#EDEFF2', flexShrink: 0 }}>
        <Box
          sx={{
            width: barWidth(score),
            height: '100%',
            borderRadius: 9999,
            bgcolor: '#1A73E8'
          }}
        />
      </Box>
      <Typography
        sx={{ fontSize: 11, width: 62, textAlign: 'right', flexShrink: 0, color: '#5F6368' }}
      >
        {suffix}
      </Typography>
    </Stack>
  );
}

/**
 * Hybrid RAG 召回透视。
 *
 * <p>三段漏斗全部来自服务端同一次检索的返回：词法 Top-K、向量 Top-K、RRF 融合后的最终
 * 入选/裁剪。这里不重算任何分数，只负责把 {@code bm25 / vector / rrf} 与
 * {@code droppedReason} 画成可对照的形状。
 */
export default function RagRetrievalXRayPanel({ evidence }) {
  const { t } = useI18n();

  const lexical = useMemo(
    () =>
      [...(evidence?.items ?? [])]
        .filter(item => (Number(item.bm25) || 0) > 0)
        .sort((left, right) => right.bm25 - left.bm25)
        .slice(0, 5),
    [evidence]
  );
  const vector = useMemo(
    () =>
      [...(evidence?.items ?? [])]
        .filter(item => (Number(item.vector) || 0) > 0)
        .sort((left, right) => right.vector - left.vector)
        .slice(0, 5),
    [evidence]
  );
  const fused = useMemo(
    () => [...(evidence?.items ?? [])].sort((left, right) => right.score - left.score),
    [evidence]
  );

  if (!evidence) {
    return (
      <Typography variant="caption" color="text.secondary" data-testid="xray-empty">
        {t('xray.empty')}
      </Typography>
    );
  }
  if ((evidence.items ?? []).length === 0) {
    return (
      <Typography variant="caption" color="text.secondary" data-testid="xray-empty">
        {t('xray.noCandidates')}
      </Typography>
    );
  }

  return (
    <Box data-testid="rag-xray">
      <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap', rowGap: 0.5, mb: 1 }}>
        <Chip
          size="small"
          label={t('xray.query', { query: evidence.query ?? '' })}
          sx={{ height: 20, fontSize: 11 }}
        />
        <Chip
          size="small"
          label={evidence.vectorEnabled ? t('xray.vectorOn') : t('xray.vectorOff')}
          sx={{
            height: 20,
            fontSize: 11,
            bgcolor: evidence.vectorEnabled ? '#E6F4EA' : '#F1F3F4',
            color: evidence.vectorEnabled ? '#137333' : '#5F6368'
          }}
        />
        <Chip
          size="small"
          label={t('xray.selected', { count: evidence.selectedCount ?? 0 })}
          sx={{ height: 20, fontSize: 11 }}
        />
        <Chip
          size="small"
          label={t('xray.injectedTokens', { tokens: evidence.injectedTokens ?? 0 })}
          sx={{ height: 20, fontSize: 11 }}
        />
      </Stack>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
          gap: 1.25
        }}
      >
        <Box data-testid="xray-lexical">
          <Typography variant="caption" sx={{ fontWeight: 700, display: 'block', mb: 0.4 }}>
            {t('xray.lexicalTitle')}
          </Typography>
          {lexical.length === 0 ? (
            <Typography variant="caption" color="text.secondary">
              {t('xray.noLexical')}
            </Typography>
          ) : (
            <Stack spacing={0.4}>
              {lexical.map(item => (
                <ScoreRow
                  key={`l-${item.chunkId}`}
                  title={item.sectionTitle || item.title || item.chunkId}
                  score={item.bm25}
                  suffix={Number(item.bm25).toFixed(2)}
                />
              ))}
            </Stack>
          )}
        </Box>

        <Box data-testid="xray-vector">
          <Typography variant="caption" sx={{ fontWeight: 700, display: 'block', mb: 0.4 }}>
            {t('xray.vectorTitle')}
          </Typography>
          {vector.length === 0 ? (
            <Typography variant="caption" color="text.secondary">
              {t('xray.noVector')}
            </Typography>
          ) : (
            <Stack spacing={0.4}>
              {vector.map(item => (
                <ScoreRow
                  key={`v-${item.chunkId}`}
                  title={item.sectionTitle || item.title || item.chunkId}
                  score={item.vector}
                  suffix={Number(item.vector).toFixed(2)}
                />
              ))}
            </Stack>
          )}
        </Box>
      </Box>

      <Box sx={{ mt: 1.25 }} data-testid="xray-fused">
        <Typography variant="caption" sx={{ fontWeight: 700, display: 'block', mb: 0.4 }}>
          {t('xray.fusedTitle')}
        </Typography>
        <Stack spacing={0.4}>
          {fused.map(item => (
            <Stack
              key={`f-${item.chunkId}`}
              direction="row"
              spacing={0.75}
              sx={{ alignItems: 'center', minWidth: 0 }}
            >
              <Chip
                size="small"
                label={item.selected ? t('xray.injected') : t('xray.dropped')}
                data-testid={`xray-${item.selected ? 'injected' : 'dropped'}`}
                sx={{
                  height: 18,
                  fontSize: 10,
                  fontWeight: 600,
                  bgcolor: item.selected ? '#E6F4EA' : '#F1F3F4',
                  color: item.selected ? '#137333' : '#5F6368'
                }}
              />
              <ScoreRow
                title={item.sectionTitle || item.title || item.chunkId}
                score={item.rrf}
                suffix={Number(item.score).toFixed(3)}
              />
              {!item.selected && item.droppedReason ? (
                <Chip
                  size="small"
                  label={t(`xray.reason.${item.droppedReason}`)}
                  sx={{ height: 18, fontSize: 10, bgcolor: '#FEF7E0', color: '#B06000' }}
                />
              ) : null}
            </Stack>
          ))}
        </Stack>
      </Box>
    </Box>
  );
}
