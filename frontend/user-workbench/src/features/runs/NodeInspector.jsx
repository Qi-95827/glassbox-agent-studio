import { useState } from 'react';
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
import DescriptionOutlined from '@mui/icons-material/DescriptionOutlined';
import SearchRounded from '@mui/icons-material/SearchRounded';
import CheckCircleRounded from '@mui/icons-material/CheckCircleRounded';
import ErrorOutlineRounded from '@mui/icons-material/ErrorOutlineRounded';
import HourglassEmptyRounded from '@mui/icons-material/HourglassEmptyRounded';
import StatusChip from '../../components/StatusChip';
import { Readout, TraceSection } from '../../components/WorkbenchDialog';
import EmptyState from '../../components/EmptyState';
import { TOOL_NAMES, PLAN_ROLES, modelKey, modelLabel, nodeTitle } from '../../constants/labels';
import { clock, duration, nodeDuration, oneLine, parseJson, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';

function MetaRow({ label, value, mono, extra }) {
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline' }}>
      <Typography sx={{ fontSize: 12.5, color: googleTokens.textSecondary, minWidth: 70 }}>
        {label}
      </Typography>
      <Typography
        sx={{
          fontSize: 12.5,
          color: googleTokens.text,
          fontFamily: mono ? googleTokens.mono : 'inherit',
          overflowWrap: 'anywhere'
        }}
      >
        {value}
      </Typography>
      {extra}
    </Stack>
  );
}

function toolIcon(name) {
  return name === 'WORKSPACE_WRITE' || name === 'WORKSPACE_READ' ? (
    <DescriptionOutlined sx={{ fontSize: 18 }} />
  ) : (
    <SearchRounded sx={{ fontSize: 18 }} />
  );
}

function ToolRow({ call }) {
  const paths = parseJson(call.resourceVersion, null);
  const fileNames =
    paths && typeof paths === 'object' && !Array.isArray(paths) ? Object.keys(paths) : [];
  const label =
    fileNames.length > 0 ? fileNames.join('、') : TOOL_NAMES[call.name] || call.name || '工具调用';
  const completed = call.status === 'COMPLETED';
  return (
    <Box
      sx={{
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 3,
        px: 1.5,
        py: 1.15,
        bgcolor: '#FFFFFF'
      }}
    >
      <Stack direction="row" spacing={1.25} sx={{ alignItems: 'flex-start' }}>
        <Box
          sx={{
            width: 30,
            height: 30,
            borderRadius: 2,
            bgcolor: '#F1F3F4',
            color: '#5F6368',
            display: 'grid',
            placeItems: 'center',
            flexShrink: 0
          }}
        >
          {toolIcon(call.name)}
        </Box>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography
            sx={{
              fontSize: 13,
              fontWeight: 500,
              overflowWrap: 'anywhere',
              fontFamily: fileNames.length ? googleTokens.mono : 'inherit'
            }}
          >
            {label}
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
            {completed ? '调用完成' : '调用中'} · {duration(call.durationMs)}
            {call.version != null ? ` · v${call.version}` : ''}
          </Typography>
          {(call.errorMessage || call.result) && (
            <Typography
              variant="caption"
              color={call.errorMessage ? 'error.main' : 'text.secondary'}
              sx={{ display: 'block', mt: 0.25, overflowWrap: 'anywhere' }}
            >
              {oneLine(call.errorMessage || call.result, 150)}
            </Typography>
          )}
        </Box>
        {completed ? (
          <CheckCircleRounded sx={{ fontSize: 18, color: googleTokens.successFg }} />
        ) : call.status === 'FAILED' ? (
          <ErrorOutlineRounded sx={{ fontSize: 18, color: googleTokens.errorFg }} />
        ) : (
          <HourglassEmptyRounded sx={{ fontSize: 18, color: googleTokens.warningFg }} />
        )}
      </Stack>
    </Box>
  );
}

function LongBody({ text, label = '交接正文' }) {
  const [expanded, setExpanded] = useState(false);
  if (!text) {
    return (
      <Typography variant="body2" color="text.secondary">
        {label}未记录。
      </Typography>
    );
  }
  const long = text.length > 260 || text.split('\n').length > 5;
  return (
    <Box>
      <Box
        sx={{
          position: 'relative',
          maxHeight: expanded ? 'none' : 120,
          overflow: 'hidden'
        }}
      >
        <Typography
          component="pre"
          variant="body2"
          sx={{
            m: 0,
            whiteSpace: 'pre-wrap',
            overflowWrap: 'anywhere',
            fontFamily: 'inherit',
            lineHeight: 1.65
          }}
        >
          {text}
        </Typography>
        {!expanded && long && (
          <Box
            sx={{
              position: 'absolute',
              inset: 'auto 0 0 0',
              height: 44,
              background: 'linear-gradient(transparent, #F8F9FA)'
            }}
          />
        )}
      </Box>
      {long && (
        <Button size="small" onClick={() => setExpanded(value => !value)} sx={{ mt: 0.25 }}>
          {expanded ? '收起正文' : '展开完整正文'}
        </Button>
      )}
    </Box>
  );
}

function HandoffRecord({ record, direction }) {
  const [expanded, setExpanded] = useState(false);
  const sender = record.senderNodeLabel || record.dependencyNodeLabel || '来源节点';
  const receiver = record.receiverNodeLabel || '接收节点';
  return (
    <Box sx={{ borderBottom: `1px solid ${googleTokens.outline}`, pb: 1 }}>
      <Stack
        direction="row"
        spacing={0.75}
        sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}
      >
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {direction === 'outbound' ? '发往' : '收到'} · {sender} → {receiver}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {clock(record.acceptedAt)} · 发送实例 #{shortId(record.senderAgentId)} · 接收实例 #
            {shortId(record.receiverAgentId)}
          </Typography>
        </Box>
        <Button size="small" onClick={() => setExpanded(value => !value)}>
          {expanded ? '收起记录' : '展开记录'}
        </Button>
      </Stack>
      {expanded && (
        <Stack spacing={0.9} sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary">
            发送尝试 #{shortId(record.senderAttemptId)} · 接收尝试 #
            {shortId(record.receiverAttemptId)} · 依赖节点{' '}
            {record.dependencyNodeLabel || shortId(record.dependencyNodeId)}
          </Typography>
          <LongBody text={record.sourceResult} />
          {record.toolEvidence?.length > 0 && (
            <Box>
              <Typography variant="caption" color="text.secondary">
                随交接附上的工具证据
              </Typography>
              {record.toolEvidence.map(evidence => (
                <Readout key={evidence.toolCallId} mono maxHeight={150}>
                  {TOOL_NAMES[evidence.tool] || evidence.tool} v{evidence.version} ·{' '}
                  {evidence.toolCallId}
                  {'\n'}
                  {evidence.resourceVersion || '无资源版本'}
                </Readout>
              ))}
            </Box>
          )}
          <Readout mono maxHeight={180}>
            {JSON.stringify(record, null, 2)}
          </Readout>
        </Stack>
      )}
    </Box>
  );
}

function HandoffSummary({ handoffs, selectedEdge, fromLabel, toLabel, dependency }) {
  const hasReceipt = handoffs.length > 0;
  if (selectedEdge) {
    return (
      <Box
        sx={{
          bgcolor: hasReceipt ? '#F6FBF7' : '#F8F9FA',
          border: '1px solid',
          borderColor: hasReceipt ? '#CEEAD6' : googleTokens.outline,
          borderRadius: 3,
          p: 1.5
        }}
      >
        <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            依赖连线 · {fromLabel} → {toLabel}
          </Typography>
          <Chip
            size="small"
            label={hasReceipt ? '已接收实际交接' : '未查到接收记录'}
            sx={{
              bgcolor: hasReceipt ? '#E6F4EA' : '#F1F3F4',
              color: hasReceipt ? '#137333' : '#5F6368'
            }}
          />
        </Stack>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
          {hasReceipt
            ? `查到 ${handoffs.length} 条接收记录；默认摘要如下，展开可看完整正文。`
            : '这是计划依赖，但当前没有持久化的接收记录，不能推断已经交接。'}
          {dependency?.status ? ` 前置节点状态：${dependency.status}` : ''}
        </Typography>
        {hasReceipt && (
          <Stack spacing={1} sx={{ mt: 1 }}>
            {handoffs.map(handoff => (
              <Box key={`${handoff.senderAttemptId}:${handoff.dependencyNodeId}`}>
                <Typography variant="caption" color="text.secondary">
                  {handoff.senderNodeLabel || '上游节点'} · {clock(handoff.acceptedAt)}
                </Typography>
                <LongBody text={handoff.sourceResult || '交接正文未记录。'} />
              </Box>
            ))}
          </Stack>
        )}
      </Box>
    );
  }

  if (!hasReceipt) {
    return (
      <Typography variant="body2" color="text.secondary">
        该节点尚未收到结构化交接；计划依赖完成结算后会显示摘要。
      </Typography>
    );
  }
  return (
    <Box
      sx={{
        bgcolor: googleTokens.canvas,
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 3,
        p: 1.5
      }}
    >
      <Stack spacing={1.1}>
        {handoffs.map(handoff => (
          <Box key={`${handoff.senderAttemptId}:${handoff.dependencyNodeId}`}>
            <Typography variant="caption" color="text.secondary">
              {handoff.senderNodeLabel || handoff.dependencyNodeLabel || '上游节点'} →{' '}
              {handoff.receiverNodeLabel || '当前节点'} · {clock(handoff.acceptedAt)}
            </Typography>
            <Typography
              variant="body2"
              sx={{ mt: 0.3, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
            >
              {oneLine(handoff.sourceResult, 320) || '交接正文未记录。'}
            </Typography>
          </Box>
        ))}
      </Stack>
    </Box>
  );
}

/**
 * 右侧节点详情：概览信息 + 工具回执 + 上游交接，深层证据折叠在下方。
 * 流程图上不再叠嵌套弹窗，所有下钻都收在这里。
 */
export default function NodeInspector({
  node,
  detail,
  loading,
  error,
  runId,
  taskId,
  generatedAt,
  executor,
  runModelMode,
  selectedEdge,
  cancelAction,
  onClose,
  onCancelRun,
  onOpenLogs
}) {
  const [confirmCancel, setConfirmCancel] = useState(false);
  const attempts = detail?.attempts ?? [];
  const tools = attempts.flatMap(attempt =>
    (attempt.tools ?? []).map(call => ({ ...call, attemptNo: attempt.number }))
  );
  const inbound = detail?.inboundHandoffs ?? [];
  const outbound = detail?.outboundHandoffs ?? [];
  const checks = detail?.checks ?? [];
  const nodeEvents = detail?.nodeEvents ?? [];
  const elapsed = nodeDuration(node, generatedAt);
  const hasAssembly = Boolean(
    node?.agentSkills?.length || node?.agentTools?.length || attempts.some(a => a.assembly)
  );
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
  const modelText = actualModel || `${runModelText}（尚无调用记录）`;
  const fixedModel =
    actualModelModes.size === 1 &&
    runModelMode &&
    [...actualModelModes][0] !== modelKey(runModelMode);
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
      <Stack direction="row" sx={{ px: 2.5, pt: 2, pb: 1.25, gap: 1, alignItems: 'flex-start' }}>
        <Stack sx={{ flex: 1, minWidth: 0 }}>
          <Typography
            component="h2"
            sx={{ fontSize: 18, fontWeight: 500, lineHeight: 1.4, overflowWrap: 'anywhere' }}
          >
            {nodeTitle(node, '节点详情')}
          </Typography>
          <Stack
            direction="row"
            spacing={0.75}

            sx={{ mt: 0.9, flexWrap: 'wrap', alignItems: 'center' }}
          >
            <StatusChip value={node?.status ?? 'PENDING'} />
            <Chip
              size="small"
              title={runId ? `运行 ${runId}` : undefined}
              label={`任务 ID：${shortId(taskId) || '—'}`}
              sx={{ bgcolor: '#F1F3F4', color: '#5F6368', fontFamily: googleTokens.mono }}
            />
          </Stack>
        </Stack>
        <Tooltip title="关闭详情">
          <IconButton onClick={onClose} size="small" aria-label="关闭节点详情">
            <CloseRounded fontSize="small" />
          </IconButton>
        </Tooltip>
      </Stack>

      <Box sx={{ px: 2.5, pb: 1.5 }}>
        <Stack spacing={0.45}>
          <MetaRow label="启动时间" value={node?.startedAt ? clock(node.startedAt) : '—'} mono />
          <MetaRow
            label="执行器"
            value={
              executor ||
              node?.agentName ||
              (node?.definitionId
                ? `${node.definitionId} v${node.definitionVersion ?? '—'}`
                : 'AutoDev-Agent v2')
            }
          />
          <MetaRow
            label="模型"
            value={modelText}
            extra={
              fixedModel ? (
                <Chip
                  size="small"
                  label="固定模型"
                  sx={{ bgcolor: '#E8F0FE', color: '#1967D2', height: 20 }}
                />
              ) : null
            }
          />
          {elapsed != null && <MetaRow label="耗时" value={duration(elapsed)} mono />}
          {node?.role && <MetaRow label="角色" value={PLAN_ROLES[node.role] || node.role} />}
        </Stack>
      </Box>

      <Divider />

      <Box sx={{ flex: 1, minHeight: 0, overflowY: 'auto', px: 2.5, py: 2 }}>
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

        <Typography sx={{ fontSize: 13.5, fontWeight: 600, mb: 1 }}>工具调用与写入回执</Typography>
        <Stack spacing={0.9}>
          {tools.length > 0 ? (
            tools.map(call => <ToolRow key={call.id} call={call} />)
          ) : (
            <Typography variant="body2" color="text.secondary">
              {node?.status === 'RUNNING'
                ? '节点正在执行，工具调用回执会在完成后写入。'
                : '该节点没有工具调用记录。'}
            </Typography>
          )}
        </Stack>

        <Typography sx={{ fontSize: 13.5, fontWeight: 600, mt: 2.5, mb: 1 }}>
          上游交接摘要
        </Typography>
        <HandoffSummary
          handoffs={edgeHandoffs}
          selectedEdge={selectedEdge}
          fromLabel={edgeFromLabel}
          toLabel={edgeToLabel}
          dependency={dependency}
        />

        {(inbound.length > 0 || outbound.length > 0) && (
          <TraceSection
            title="全部交接记录"
            subtitle={`入站 ${inbound.length} 条 · 出站 ${outbound.length} 条`}
            defaultExpanded={Boolean(selectedEdge)}
          >
            <Stack spacing={1}>
              {inbound.map(record => (
                <HandoffRecord
                  key={`in:${record.senderAttemptId}:${record.dependencyNodeId}`}
                  record={record}
                  direction="inbound"
                />
              ))}
              {outbound.map(record => (
                <HandoffRecord
                  key={`out:${record.receiverAttemptId}:${record.dependencyNodeId}`}
                  record={record}
                  direction="outbound"
                />
              ))}
            </Stack>
          </TraceSection>
        )}

        {detail?.result && (
          <>
            <Typography sx={{ fontSize: 13.5, fontWeight: 600, mt: 2.5, mb: 1 }}>
              节点结果
            </Typography>
            <Box sx={{ bgcolor: googleTokens.canvas, borderRadius: 2.5, p: 1.4 }}>
              <LongBody text={detail.result} label="节点结果" />
            </Box>
          </>
        )}

        {checks.length > 0 && (
          <Box sx={{ mt: 1 }}>
            <TraceSection title={`系统检查 · ${checks.length} 条`} subtitle="证据快照">
              <Stack spacing={1}>
                {checks.map(check => (
                  <Box key={check.id}>
                    <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
                      <StatusChip value={check.status} />
                      <Typography variant="caption" color="text.secondary">
                        {check.checkId} v{check.checkVersion}
                      </Typography>
                    </Stack>
                    {check.summary && (
                      <Typography variant="body2" sx={{ mt: 0.4, whiteSpace: 'pre-wrap' }}>
                        {check.summary}
                      </Typography>
                    )}
                    {check.evidence && (
                      <Box sx={{ mt: 0.5 }}>
                        <Readout maxHeight={160}>{check.evidence}</Readout>
                      </Box>
                    )}
                  </Box>
                ))}
              </Stack>
            </TraceSection>
          </Box>
        )}

        {hasAssembly && (
          <TraceSection title="Agent 装配快照" subtitle={`定义 ${node.definitionId ?? '—'}`}>
            <Stack spacing={1}>
              <Typography variant="body2">
                Skill：{(node.agentSkills ?? []).map(item => item.name).join('、') || '无'}
              </Typography>
              <Typography variant="body2">
                工具：{(node.agentTools ?? []).map(item => item.name).join('、') || '无'}
              </Typography>
              {node.agentReason && (
                <Typography variant="caption" color="text.secondary">
                  选择理由：{node.agentReason}
                </Typography>
              )}
              {attempts
                .filter(attempt => attempt.assembly)
                .map(attempt => (
                  <Box key={`assembly:${attempt.id}`}>
                    <Typography variant="caption" color="text.secondary">
                      尝试 {attempt.number} · 实际装配
                      {attempt.assembly?.components?.model?.name
                        ? ` · 模型 ${modelLabel(
                            attempt.assembly.components.model.mode ||
                              attempt.assembly.components.model.name
                          )}`
                        : ''}
                      {attempt.assembly?.components?.input?.sources?.length
                        ? ` · 输入来源 ${attempt.assembly.components.input.sources.length} 个`
                        : ''}
                    </Typography>
                    <Readout mono maxHeight={240}>
                      {JSON.stringify(attempt.assembly, null, 2)}
                    </Readout>
                  </Box>
                ))}
            </Stack>
          </TraceSection>
        )}

        {nodeEvents.length > 0 && (
          <TraceSection title="节点时间线" subtitle={`${nodeEvents.length} 条事件`}>
            <Stack spacing={0.9}>
              {nodeEvents.map(event => (
                <Box key={event.id}>
                  <Typography variant="caption" color="text.secondary">
                    {clock(event.occurredAt)} · {event.type}
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

        {!node && (
          <EmptyState
            compact
            title="选择一个节点"
            description="点击流程图上的节点查看工具回执、交接摘要与检查证据。"
          />
        )}
      </Box>

      <Divider />
      <Stack direction="row" spacing={1} sx={{ px: 2, py: 1.5 }}>
        <Button size="small" variant="outlined" onClick={onOpenLogs} sx={{ flex: 1 }}>
          查看完整日志
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
              取消运行
            </Button>
          </span>
        </Tooltip>
      </Stack>

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
