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
import PsychologyOutlined from '@mui/icons-material/PsychologyOutlined';
import WidgetsOutlined from '@mui/icons-material/WidgetsOutlined';
import ChevronRightRounded from '@mui/icons-material/ChevronRightRounded';
import FiberManualRecordRounded from '@mui/icons-material/FiberManualRecordRounded';
import StatusChip from '../../components/StatusChip';
import EmptyState from '../../components/EmptyState';
import NodeGlassboxDialog from './NodeGlassboxDialog';
import { readableHandoffSummary, stripMarkdownNoise } from './glassbox/textClean';
import { PLAN_ROLES, modelKey, modelLabel, nodeTitle } from '../../constants/labels';
import { clock, duration, nodeDuration, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';

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
  glassboxRequest,
  hasBreakpoint = false,
  onToggleBreakpoint,
  cancelAction,
  onClose,
  onCancelRun,
  onOpenLogs
}) {
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
      ? '混合模型'
      : actualModelModes.size === 1
        ? modelLabel([...actualModelModes][0])
        : null;
  const runModelText = runModelMode ? modelLabel(runModelMode) : '未知模型';
  const modelText = actualModel || `${runModelText}（未调用）`;

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
    dependency?.label || selectedEdge?.fromNodeLabel || shortId(edgeFromNodeId) || '上游节点';
  const edgeToLabel = nodeTitle(node, '当前节点');

  const latestAssembly = attempts.find(item => item.assembly)?.assembly;
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
            {nodeTitle(node, '节点详情')}
          </Typography>
          <Stack
            direction="row"
            spacing={0.75}
            sx={{ mt: 0.8, flexWrap: 'wrap', alignItems: 'center', rowGap: 0.5 }}
          >
            <StatusChip value={node?.status ?? 'PENDING'} />
            <Chip
              size="small"
              title={runId ? `运行 ${runId}` : undefined}
              label={`#${shortId(taskId) || '—'}`}
              sx={{ bgcolor: '#F1F3F4', color: '#5F6368', fontFamily: googleTokens.mono }}
            />
            {node && onToggleBreakpoint && (
              <Tooltip title="在执行该节点前自动暂停，便于检查上游交接与上下文">
                <Chip
                  size="small"
                  clickable
                  onClick={onToggleBreakpoint}
                  icon={
                    <FiberManualRecordRounded
                      sx={{ fontSize: 12, color: hasBreakpoint ? '#C5221F' : '#9AA0A6' }}
                    />
                  }
                  label={hasBreakpoint ? '已设断点' : '设断点'}
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
            <Tooltip title="在宽屏弹窗中深度透视该节点">
              <IconButton
                onClick={() => openGlassbox(selectedEdge ? 'handoff' : 'result')}
                size="small"
                aria-label="展开深度透视弹窗"
              >
                <OpenInFullRounded fontSize="small" />
              </IconButton>
            </Tooltip>
          )}
          <Tooltip title="关闭详情">
            <IconButton onClick={onClose} size="small" aria-label="关闭节点详情">
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
        <MetaCell label="模型" value={modelText} />
        <MetaCell label="耗时" value={elapsed != null ? duration(elapsed) : '—'} mono />
        <MetaCell
          label="角色"
          value={node?.role ? PLAN_ROLES[node.role] || node.role : '通用节点'}
        />
        <MetaCell label="启动时间" value={node?.startedAt ? clock(node.startedAt) : '—'} mono />
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
              正在读取节点记录…
            </Typography>
          </Stack>
        )}

        {!node ? (
          <EmptyState
            compact
            title="选择一个节点"
            description="点击流程图上的节点或连线，快速预览关键信息并一键打开宽屏玻璃盒透视弹窗。"
          />
        ) : (
          <Stack spacing={2}>
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
                    依赖连线 · {edgeFromLabel} → {edgeToLabel}
                  </Typography>
                  <Chip
                    size="small"
                    label={edgeHandoffs.length > 0 ? '已接收交接' : '暂无记录'}
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
                  在宽屏弹窗中查看交接信封与校验原理 →
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
                玻璃盒深度透视（点击展开宽屏阅读）
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
                  title="交接信封"
                  subtitle={`收到 ${inbound.length} · 发出 ${outbound.length}`}
                  accentBg="#E6F4EA"
                  accentFg="#137333"
                  onClick={() => openGlassbox('handoff')}
                />
                <GlassboxEntryCard
                  icon={<VerifiedUserOutlined sx={{ fontSize: 16 }} />}
                  title="工具安检"
                  subtitle={
                    tools.length > 0
                      ? `调用 ${tools.length} 次${deniedToolCount ? ` · 拦截 ${deniedToolCount}` : ' · 全通过'}`
                      : '无工具调用'
                  }
                  accentBg="#FEF7E0"
                  accentFg="#B06000"
                  onClick={() => openGlassbox('gateway')}
                />
                <GlassboxEntryCard
                  icon={<PsychologyOutlined sx={{ fontSize: 16 }} />}
                  title="模型 I/O"
                  subtitle={
                    models.length > 0
                      ? `${models.length} 次调用${totalOutTokens ? ` · Out ${totalOutTokens}` : ''}`
                      : '暂无模型调用'
                  }
                  accentBg="#E8F0FE"
                  accentFg="#1967D2"
                  onClick={() => openGlassbox('llm')}
                />
                <GlassboxEntryCard
                  icon={<WidgetsOutlined sx={{ fontSize: 16 }} />}
                  title="装配配方"
                  subtitle={`放行工具 ${grantedToolCount} 项`}
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
                    节点结论摘要
                  </Typography>
                  <Button
                    size="small"
                    onClick={() => openGlassbox('result')}
                    sx={{ py: 0, px: 0.5, fontSize: 12, minHeight: 24 }}
                  >
                    阅读全文 →
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
                    上游交接速览（{edgeHandoffs.length}）
                  </Typography>
                  <Button
                    size="small"
                    onClick={() => openGlassbox('handoff')}
                    sx={{ py: 0, px: 0.5, fontSize: 12, minHeight: 24 }}
                  >
                    校验与正文 →
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
                            {handoff.senderNodeLabel || handoff.dependencyNodeLabel || '上游节点'}
                          </Typography>
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{ flexShrink: 0 }}
                          >
                            {passCount > 0 ? `${passCount} 项 PASS` : clock(handoff.acceptedAt)}
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
                            涉及 {artifactCount} 个文件/产物 · 点击查看完整交接与校验原理
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
            深度透视
          </Button>
        )}
        <Button size="small" variant="outlined" onClick={onOpenLogs}>
          完整日志
        </Button>
        <Tooltip
          title={
            cancelAction?.enabled
              ? '取消会终止整个运行'
              : cancelAction?.disabledReason || '服务端当前未提供取消操作'
          }
        >
          <span>
            <Button
              size="small"
              color="error"
              disabled={!cancelAction?.enabled}
              onClick={() => setConfirmCancel(true)}
            >
              取消
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
      />

      {/* 取消运行确认框 */}
      <Dialog open={confirmCancel} onClose={() => setConfirmCancel(false)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ fontSize: 18 }}>取消本次运行？</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary">
            取消会终止整个运行，而不只是当前节点。已产生的执行证据与工具回执会保留；在途模型或工具调用可能继续结束。
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmCancel(false)} color="inherit">
            继续运行
          </Button>
          <Button
            variant="contained"
            color="error"
            onClick={() => {
              setConfirmCancel(false);
              onCancelRun?.();
            }}
          >
            取消运行
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
