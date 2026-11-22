import {
  Box,
  Chip,
  Dialog,
  Divider,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Stack,
  Tab,
  Tabs,
  Tooltip,
  Typography,
  useMediaQuery
} from '@mui/material';
import CloseRounded from '@mui/icons-material/CloseRounded';
import DescriptionOutlined from '@mui/icons-material/DescriptionOutlined';
import SwapHorizRounded from '@mui/icons-material/SwapHorizRounded';
import VerifiedUserOutlined from '@mui/icons-material/VerifiedUserOutlined';
import PsychologyOutlined from '@mui/icons-material/PsychologyOutlined';
import WidgetsOutlined from '@mui/icons-material/WidgetsOutlined';
import FiberManualRecordRounded from '@mui/icons-material/FiberManualRecordRounded';
import StatusChip from '../../components/StatusChip';
import MarkdownView from '../../components/MarkdownView';
import EmptyState from '../../components/EmptyState';
import { Readout, TraceSection } from '../../components/WorkbenchDialog';
import AssemblyBlueprintPanel from './glassbox/AssemblyBlueprintPanel';
import LlmTracePanel from './glassbox/LlmTracePanel';
import ToolGatewayPanel from './glassbox/ToolGatewayPanel';
import HandoffEnvelopePanel from './glassbox/HandoffEnvelopePanel';
import { nodeTitle, planRoleLabel } from '../../constants/labels';
import { clock, duration } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

function ResultAndTimelineView({ detail, checks, nodeEvents }) {
  const { t, locale } = useI18n();
  const hasContent = Boolean(detail?.result) || checks.length > 0 || nodeEvents.length > 0;
  if (!hasContent) {
    return (
      <EmptyState
        compact
        title={t('glassboxDialog.emptyTitle')}
        description={t('glassboxDialog.emptyDescription')}
      />
    );
  }

  return (
    <Stack spacing={2}>
      {detail?.result && (
        <Box
          sx={{
            p: 2.25,
            borderRadius: 3,
            bgcolor: '#FFFFFF',
            border: `1px solid ${googleTokens.outline}`
          }}
        >
          <Typography variant="subtitle2" sx={{ mb: 1.25, fontSize: 14.5 }}>
            {t('glassboxDialog.nodeResult')}
          </Typography>
          <MarkdownView text={detail.result} />
        </Box>
      )}

      {checks.length > 0 && (
        <Box
          sx={{
            p: 2,
            borderRadius: 3,
            bgcolor: '#FFFFFF',
            border: `1px solid ${googleTokens.outline}`
          }}
        >
          <Typography variant="subtitle2" sx={{ mb: 1.25, fontSize: 14.5 }}>
            {t('glassboxDialog.checks', { count: checks.length })}
          </Typography>
          <Stack spacing={1.25}>
            {checks.map(check => (
              <Box
                key={check.id}
                sx={{
                  p: 1.25,
                  borderRadius: 2,
                  bgcolor: '#F8F9FA',
                  border: `1px solid ${googleTokens.outline}`
                }}
              >
                <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', mb: 0.5 }}>
                  <StatusChip value={check.status} />
                  <Typography variant="caption" color="text.secondary">
                    {check.checkId} v{check.checkVersion}
                  </Typography>
                </Stack>
                {check.summary && (
                  <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', mb: 0.5 }}>
                    {check.summary}
                  </Typography>
                )}
                {check.evidence && <Readout maxHeight={180}>{check.evidence}</Readout>}
              </Box>
            ))}
          </Stack>
        </Box>
      )}

      {nodeEvents.length > 0 && (
        <TraceSection
          title={t('glassboxDialog.timeline')}
          subtitle={t('glassboxDialog.events', { count: nodeEvents.length })}
        >
          <Stack spacing={0.9}>
            {nodeEvents.map(event => (
              <Box key={event.id}>
                <Typography variant="caption" color="text.secondary">
                  {clock(event.occurredAt, locale)} · {event.type}
                  {event.reasonCode ? ` · ${event.reasonCode}` : ''}
                </Typography>
                <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>
                  {event.message}
                </Typography>
              </Box>
            ))}
          </Stack>
        </TraceSection>
      )}
    </Stack>
  );
}

/**
 * 宽屏节点玻璃盒透视弹窗：
 * 避免把长段 Prompt、交接正文、表格与安检门规则挤在窄右侧抽屉里，
 * 提供宽敞分栏的沉浸式学习与排查体验。
 */
export default function NodeGlassboxDialog({
  open,
  activeSection,
  onSectionChange,
  onClose,
  node,
  detail,
  attempts,
  models,
  tools,
  inbound,
  outbound,
  checks,
  nodeEvents,
  modelText,
  elapsed,
  hasBreakpoint,
  onToggleBreakpoint
}) {
  const { t, locale } = useI18n();
  const wide = useMediaQuery('(min-width:900px)');
  const handoffCount = inbound.length + outbound.length;

  const sections = [
    {
      id: 'result',
      label: t('glassboxDialog.sectionResult'),
      icon: <DescriptionOutlined sx={{ fontSize: 18 }} />,
      badge: detail?.result ? t('glassboxDialog.body') : null
    },
    {
      id: 'handoff',
      label: t('glassbox.handoff'),
      icon: <SwapHorizRounded sx={{ fontSize: 18 }} />,
      badge: handoffCount
    },
    {
      id: 'gateway',
      label: t('glassbox.gateway'),
      icon: <VerifiedUserOutlined sx={{ fontSize: 18 }} />,
      badge: tools.length
    },
    {
      id: 'llm',
      label: t('glassbox.llm'),
      icon: <PsychologyOutlined sx={{ fontSize: 18 }} />,
      badge: models.length
    },
    {
      id: 'assembly',
      label: t('glassbox.assembly'),
      icon: <WidgetsOutlined sx={{ fontSize: 18 }} />,
      badge: null
    }
  ];

  const current = sections.some(item => item.id === activeSection) ? activeSection : 'result';

  return (
    <Dialog
      open={open}
      onClose={onClose}
      maxWidth="lg"
      fullWidth
      slotProps={{
        paper: {
          sx: {
            borderRadius: 4,
            height: 'min(86vh, 840px)',
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden',
            backgroundImage: 'none'
          }
        }
      }}
    >
      {/* 顶部栏 */}
      <Stack
        direction="row"
        spacing={1.5}
        sx={{
          px: 3,
          py: 1.75,
          alignItems: 'center',
          justifyContent: 'space-between',
          bgcolor: '#FFFFFF',
          borderBottom: `1px solid ${googleTokens.outline}`
        }}
      >
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Stack
            direction="row"
            spacing={1}
            sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}
          >
            <Typography sx={{ fontSize: 17, fontWeight: 600, overflowWrap: 'anywhere' }}>
              {nodeTitle(node, t('glassboxDialog.title'), locale)}
            </Typography>
            <StatusChip value={node?.status ?? 'PENDING'} />
            {node?.role && (
              <Chip
                size="small"
                label={planRoleLabel(node.role, locale)}
                sx={{ bgcolor: '#F1F3F4', color: '#3C4043' }}
              />
            )}
            {node && onToggleBreakpoint && (
              <Tooltip title={t('glassboxDialog.breakpointHint')}>
                <Chip
                  size="small"
                  clickable
                  onClick={onToggleBreakpoint}
                  icon={
                    <FiberManualRecordRounded
                      sx={{ fontSize: 12, color: hasBreakpoint ? '#C5221F' : '#9AA0A6' }}
                    />
                  }
                  label={
                    hasBreakpoint
                      ? t('glassboxDialog.breakpointSet')
                      : t('glassboxDialog.setBreakpoint')
                  }
                  sx={{
                    bgcolor: hasBreakpoint ? '#FCE8E6' : '#F1F3F4',
                    color: hasBreakpoint ? '#C5221F' : '#5F6368',
                    fontWeight: 500
                  }}
                />
              </Tooltip>
            )}
          </Stack>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.4 }}>
            {t('glassboxDialog.modelLine', { model: modelText })}
            {elapsed != null
              ? ` · ${t('glassboxDialog.elapsed', { value: duration(elapsed) })}`
              : ''}
            {node?.startedAt
              ? ` · ${t('glassboxDialog.startedAt', { time: clock(node.startedAt, locale) })}`
              : ''}
          </Typography>
        </Box>
        <Tooltip title={t('glassboxDialog.closeHint')}>
          <IconButton onClick={onClose} size="small" aria-label={t('glassboxDialog.closeHint')}>
            <CloseRounded fontSize="small" />
          </IconButton>
        </Tooltip>
      </Stack>

      {/* 窄屏顶部 Tabs */}
      {!wide && (
        <Tabs
          value={current}
          onChange={(_, value) => onSectionChange(value)}
          variant="scrollable"
          scrollButtons="auto"
          sx={{
            px: 1.5,
            minHeight: 42,
            borderBottom: `1px solid ${googleTokens.outline}`,
            bgcolor: '#FFFFFF'
          }}
        >
          {sections.map(item => (
            <Tab
              key={item.id}
              value={item.id}
              label={typeof item.badge === 'number' ? `${item.label} (${item.badge})` : item.label}
            />
          ))}
        </Tabs>
      )}

      {/* 主体：左侧导航 + 右侧宽屏阅读区 */}
      <Box sx={{ flex: 1, minHeight: 0, display: 'flex', bgcolor: googleTokens.canvas }}>
        {wide && (
          <Box
            sx={{
              width: 212,
              flexShrink: 0,
              bgcolor: '#FFFFFF',
              borderRight: `1px solid ${googleTokens.outline}`,
              p: 1.25,
              overflowY: 'auto'
            }}
          >
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ px: 1.25, py: 0.5, display: 'block', fontWeight: 600 }}
            >
              {t('glassboxDialog.dimensions')}
            </Typography>
            <List dense disablePadding sx={{ mt: 0.5 }}>
              {sections.map(item => {
                const selected = current === item.id;
                return (
                  <ListItemButton
                    key={item.id}
                    selected={selected}
                    onClick={() => onSectionChange(item.id)}
                    sx={{
                      borderRadius: 2.5,
                      mb: 0.5,
                      px: 1.25,
                      py: 0.9,
                      '&.Mui-selected': {
                        bgcolor: '#E8F0FE',
                        color: '#1967D2',
                        '&:hover': { bgcolor: '#D2E3FC' }
                      }
                    }}
                  >
                    <ListItemIcon
                      sx={{
                        minWidth: 30,
                        color: selected ? '#1967D2' : googleTokens.textSecondary
                      }}
                    >
                      {item.icon}
                    </ListItemIcon>
                    <ListItemText
                      primary={item.label}
                      slotProps={{
                        primary: {
                          sx: { fontSize: 13.5, fontWeight: selected ? 600 : 500 }
                        }
                      }}
                    />
                    {item.badge != null && (
                      <Chip
                        size="small"
                        label={item.badge}
                        sx={{
                          height: 20,
                          fontSize: 11,
                          bgcolor: selected ? '#FFFFFF' : '#F1F3F4',
                          color: selected ? '#1967D2' : '#5F6368',
                          fontWeight: 600
                        }}
                      />
                    )}
                  </ListItemButton>
                );
              })}
            </List>

            <Divider sx={{ my: 1.5 }} />
            <Box sx={{ px: 1.25 }}>
              <Typography variant="caption" color="text.secondary" sx={{ lineHeight: 1.6 }}>
                {t('glassboxDialog.tip')}
              </Typography>
            </Box>
          </Box>
        )}

        <Box sx={{ flex: 1, minWidth: 0, overflowY: 'auto', p: { xs: 2, md: 3 } }}>
          {current === 'result' && (
            <ResultAndTimelineView detail={detail} checks={checks} nodeEvents={nodeEvents} />
          )}
          {current === 'handoff' && (
            <HandoffEnvelopePanel inbound={inbound} outbound={outbound} defaultExpandBody />
          )}
          {current === 'gateway' && <ToolGatewayPanel tools={tools} />}
          {current === 'llm' && <LlmTracePanel models={models} />}
          {current === 'assembly' && <AssemblyBlueprintPanel node={node} attempts={attempts} />}
        </Box>
      </Box>
    </Dialog>
  );
}
