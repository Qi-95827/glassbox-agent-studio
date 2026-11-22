import { useEffect, useState } from 'react';
import {
  Box,
  Button,
  Chip,
  Stack,
  ToggleButton,
  ToggleButtonGroup,
  Typography
} from '@mui/material';
import ArrowBackRounded from '@mui/icons-material/ArrowBackRounded';
import ArrowForwardRounded from '@mui/icons-material/ArrowForwardRounded';
import CodeRounded from '@mui/icons-material/CodeRounded';
import SubdirectoryArrowRightRounded from '@mui/icons-material/SubdirectoryArrowRightRounded';
import TouchAppOutlined from '@mui/icons-material/TouchAppOutlined';
import ArchitectureDiagram from './ArchitectureDiagrams';
import {
  getArchitectureTopic,
  getChildTopics,
  getParentTopic,
  pickText
} from './architectureCatalog';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

const SPOTLIGHT_TONES = {
  blue: { bg: '#F4F8FF', border: '#D2E3FC', chipBg: '#E8F0FE', chipFg: '#174EA6' },
  green: { bg: '#F3FAF5', border: '#CEEAD6', chipBg: '#E6F4EA', chipFg: '#137333' },
  amber: { bg: '#FFFBF0', border: '#FDE293', chipBg: '#FEF7E0', chipFg: '#B06000' },
  purple: { bg: '#FAF5FF', border: '#E4C7FB', chipBg: '#F3E8FD', chipFg: '#681DA8' },
  red: { bg: '#FEF6F5', border: '#F6AEA9', chipBg: '#FCE8E6', chipFg: '#A50E0E' }
};

/**
 * 单张架构图聚焦画布：支持 C4 渐进式下钻（主图 → 专题子图 → 返回主图）。
 */
export default function ArchitectureCanvas({ topicId, compact = false, onNavigateTopic }) {
  const { locale, t } = useI18n();
  const topic = getArchitectureTopic(topicId);
  const parentTopic = getParentTopic(topic);
  const childTopics = getChildTopics(topic.id);
  const [subView, setSubView] = useState(topic.subViews?.[0]?.id ?? null);
  const [selectedNodeId, setSelectedNodeId] = useState(topic.defaultNode);

  useEffect(() => {
    const initialSub = topic.subViews?.[0]?.id ?? null;
    setSubView(initialSub);
    setSelectedNodeId(topic.defaultNode);
  }, [topic.id, topic.defaultNode, topic.subViews]);

  const handleSubViewChange = (_, next) => {
    if (!next) return;
    setSubView(next);
    if (topic.id === 'layers-stack') {
      setSelectedNodeId(next === 'frontend' ? 'fe-features' : 'be-app');
    }
  };

  const activeNode = topic.nodes?.[selectedNodeId] ?? Object.values(topic.nodes ?? {})[0] ?? null;
  const drillTarget = activeNode?.drillTo ? getArchitectureTopic(activeNode.drillTo) : null;
  const spotlightTone = SPOTLIGHT_TONES[activeNode?.tone] ?? SPOTLIGHT_TONES.blue;

  return (
    <Stack spacing={compact ? 2 : 2.5}>
      {/* 子图顶部的返航面包屑 */}
      {parentTopic && onNavigateTopic && (
        <Box>
          <Button
            size="small"
            startIcon={<ArrowBackRounded sx={{ fontSize: 15 }} />}
            onClick={() => onNavigateTopic(parentTopic.id)}
            sx={{
              px: 1.25,
              py: 0.3,
              borderRadius: 9999,
              textTransform: 'none',
              fontSize: 12.5,
              color: '#1967D2',
              bgcolor: '#EEF4FF',
              '&:hover': { bgcolor: '#DCE8FE' }
            }}
          >
            {t('architecture.backToParent', {
              badge: parentTopic.badge,
              title: pickText(parentTopic.shortTitle, locale)
            })}
          </Button>
        </Box>
      )}

      {/* 顶部：编号 + 标题 + 一句话定位 + 可选子视图切换 */}
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        sx={{ justifyContent: 'space-between', alignItems: { sm: 'flex-start' } }}
      >
        <Box sx={{ minWidth: 0 }}>
          <Stack
            direction="row"
            spacing={1}
            sx={{ alignItems: 'center', mb: 0.5, flexWrap: 'wrap' }}
          >
            <Chip
              size="small"
              label={topic.badge}
              sx={{
                height: 22,
                fontSize: 11.5,
                fontWeight: 700,
                bgcolor: parentTopic ? '#F3E8FD' : '#E8F0FE',
                color: parentTopic ? '#681DA8' : '#1967D2'
              }}
            />
            <Typography
              component={compact ? 'h2' : 'h1'}
              sx={{ fontSize: compact ? 18 : 21, fontWeight: 600, letterSpacing: '-0.015em' }}
            >
              {pickText(topic.title, locale)}
            </Typography>
          </Stack>
          <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.6 }}>
            {pickText(topic.tagline, locale)}
          </Typography>

          {/* 主图拥有的深潜子图快捷入口 */}
          {childTopics.length > 0 && onNavigateTopic && (
            <Stack
              direction="row"
              spacing={0.75}
              useFlexGap
              sx={{ mt: 1.25, flexWrap: 'wrap', alignItems: 'center' }}
            >
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ fontWeight: 600, mr: 0.25 }}
              >
                {t('architecture.deepDivesLabel')}
              </Typography>
              {childTopics.map(sub => (
                <Chip
                  key={sub.id}
                  size="small"
                  clickable
                  icon={<SubdirectoryArrowRightRounded sx={{ fontSize: 14 }} />}
                  label={`${sub.badge} ${pickText(sub.shortTitle, locale)}`}
                  onClick={() => onNavigateTopic(sub.id)}
                  sx={{
                    height: 26,
                    fontSize: 12,
                    fontWeight: 500,
                    bgcolor: '#FFFFFF',
                    color: '#1967D2',
                    border: '1px solid #D2E3FC',
                    '&:hover': { bgcolor: '#E8F0FE', borderColor: '#AECBFA' }
                  }}
                />
              ))}
            </Stack>
          )}
        </Box>

        {topic.subViews && topic.subViews.length > 0 && (
          <ToggleButtonGroup
            size="small"
            exclusive
            value={subView}
            onChange={handleSubViewChange}
            sx={{
              flexShrink: 0,
              bgcolor: '#F1F3F4',
              p: 0.4,
              borderRadius: 9999,
              '& .MuiToggleButton-root': {
                border: 0,
                borderRadius: '9999px !important',
                px: 1.5,
                py: 0.45,
                fontSize: 12.5,
                textTransform: 'none',
                color: '#5F6368',
                '&.Mui-selected': {
                  bgcolor: '#FFFFFF',
                  color: '#1967D2',
                  fontWeight: 600,
                  boxShadow: '0 1px 2px rgba(60,64,67,.18)'
                }
              }
            }}
          >
            {topic.subViews.map(item => (
              <ToggleButton key={item.id} value={item.id}>
                {pickText(item.label, locale)}
              </ToggleButton>
            ))}
          </ToggleButtonGroup>
        )}
      </Stack>

      {/* 主角：高清交互式 SVG 架构图 */}
      <Box>
        <ArchitectureDiagram
          topicId={topic.id}
          subView={subView}
          locale={locale}
          selectedNodeId={selectedNodeId}
          onSelectNode={setSelectedNodeId}
        />
        <Stack
          direction="row"
          spacing={0.6}
          sx={{ mt: 0.9, px: 0.5, alignItems: 'center', color: 'text.secondary' }}
        >
          <TouchAppOutlined sx={{ fontSize: 14 }} />
          <Typography variant="caption" color="text.secondary">
            {t('architecture.clickNodeHint')}
          </Typography>
        </Stack>
      </Box>

      {/* 节点代码透视条（点击图上任意模块实时联动，支持一键下钻到专题子图） */}
      {activeNode && (
        <Box
          data-testid="architecture-node-spotlight"
          sx={{
            p: { xs: 1.75, sm: 2 },
            borderRadius: 3,
            bgcolor: spotlightTone.bg,
            border: `1px solid ${spotlightTone.border}`,
            transition: 'background-color 180ms ease, border-color 180ms ease'
          }}
        >
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={1}
            sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' }, mb: 0.75 }}
          >
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <Chip
                size="small"
                label={pickText(activeNode.role, locale)}
                sx={{
                  height: 22,
                  fontSize: 11,
                  fontWeight: 600,
                  bgcolor: spotlightTone.chipBg,
                  color: spotlightTone.chipFg
                }}
              />
              <Typography sx={{ fontSize: 14.5, fontWeight: 600, color: '#202124' }}>
                {pickText(activeNode.label, locale)}
              </Typography>
            </Stack>

            {activeNode.codeRefs?.length > 0 && (
              <Stack direction="row" spacing={0.6} useFlexGap sx={{ flexWrap: 'wrap' }}>
                {activeNode.codeRefs.map(ref => (
                  <Chip
                    key={ref}
                    size="small"
                    icon={<CodeRounded sx={{ fontSize: 13 }} />}
                    label={ref}
                    sx={{
                      height: 23,
                      fontSize: 11.5,
                      fontFamily: "'JetBrains Mono', 'Cascadia Code', monospace",
                      bgcolor: '#FFFFFF',
                      border: `1px solid ${googleTokens.outline}`,
                      color: '#3C4043'
                    }}
                  />
                ))}
              </Stack>
            )}
          </Stack>

          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={1.25}
            sx={{ justifyContent: 'space-between', alignItems: { sm: 'flex-end' } }}
          >
            <Typography variant="body2" sx={{ color: '#3C4043', lineHeight: 1.65, flex: 1 }}>
              {pickText(activeNode.intent, locale)}
            </Typography>
            {drillTarget && onNavigateTopic && (
              <Button
                size="small"
                variant="contained"
                disableElevation
                endIcon={<ArrowForwardRounded sx={{ fontSize: 15 }} />}
                onClick={() => onNavigateTopic(drillTarget.id)}
                sx={{
                  flexShrink: 0,
                  borderRadius: 9999,
                  px: 1.75,
                  py: 0.45,
                  fontSize: 12.5,
                  textTransform: 'none',
                  fontWeight: 500
                }}
              >
                {t('architecture.drillInto', {
                  badge: drillTarget.badge,
                  title: pickText(drillTarget.shortTitle, locale)
                })}
              </Button>
            )}
          </Stack>
        </Box>
      )}

      {/* 底部：3 张精炼设计原则卡片 */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' },
          gap: 1.5
        }}
      >
        {(topic.principles ?? []).map(item => (
          <Box
            key={item.title['zh-CN']}
            sx={{
              p: 1.75,
              borderRadius: 3,
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`
            }}
          >
            <Typography sx={{ fontSize: 13.5, fontWeight: 600, color: '#202124', mb: 0.5 }}>
              {pickText(item.title, locale)}
            </Typography>
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block', lineHeight: 1.6 }}
            >
              {pickText(item.body, locale)}
            </Typography>
          </Box>
        ))}
      </Box>
    </Stack>
  );
}
