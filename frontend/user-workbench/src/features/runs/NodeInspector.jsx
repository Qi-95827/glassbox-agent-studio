import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  Stack,
  Tooltip,
  Typography
} from '@mui/material';
import CloseRounded from '@mui/icons-material/CloseRounded';
import OpenInFullRounded from '@mui/icons-material/OpenInFullRounded';
import SwapHorizRounded from '@mui/icons-material/SwapHorizRounded';
import VerifiedUserOutlined from '@mui/icons-material/VerifiedUserOutlined';
import { latestAssemblyAttempt } from './glassbox/assemblyAttempt';
import PsychologyOutlined from '@mui/icons-material/PsychologyOutlined';
import WidgetsOutlined from '@mui/icons-material/WidgetsOutlined';
import ChevronRightRounded from '@mui/icons-material/ChevronRightRounded';
import FiberManualRecordRounded from '@mui/icons-material/FiberManualRecordRounded';
import StatusChip from '../../components/StatusChip';
import EmptyState from '../../components/EmptyState';
import NodeGlassboxDialog from './NodeGlassboxDialog';
import { readableHandoffSummary, stripMarkdownNoise } from './glassbox/textClean';
import { modelKey, modelLabel, nodeTitle, planRoleLabel } from '../../constants/labels';
import { clock, duration, nodeDuration, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

function MetaCell({ label, value, mono }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography sx={{ fontSize: 11.5, color: googleTokens.textSecondary, lineHeight: 1.3 }}>
        {label}
      </Typography>
      <Typography
        sx={{
          fontSize: 12.5,
          fontWeight: 500,
          color: googleTokens.text,
          fontFamily: mono ? googleTokens.mono : 'inherit',
          mt: 0.2,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap'
        }}
        title={value}
      >
        {value}
      </Typography>
    </Box>
  );
}

function GlassboxEntryCard({ icon, title, subtitle, accentBg, accentFg, onClick }) {
  return (
    <Box
      role="button"
      tabIndex={0}
      onClick={onClick}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onClick();
        }
      }}
      sx={{
        p: 1.35,
        borderRadius: 2.5,
        bgcolor: '#FFFFFF',
        border: `1px solid ${googleTokens.outline}`,
        cursor: 'pointer',
        transition: 'border-color .15s ease, background-color .15s ease, transform .15s ease',
        '&:hover': {
          borderColor: googleTokens.primary,
          bgcolor: '#F8FBFF'
        }
      }}
    >
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.6 }}>
        <Box
          sx={{
            width: 28,
            height: 28,
            borderRadius: 2,
            bgcolor: accentBg,
            color: accentFg,
            display: 'grid',
            placeItems: 'center',
            flexShrink: 0
          }}
        >
          {icon}
        </Box>
        <Typography sx={{ fontSize: 13, fontWeight: 600, flex: 1, minWidth: 0 }}>
          {title}
        </Typography>
        <ChevronRightRounded sx={{ fontSize: 16, color: googleTokens.textSecondary }} />
      </Stack>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{
          display: 'block',
          lineHeight: 1.45,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap'
        }}
      >
        {subtitle}
      </Typography>
    </Box>
  );
}

/**
 * 右侧节点详情（轻量化概览）：
 * 仅展示核心状态、精简元数据、4 个玻璃盒入口卡片与清洗后的简要结论，
 * 点击任意模块或「深度透视」按钮后，在宽屏弹窗中沉浸阅读完整正文、表格与校验原理。
 */
export default function NodeInspector({
  node,
  detail,
  loading,
  error,
  runId,
  taskId,
  generatedAt,
  runModelMode,
  selectedEdge,
  revisionEdge,
  glassboxRequest,
  liveStream = null,
  hasBreakpoint = false,
  onToggleBreakpoint,
  cancelAction,
  onClose,
  onCancelRun,
  onOpenLogs
}) {
  const { t, locale } = useI18n();
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogSection, setDialogSection] = useState('result');

  // 由运行详情页的 URL Query（nodeId + glassbox）驱动，自动展开目标节点的宽屏透视 Tab。
  useEffect(() => {
    if (!glassboxRequest || glassboxRequest.nodeId !== node?.id) return;
    const allowed = ['result', 'handoff', 'gateway', 'llm', 'assembly'];
    setDialogSection(
      allowed.includes(glassboxRequest.section) ? glassboxRequest.section : 'result'
    );
    setDialogOpen(true);
  }, [glassboxRequest, node?.id]);

  const attempts = detail?.attempts ?? [];
  const models = attempts.flatMap(attempt => attempt.models ?? []);
  const tools = attempts.flatMap(attempt =>
    (attempt.tools ?? []).map(call => ({ ...call, attemptNo: attempt.number }))
  );
  const inbound = detail?.inboundHandoffs ?? [];
  const outbound = detail?.outboundHandoffs ?? [];
  const checks = detail?.checks ?? [];
  const nodeEvents = detail?.nodeEvents ?? [];
  const elapsed = nodeDuration(node, generatedAt);

  const openGlassbox = section => {
    setDialogSection(section);
    setDialogOpen(true);
  };

  const actualModelModes = new Set(
    attempts
      .flatMap(attempt => [
        attempt.assembly?.components?.model?.mode,
        ...(attempt.models ?? []).map(call => call.name)
      ])
      .filter(Boolean)
      .map(mode => modelKey(mode))
      .filter(Boolean)
  );
  const actualModel =
    actualModelModes.size > 1
      ? t('common.mixedModel')
      : actualModelModes.size === 1
        ? modelLabel([...actualModelModes][0], undefined, locale)
        : null;
  const runModelText = runModelMode
    ? modelLabel(runModelMode, undefined, locale)
    : t('common.unknownModel');
  const modelText = actualModel || t('inspector.notCalled', { model: runModelText });

  const edgeFromNodeId = selectedEdge?.fromNodeId;
  const edgeToNodeId = selectedEdge?.toNodeId ?? node?.id;
  const dependency = (detail?.dependencies ?? []).find(item => item.nodeId === edgeFromNodeId);
  const edgeHandoffs = selectedEdge
    ? inbound.filter(
        record =>
          record.dependencyNodeId === edgeFromNodeId && record.receiverNodeId === edgeToNodeId
      )
    : inbound;
  const edgeFromLabel =
    dependency?.label ||
    selectedEdge?.fromNodeLabel ||
    shortId(edgeFromNodeId) ||
    t('glassbox.upstreamNode');
  const edgeToLabel = nodeTitle(node, t('glassbox.currentNode'), locale);

  // 与装配蓝图面板、后端 toolFunnel 取同一次（最新）尝试的装配快照，避免三处口径分叉。
  const latestAttempt = latestAssemblyAttempt(attempts);
  const latestAssembly = latestAttempt?.assembly;
  const grantedToolCount = Object.keys(latestAssembly?.components?.tools ?? {}).length;
  const deniedToolCount = tools.filter(call => call.status === 'FAILED').length;
  const totalOutTokens = models.reduce((sum, call) => sum + (Number(call.outputTokens) || 0), 0);
  const cleanResultPreview = detail?.result ? stripMarkdownNoise(detail.result).slice(0, 150) : '';

  return (
    <Box
      sx={{
        width: '100%',
        flexShrink: 0,
        height: '100%',
        bgcolor: '#FFFFFF',
        display: 'flex',
        flexDirection: 'column',
        animation: 'nodeInspectorIn .2s ease',
        '@keyframes nodeInspectorIn': {
          from: { opacity: 0, transform: 'translateX(12px)' },
          to: { opacity: 1, transform: 'translateX(0)' }
        }
      }}
    >
      {/* 顶部标题与快捷操作 */}
      <Stack direction="row" sx={{ px: 2.25, pt: 2, pb: 1.25, gap: 1, alignItems: 'flex-start' }}>
        <Stack sx={{ flex: 1, minWidth: 0 }}>
          <Typography
            component="h2"
            sx={{ fontSize: 16.5, fontWeight: 600, lineHeight: 1.4, overflowWrap: 'anywhere' }}
          >
            {nodeTitle(node, t('inspector.title'), locale)}
          </Typography>
          <Stack
            direction="row"
            spacing={0.75}
            sx={{ mt: 0.8, flexWrap: 'wrap', alignItems: 'center', rowGap: 0.5 }}
          >
            <StatusChip value={node?.status ?? 'PENDING'} />
            <Chip
              size="small"
              title={runId ? t('common.runId', { id: runId }) : undefined}
              label={`#${shortId(taskId) || '—'}`}
              sx={{ bgcolor: '#F1F3F4', color: '#5F6368', fontFamily: googleTokens.mono }}
            />
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
        </Stack>
        <Stack direction="row" spacing={0.25}>
          {node && (
            <Tooltip title={t('inspector.openHint')}>
              <IconButton
                onClick={() => openGlassbox(selectedEdge ? 'handoff' : 'result')}
                size="small"
                aria-label={t('inspector.openHint')}
              >
                <OpenInFullRounded fontSize="small" />
              </IconButton>
            </Tooltip>
          )}
          <Tooltip title={t('common.close')}>
            <IconButton onClick={onClose} size="small" aria-label={t('common.close')}>
              <CloseRounded fontSize="small" />
            </IconButton>
          </Tooltip>
        </Stack>
      </Stack>

      {/* 精简 2x2 元数据 */}
      <Box
        sx={{
          px: 2.25,
          pb: 1.5,
          display: 'grid',
          gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
          gap: 1.1
        }}
      >
        <MetaCell label={t('common.model')} value={modelText} />
        <MetaCell
          label={t('inspector.elapsed')}
          value={elapsed != null ? duration(elapsed) : '—'}
          mono
        />
        <MetaCell
          label={t('inspector.role')}
          value={node?.role ? planRoleLabel(node.role, locale) : t('inspector.genericNode')}
        />
        <MetaCell
          label={t('inspector.startedAt')}
          value={node?.startedAt ? clock(node.startedAt, locale) : '—'}
          mono
        />
      </Box>

      <Divider />

      {/* 主体：低密度概览 + 透视弹窗入口 */}
      <Box sx={{ flex: 1, minHeight: 0, overflowY: 'auto', px: 2.25, py: 2 }}>
        {error && (
          <Alert severity="error" sx={{ mb: 1.5 }}>
            {error.message || String(error)}
          </Alert>
        )}
        {loading && !detail && (
          <Stack direction="row" spacing={1} sx={{ py: 2, alignItems: 'center' }}>
            <CircularProgress size={16} />
            <Typography variant="body2" color="text.secondary">
              {t('inspector.loading')}
            </Typography>
          </Stack>
        )}

        {!node ? (
          <EmptyState
            compact
            title={t('inspector.selectTitle')}
            description={t('inspector.selectDescription')}
          />
        ) : (
          <Stack spacing={2}>
            {/* 反思回边：展示真实已反思轮次与上限，并列出对应节点的尝试记录。 */}
            {revisionEdge && (
              <Box
                data-testid="inspector-revision-edge"
                sx={{
                  p: 1.5,
                  borderRadius: 2.5,
                  bgcolor: '#FEF7E0',
                  border: '1px solid #F9D9A2'
                }}
              >
                <Stack
                  direction="row"
                  spacing={0.75}
                  sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 0.5 }}
                >
                  <Typography variant="caption" sx={{ fontWeight: 600, color: '#B06000' }}>
                    {t('inspector.revisionEdge')}
                  </Typography>
                  <Chip
                    size="small"
                    label={t('inspector.revisionCount', {
                      count: revisionEdge.revisionCount ?? 0,
                      max: revisionEdge.maxRevisions ?? 0
                    })}
                    sx={{ height: 20, fontSize: 11, bgcolor: '#FFF3E0', color: '#B06000' }}
                  />
                </Stack>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  {t('inspector.revisionPath', {
                    reviewer: shortId(revisionEdge.reviewerNodeId),
                    target: nodeTitle(node, t('glassbox.currentNode'), locale)
                  })}
                </Typography>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ display: 'block', mt: 0.5 }}
                >
                  {t('inspector.revisionAttempts', { count: attempts.length })}
                </Typography>
              </Box>
            )}

            {/* 若当前选中了连线，高亮显示该条交接 */}
            {selectedEdge && (
              <Box
                sx={{
                  p: 1.5,
                  borderRadius: 2.5,
                  bgcolor: edgeHandoffs.length > 0 ? '#F6FBF7' : '#F8F9FA',
                  border: '1px solid',
                  borderColor: edgeHandoffs.length > 0 ? '#CEEAD6' : googleTokens.outline
                }}
              >
                <Stack
                  direction="row"
                  spacing={0.75}
                  sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 0.5 }}
                >
                  <Typography variant="caption" sx={{ fontWeight: 600 }}>
                    {t('inspector.edge', { from: edgeFromLabel, to: edgeToLabel })}
                  </Typography>
                  <Chip
                    size="small"
                    label={
                      edgeHandoffs.length > 0
                        ? t('inspector.handoffReceived')
                        : t('inspector.noRecord')
                    }
                    sx={{
                      height: 20,
                      fontSize: 11,
                      bgcolor: edgeHandoffs.length > 0 ? '#E6F4EA' : '#F1F3F4',
                      color: edgeHandoffs.length > 0 ? '#137333' : '#5F6368'
                    }}
                  />
                </Stack>
                <Button
                  size="small"
                  variant="text"
                  onClick={() => openGlassbox('handoff')}
                  sx={{ px: 0, minHeight: 24, fontSize: 12.5 }}
                >
                  {t('inspector.openHandoff')} →
                </Button>
              </Box>
            )}

            {/* 4 个玻璃盒入口卡片 */}
            <Box>
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ fontWeight: 600, display: 'block', mb: 0.9 }}
              >
                {t('inspector.glassboxCards')}
              </Typography>
              <Box
                sx={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
                  gap: 1
                }}
              >
                <GlassboxEntryCard
                  icon={<SwapHorizRounded sx={{ fontSize: 16 }} />}
                  title={t('glassbox.handoff')}
                  subtitle={t('inspector.handoffCounts', {
                    inbound: inbound.length,
                    outbound: outbound.length
                  })}
                  accentBg="#E6F4EA"
                  accentFg="#137333"
                  onClick={() => openGlassbox('handoff')}
                />
                <GlassboxEntryCard
                  icon={<VerifiedUserOutlined sx={{ fontSize: 16 }} />}
                  title={t('inspector.gatewayTitle')}
                  subtitle={
                    tools.length > 0
                      ? deniedToolCount
                        ? t('inspector.gatewayCallsDenied', {
                            count: tools.length,
                            denied: deniedToolCount
                          })
                        : t('inspector.gatewayCallsPassed', { count: tools.length })
                      : t('inspector.noToolCalls')
                  }
                  accentBg="#FEF7E0"
                  accentFg="#B06000"
                  onClick={() => openGlassbox('gateway')}
                />
                <GlassboxEntryCard
                  icon={<PsychologyOutlined sx={{ fontSize: 16 }} />}
                  title={t('glassbox.llm')}
                  subtitle={
                    models.length > 0
                      ? t('inspector.modelCalls', {
                          count: models.length,
                          tokens: totalOutTokens
                        })
                      : t('inspector.noModelCalls')
                  }
                  accentBg="#E8F0FE"
                  accentFg="#1967D2"
                  onClick={() => openGlassbox('llm')}
                />
                <GlassboxEntryCard
                  icon={<WidgetsOutlined sx={{ fontSize: 16 }} />}
                  title={t('glassbox.assembly')}
                  subtitle={
                    latestAttempt?.number
                      ? t('inspector.grantedToolsWithAttempt', {
                          count: grantedToolCount,
                          attempt: latestAttempt.number
                        })
                      : t('inspector.grantedTools', { count: grantedToolCount })
                  }
                  accentBg="#F3E8FD"
                  accentFg="#7627BB"
                  onClick={() => openGlassbox('assembly')}
                />
              </Box>
            </Box>

            {/* 节点结论精简预览 */}
            {cleanResultPreview && (
              <Box>
                <Stack
                  direction="row"
                  sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 0.75 }}
                >
                  <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                    {t('inspector.summary')}
                  </Typography>
                  <Button
                    size="small"
                    onClick={() => openGlassbox('result')}
                    sx={{ py: 0, px: 0.5, fontSize: 12, minHeight: 24 }}
                  >
                    {t('inspector.readFull')} →
                  </Button>
                </Stack>
                <Box
                  onClick={() => openGlassbox('result')}
                  sx={{
                    p: 1.35,
                    borderRadius: 2.5,
                    bgcolor: googleTokens.canvas,
                    border: `1px solid ${googleTokens.outline}`,
                    cursor: 'pointer',
                    '&:hover': { borderColor: googleTokens.primary }
                  }}
                >
                  <Typography
                    variant="body2"
                    sx={{
                      fontSize: 13,
                      lineHeight: 1.6,
                      display: '-webkit-box',
                      WebkitLineClamp: 3,
                      WebkitBoxOrient: 'vertical',
                      overflow: 'hidden'
                    }}
                  >
                    {cleanResultPreview}
                  </Typography>
                </Box>
              </Box>
            )}

            {/* 上游交接速览（清洗后清爽卡片，点击弹窗看详情） */}
            {edgeHandoffs.length > 0 && (
              <Box>
                <Stack
                  direction="row"
                  sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 0.75 }}
                >
                  <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                    {t('inspector.handoffPreview', { count: edgeHandoffs.length })}
                  </Typography>
                  <Button
                    size="small"
                    onClick={() => openGlassbox('handoff')}
                    sx={{ py: 0, px: 0.5, fontSize: 12, minHeight: 24 }}
                  >
                    {t('inspector.checksAndBody')} →
                  </Button>
                </Stack>
                <Stack spacing={0.9}>
                  {edgeHandoffs.map(handoff => {
                    const summary = readableHandoffSummary(
                      handoff.structured?.summary,
                      handoff.sourceResult,
                      110
                    );
                    const artifactCount = handoff.structured?.artifacts?.length ?? 0;
                    const passCount = (handoff.structured?.validationChecks ?? []).filter(
                      chk => chk.status === 'PASS'
                    ).length;
                    return (
                      <Box
                        key={`${handoff.senderAttemptId}:${handoff.dependencyNodeId}`}
                        onClick={() => openGlassbox('handoff')}
                        sx={{
                          p: 1.25,
                          borderRadius: 2.5,
                          bgcolor: '#FAFAFA',
                          border: `1px solid ${googleTokens.outline}`,
                          cursor: 'pointer',
                          '&:hover': { borderColor: googleTokens.primary, bgcolor: '#F8FBFF' }
                        }}
                      >
                        <Stack
                          direction="row"
                          spacing={0.5}
                          sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 0.45 }}
                        >
                          <Typography
                            variant="caption"
                            sx={{
                              fontWeight: 600,
                              color: googleTokens.text,
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap'
                            }}
                          >
                            {handoff.senderNodeLabel ||
                              handoff.dependencyNodeLabel ||
                              t('glassbox.upstreamNode')}
                          </Typography>
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{ flexShrink: 0 }}
                          >
                            {passCount > 0
                              ? t('inspector.passCount', { count: passCount })
                              : clock(handoff.acceptedAt, locale)}
                          </Typography>
                        </Stack>
                        <Typography
                          variant="caption"
                          color="text.secondary"
                          sx={{
                            display: '-webkit-box',
                            WebkitLineClamp: 2,
                            WebkitBoxOrient: 'vertical',
                            overflow: 'hidden',
                            lineHeight: 1.5
                          }}
                        >
                          {summary}
                        </Typography>
                        {artifactCount > 0 && (
                          <Typography
                            variant="caption"
                            sx={{ display: 'block', mt: 0.45, color: googleTokens.primary }}
                          >
                            {t('inspector.artifactHint', { count: artifactCount })}
                          </Typography>
                        )}
                      </Box>
                    );
                  })}
                </Stack>
              </Box>
            )}
          </Stack>
        )}
      </Box>

      {/* 底部操作栏 */}
      <Divider />
      <Stack direction="row" spacing={1} sx={{ px: 2, py: 1.5 }}>
        {node && (
          <Button
            size="small"
            variant="contained"
            startIcon={<OpenInFullRounded sx={{ fontSize: 15 }} />}
            onClick={() => openGlassbox(selectedEdge ? 'handoff' : 'result')}
            sx={{ flex: 1 }}
          >
            {t('inspector.deepInspect')}
          </Button>
        )}
        <Button size="small" variant="outlined" onClick={onOpenLogs}>
          {t('inspector.fullLogs')}
        </Button>
        <Tooltip
          title={
            cancelAction?.enabled
              ? t('inspector.cancelHint')
              : cancelAction?.disabledReason || t('inspector.cancelUnavailable')
          }
        >
          <span>
            <Button
              size="small"
              color="error"
              disabled={!cancelAction?.enabled}
              onClick={() => setConfirmCancel(true)}
            >
              {t('common.cancel')}
            </Button>
          </span>
        </Tooltip>
      </Stack>

      {/* 宽屏玻璃盒透视弹窗 */}
      <NodeGlassboxDialog
        open={dialogOpen && Boolean(node)}
        activeSection={dialogSection}
        onSectionChange={setDialogSection}
        onClose={() => setDialogOpen(false)}
        node={node}
        detail={detail}
        attempts={attempts}
        models={models}
        tools={tools}
        inbound={inbound}
        outbound={outbound}
        checks={checks}
        nodeEvents={nodeEvents}
        modelText={modelText}
        elapsed={elapsed}
        hasBreakpoint={hasBreakpoint}
        onToggleBreakpoint={onToggleBreakpoint}
        liveStream={liveStream}
      />

      {/* 取消运行确认框 */}
      <Dialog open={confirmCancel} onClose={() => setConfirmCancel(false)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ fontSize: 18 }}>{t('inspector.cancelTitle')}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary">
            {t('inspector.cancelDescription')}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmCancel(false)} color="inherit">
            {t('inspector.continueRun')}
          </Button>
          <Button
            variant="contained"
            color="error"
            onClick={() => {
              setConfirmCancel(false);
              onCancelRun?.();
            }}
          >
            {t('run.action.CANCEL')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
