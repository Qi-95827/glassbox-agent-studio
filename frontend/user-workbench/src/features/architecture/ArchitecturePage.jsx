import { useEffect, useState } from 'react';
import {
  Box,
  Button,
  Chip,
  Collapse,
  Divider,
  List,
  ListItemButton,
  Stack,
  ToggleButton,
  ToggleButtonGroup,
  Typography
} from '@mui/material';
import ArrowBackRounded from '@mui/icons-material/ArrowBackRounded';
import ArrowForwardRounded from '@mui/icons-material/ArrowForwardRounded';
import KeyboardArrowDownRounded from '@mui/icons-material/KeyboardArrowDownRounded';
import KeyboardArrowRightRounded from '@mui/icons-material/KeyboardArrowRightRounded';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { usePageChrome } from '../../layout/pageChrome';
import ArchitectureCanvas from './ArchitectureCanvas';
import ArchitectureXrayPanel from './ArchitectureXrayPanel';
import {
  ALL_ARCHITECTURE_TOPICS,
  ARCHITECTURE_GROUPS,
  ARCHITECTURE_TOPICS,
  getArchitectureTopic,
  getChildTopics,
  getParentTopic,
  pickText
} from './architectureCatalog';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

/**
 * 架构图谱独立页面：左侧可折叠二级树 + 右侧单图聚焦与 C4 渐进式下钻画布。
 */
export default function ArchitecturePage() {
  const { t, locale } = useI18n();
  const [params, setParams] = useSearchParams();
  const activeTopic = getArchitectureTopic(params.get('topic'));
  const parentTopic = getParentTopic(activeTopic);
  const activeBranchId = parentTopic ? parentTopic.id : activeTopic.id;
  const navigate = useNavigate();
  // 实时 X 光模式挂在 URL 上：可分享、可刷新，默认仍是静态教学图。
  const mode = params.get('mode') === 'xray' ? 'xray' : 'static';
  const setMode = next => {
    const copy = new URLSearchParams(params);
    if (next === 'xray') copy.set('mode', 'xray');
    else copy.delete('mode');
    setParams(copy, { replace: true });
  };

  // 默认只展开当前正在浏览的主图分支，其余收起，保持极简呼吸感
  const [expandedId, setExpandedId] = useState(activeBranchId);

  useEffect(() => {
    setExpandedId(activeBranchId);
  }, [activeBranchId]);

  const activeIndex = ALL_ARCHITECTURE_TOPICS.findIndex(item => item.id === activeTopic.id);
  const prevTopic = activeIndex > 0 ? ALL_ARCHITECTURE_TOPICS[activeIndex - 1] : null;
  const nextTopic =
    activeIndex >= 0 && activeIndex < ALL_ARCHITECTURE_TOPICS.length - 1
      ? ALL_ARCHITECTURE_TOPICS[activeIndex + 1]
      : null;

  usePageChrome({
    trail: [
      { label: t('nav.workspace') },
      { label: t('nav.architecture') },
      ...(parentTopic ? [{ label: pickText(parentTopic.shortTitle, locale) }] : []),
      { label: pickText(activeTopic.shortTitle, locale) }
    ]
  });

  const selectTopic = id => {
    const next = new URLSearchParams(params);
    next.set('topic', id);
    setParams(next, { replace: true });
  };

  // 实时 X 光：用真实运行的落库轨迹点亮模块，静态教学图仍是一等公民（默认分支）。
  if (mode === 'xray') {
    const modeSwitch = (
      <ToggleButtonGroup
        exclusive
        size="small"
        value={mode}
        onChange={(_, value) => value && setMode(value)}
      >
        <ToggleButton value="static" data-testid="architecture-mode-static">
          {t('architecture.mode.static')}
        </ToggleButton>
        <ToggleButton value="xray" data-testid="architecture-mode-xray">
          {t('architecture.mode.xray')}
        </ToggleButton>
      </ToggleButtonGroup>
    );
    return (
      <Box
        data-testid="architecture-xray-mode"
        sx={{ height: '100%', overflow: 'auto', p: 2, bgcolor: googleTokens.canvas }}
      >
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1.5 }}>
          {modeSwitch}
          <Typography variant="caption" color="text.secondary">
            {t('architecture.xrayHint')}
          </Typography>
        </Stack>
        <ArchitectureXrayPanel
          initialRunId={params.get('run') ?? ''}
          onOpenRun={(runId, nodeId, tab) =>
            navigate(
              `/runs/${runId}?node=${encodeURIComponent(nodeId)}&glassbox=${encodeURIComponent(tab)}`
            )
          }
        />
      </Box>
    );
  }

  return (
    <Box sx={{ height: '100%', display: 'flex', minHeight: 0, bgcolor: googleTokens.canvas }}>
      {/* 左侧可折叠二级目录树（桌面端） */}
      <Box
        sx={{
          display: { xs: 'none', md: 'flex' },
          flexDirection: 'column',
          width: 276,
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
        <ToggleButtonGroup
          exclusive
          size="small"
          value={mode}
          onChange={(_, value) => value && setMode(value)}
          sx={{ px: 1, mb: 1 }}
        >
          <ToggleButton value="static" data-testid="architecture-mode-static">
            {t('architecture.mode.static')}
          </ToggleButton>
          <ToggleButton value="xray" data-testid="architecture-mode-xray">
            {t('architecture.mode.xray')}
          </ToggleButton>
        </ToggleButtonGroup>
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
                    const children = getChildTopics(item.id);
                    const selected = item.id === activeTopic.id;
                    const branchExpanded = expandedId === item.id;
                    return (
                      <Box key={item.id} sx={{ mb: 0.4 }}>
                        <ListItemButton
                          selected={selected}
                          onClick={() => {
                            selectTopic(item.id);
                            if (children.length > 0) setExpandedId(item.id);
                          }}
                          sx={{
                            borderRadius: 2.5,
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
                              flex: 1,
                              minWidth: 0,
                              fontSize: 13,
                              fontWeight: selected ? 600 : 500,
                              lineHeight: 1.35
                            }}
                          >
                            {pickText(item.shortTitle, locale)}
                          </Typography>
                          {children.length > 0 && (
                            <Stack
                              direction="row"
                              spacing={0.25}
                              sx={{ alignItems: 'center', color: 'text.secondary' }}
                              onClick={event => {
                                event.stopPropagation();
                                setExpandedId(prev => (prev === item.id ? null : item.id));
                              }}
                            >
                              <Typography sx={{ fontSize: 10.5, fontWeight: 600 }}>
                                {children.length}
                              </Typography>
                              {branchExpanded ? (
                                <KeyboardArrowDownRounded sx={{ fontSize: 16 }} />
                              ) : (
                                <KeyboardArrowRightRounded sx={{ fontSize: 16 }} />
                              )}
                            </Stack>
                          )}
                        </ListItemButton>

                        {children.length > 0 && (
                          <Collapse in={branchExpanded} timeout="auto" unmountOnExit>
                            <Box
                              sx={{
                                ml: 2.25,
                                pl: 1.25,
                                mt: 0.35,
                                mb: 0.4,
                                borderLeft: `1.5px solid ${googleTokens.outline}`
                              }}
                            >
                              {children.map(sub => {
                                const subSelected = sub.id === activeTopic.id;
                                return (
                                  <ListItemButton
                                    key={sub.id}
                                    selected={subSelected}
                                    onClick={() => selectTopic(sub.id)}
                                    sx={{
                                      borderRadius: 2,
                                      mb: 0.3,
                                      px: 1,
                                      py: 0.6,
                                      gap: 0.75,
                                      '&.Mui-selected': {
                                        bgcolor: '#F3E8FD',
                                        color: '#681DA8',
                                        '&:hover': { bgcolor: '#E9D5FB' }
                                      }
                                    }}
                                  >
                                    <Typography
                                      sx={{
                                        fontSize: 10.5,
                                        fontWeight: 700,
                                        color: subSelected ? '#681DA8' : '#80868B',
                                        minWidth: 26
                                      }}
                                    >
                                      {sub.badge}
                                    </Typography>
                                    <Typography
                                      sx={{
                                        fontSize: 12.25,
                                        fontWeight: subSelected ? 600 : 500,
                                        lineHeight: 1.35
                                      }}
                                    >
                                      {pickText(sub.shortTitle, locale)}
                                    </Typography>
                                  </ListItemButton>
                                );
                              })}
                            </Box>
                          </Collapse>
                        )}
                      </Box>
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
            {ALL_ARCHITECTURE_TOPICS.map(item => {
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

          <ArchitectureCanvas topicId={activeTopic.id} onNavigateTopic={selectTopic} />

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
              {activeIndex + 1} / {ALL_ARCHITECTURE_TOPICS.length}
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
