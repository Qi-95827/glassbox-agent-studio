import { Box, Button, Chip, Divider, List, ListItemButton, Stack, Typography } from '@mui/material';
import ArrowBackRounded from '@mui/icons-material/ArrowBackRounded';
import ArrowForwardRounded from '@mui/icons-material/ArrowForwardRounded';
import { useSearchParams } from 'react-router-dom';
import { usePageChrome } from '../../layout/pageChrome';
import ArchitectureCanvas from './ArchitectureCanvas';
import {
  ARCHITECTURE_GROUPS,
  ARCHITECTURE_TOPICS,
  getArchitectureTopic,
  pickText
} from './architectureCatalog';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

/**
 * 架构图谱独立页面：左侧分组目录 + 右侧单图聚焦画布。
 */
export default function ArchitecturePage() {
  const { t, locale } = useI18n();
  const [params, setParams] = useSearchParams();
  const activeTopic = getArchitectureTopic(params.get('topic'));
  const activeIndex = ARCHITECTURE_TOPICS.findIndex(item => item.id === activeTopic.id);
  const prevTopic = activeIndex > 0 ? ARCHITECTURE_TOPICS[activeIndex - 1] : null;
  const nextTopic =
    activeIndex >= 0 && activeIndex < ARCHITECTURE_TOPICS.length - 1
      ? ARCHITECTURE_TOPICS[activeIndex + 1]
      : null;

  usePageChrome({
    trail: [
      { label: t('nav.workspace') },
      { label: t('nav.architecture') },
      { label: pickText(activeTopic.shortTitle, locale) }
    ]
  });

  const selectTopic = id => {
    const next = new URLSearchParams(params);
    next.set('topic', id);
    setParams(next, { replace: true });
  };

  return (
    <Box sx={{ height: '100%', display: 'flex', minHeight: 0, bgcolor: googleTokens.canvas }}>
      {/* 左侧分组目录（桌面端） */}
      <Box
        sx={{
          display: { xs: 'none', md: 'flex' },
          flexDirection: 'column',
          width: 268,
          flexShrink: 0,
          bgcolor: '#FFFFFF',
          borderRight: `1px solid ${googleTokens.outline}`,
          overflowY: 'auto',
          p: 2
        }}
      >
        <Typography sx={{ fontSize: 15, fontWeight: 600, px: 1, mb: 0.35 }}>
          {t('architecture.pageTitle')}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ px: 1, mb: 2, lineHeight: 1.5 }}>
          {t('architecture.pageSubtitle')}
        </Typography>

        <Stack spacing={2}>
          {ARCHITECTURE_GROUPS.map(group => {
            const items = ARCHITECTURE_TOPICS.filter(item => item.group === group.id);
            return (
              <Box key={group.id}>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{
                    px: 1,
                    mb: 0.75,
                    display: 'block',
                    fontWeight: 600,
                    letterSpacing: '0.02em',
                    textTransform: 'uppercase',
                    fontSize: 11
                  }}
                >
                  {pickText(group.title, locale)}
                </Typography>
                <List dense disablePadding>
                  {items.map(item => {
                    const selected = item.id === activeTopic.id;
                    return (
                      <ListItemButton
                        key={item.id}
                        selected={selected}
                        onClick={() => selectTopic(item.id)}
                        sx={{
                          borderRadius: 2.5,
                          mb: 0.4,
                          px: 1.1,
                          py: 0.85,
                          alignItems: 'center',
                          gap: 1,
                          '&.Mui-selected': {
                            bgcolor: '#E8F0FE',
                            color: '#1967D2',
                            '&:hover': { bgcolor: '#D2E3FC' }
                          }
                        }}
                      >
                        <Box
                          sx={{
                            minWidth: 24,
                            height: 20,
                            borderRadius: 1.5,
                            display: 'grid',
                            placeItems: 'center',
                            fontSize: 10.5,
                            fontWeight: 700,
                            bgcolor: selected ? '#FFFFFF' : '#F1F3F4',
                            color: selected ? '#1967D2' : '#5F6368'
                          }}
                        >
                          {item.badge}
                        </Box>
                        <Typography
                          sx={{
                            fontSize: 13,
                            fontWeight: selected ? 600 : 500,
                            lineHeight: 1.35
                          }}
                        >
                          {pickText(item.shortTitle, locale)}
                        </Typography>
                      </ListItemButton>
                    );
                  })}
                </List>
              </Box>
            );
          })}
        </Stack>
      </Box>

      {/* 右侧单图聚焦画布 */}
      <Box sx={{ flex: 1, minWidth: 0, overflowY: 'auto' }}>
        <Box sx={{ maxWidth: 1080, mx: 'auto', px: { xs: 2, sm: 3.5 }, py: { xs: 2.5, md: 3.5 } }}>
          {/* 窄屏横向快速切换条 */}
          <Stack
            direction="row"
            spacing={0.75}
            sx={{
              display: { xs: 'flex', md: 'none' },
              overflowX: 'auto',
              pb: 1.5,
              mb: 1.5
            }}
          >
            {ARCHITECTURE_TOPICS.map(item => {
              const selected = item.id === activeTopic.id;
              return (
                <Chip
                  key={item.id}
                  clickable
                  onClick={() => selectTopic(item.id)}
                  label={`${item.badge} · ${pickText(item.shortTitle, locale)}`}
                  sx={{
                    flexShrink: 0,
                    bgcolor: selected ? '#E8F0FE' : '#FFFFFF',
                    color: selected ? '#1967D2' : '#3C4043',
                    border: `1px solid ${selected ? '#AECBFA' : googleTokens.outline}`,
                    fontWeight: selected ? 600 : 500
                  }}
                />
              );
            })}
          </Stack>

          <ArchitectureCanvas topicId={activeTopic.id} />

          <Divider sx={{ my: 3 }} />

          {/* 底部顺滑翻页导航 */}
          <Stack
            direction="row"
            spacing={1.5}
            sx={{ justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap' }}
          >
            {prevTopic ? (
              <Button
                variant="outlined"
                size="small"
                startIcon={<ArrowBackRounded sx={{ fontSize: 16 }} />}
                onClick={() => selectTopic(prevTopic.id)}
                sx={{ borderRadius: 9999, textTransform: 'none', px: 1.75 }}
              >
                {prevTopic.badge} · {pickText(prevTopic.shortTitle, locale)}
              </Button>
            ) : (
              <Box />
            )}

            <Typography variant="caption" color="text.secondary">
              {activeIndex + 1} / {ARCHITECTURE_TOPICS.length}
            </Typography>

            {nextTopic ? (
              <Button
                variant="outlined"
                size="small"
                endIcon={<ArrowForwardRounded sx={{ fontSize: 16 }} />}
                onClick={() => selectTopic(nextTopic.id)}
                sx={{ borderRadius: 9999, textTransform: 'none', px: 1.75 }}
              >
                {nextTopic.badge} · {pickText(nextTopic.shortTitle, locale)}
              </Button>
            ) : (
              <Box />
            )}
          </Stack>
        </Box>
      </Box>
    </Box>
  );
}
