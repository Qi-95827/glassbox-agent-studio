import { memo, useCallback, useEffect, useMemo } from 'react';
import { Box, Stack, Typography, useMediaQuery } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import {
  Background,
  Handle,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow
} from '@xyflow/react';
import dagre from '@dagrejs/dagre';
import '@xyflow/react/dist/style.css';
import StatusChip from '../../components/StatusChip';
import { statusTone, nodeTitle } from '../../constants/labels';
import { nodeDuration } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

const handleStyle = { opacity: 0, pointerEvents: 'none' };

const toneColor = {
  success: '#34A853',
  active: '#1A73E8',
  warning: '#F29900',
  error: '#EA4335',
  neutral: '#9AA0A6',
  info: '#1A73E8'
};

/**
 * 节点卡片。
 *
 * <p>运行详情每 1.8s 轮询一次执行视图。若每次轮询都重建整张画布的节点组件，
 * 30 个节点的图会持续产生数百次无意义的样式与布局计算，滚动与抽屉都会卡顿。
 * 这里用 `memo` + 显式比较器，只在「节点数据、选中态、断点、紧凑模式、语言」真正变化时重绘。
 *
 * <p>React Query 的结构共享保证未变化的节点对象保持同一引用，因此比较器按引用比较 `node`
 * 即可；不为每个字段做深比较，避免把节省下来的渲染时间又花在比较上。
 */
const FlowNode = memo(
  function FlowNode({ data }) {
    const { node, selected, compact, onSelect, generatedAt, hasBreakpoint, t } = data;
    const running = node.status === 'RUNNING';
    const duration = nodeDuration(node, generatedAt);
    return (
      <>
        <Handle type="target" position={Position.Left} style={handleStyle} />
        <Box
          role="button"
          tabIndex={0}
          aria-label={nodeTitle(node, t('common.unnamedStep'))}
          aria-pressed={selected}
          onClick={() => onSelect?.(node.id)}
          onKeyDown={event => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              onSelect?.(node.id);
            }
          }}
          sx={{
            width: compact ? 150 : 224,
            minHeight: compact ? 64 : 88,
            display: 'flex',
            bgcolor: '#FFFFFF',
            border: `${selected || running ? '2px' : '1px'} solid ${
              running ? '#1A73E8' : selected ? '#5F6368' : googleTokens.outline
            }`,
            borderRadius: compact ? '12px' : '14px',
            overflow: 'hidden',
            cursor: 'pointer',
            boxShadow: running
              ? '0 0 0 4px rgba(26,115,232,.10), 0 8px 20px rgba(26,115,232,.16)'
              : selected
                ? '0 4px 14px rgba(60,64,67,.16)'
                : '0 1px 2px rgba(60,64,67,.10)',
            transition: 'border-color .18s ease, box-shadow .18s ease',
            '&:hover': { borderColor: '#1A73E8' }
          }}
        >
          <Box
            sx={{
              width: 4,
              flexShrink: 0,
              bgcolor: toneColor[statusTone(node.status)] || toneColor.neutral
            }}
          />
          <Stack
            spacing={compact ? 0.55 : 0.9}
            sx={{
              flex: 1,
              minWidth: 0,
              px: compact ? 1.1 : 1.6,
              py: compact ? 0.8 : 1.3,
              justifyContent: 'center'
            }}
          >
            <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', minWidth: 0 }}>
              <Typography
                noWrap
                title={nodeTitle(node)}
                sx={{
                  flex: 1,
                  minWidth: 0,
                  fontSize: compact ? 12.5 : 14,
                  fontWeight: 600,
                  lineHeight: 1.35,
                  color: googleTokens.text
                }}
              >
                {nodeTitle(node)}
              </Typography>
              {hasBreakpoint && (
                <Box
                  component="span"
                  title={t('run.breakpointTooltip')}
                  sx={{
                    px: 0.6,
                    py: 0.1,
                    borderRadius: 9999,
                    bgcolor: '#FCE8E6',
                    color: '#C5221F',
                    fontSize: 10,
                    fontWeight: 600,
                    flexShrink: 0
                  }}
                >
                  {t('run.breakpointBadge')}
                </Box>
              )}
            </Stack>
            <Stack direction="row" spacing={compact ? 0.6 : 1} sx={{ alignItems: 'center' }}>
              <StatusChip value={node.status} />
              {duration != null && (
                <Typography
                  sx={{ fontSize: compact ? 10.5 : 11.5, color: googleTokens.textSecondary }}
                >
                  {duration < 1000
                    ? `${Math.round(duration)}ms`
                    : `${(duration / 1000).toFixed(1)}s`}
                </Typography>
              )}
              {node.attemptCount > 1 && !compact && (
                <Typography sx={{ fontSize: 11.5, color: googleTokens.textSecondary }}>
                  {t('run.attemptCount', { count: node.attemptCount })}
                </Typography>
              )}
            </Stack>
          </Stack>
        </Box>
        <Handle type="source" position={Position.Right} style={handleStyle} />
      </>
    );
  },
  (previous, next) =>
    previous.data.node === next.data.node &&
    previous.data.selected === next.data.selected &&
    previous.data.hasBreakpoint === next.data.hasBreakpoint &&
    previous.data.compact === next.data.compact &&
    previous.data.generatedAt === next.data.generatedAt &&
    previous.data.onSelect === next.data.onSelect &&
    previous.data.t === next.data.t
);

const nodeTypes = { trace: FlowNode };

function layout(nodes, edges, width, height, gap) {
  const graph = new dagre.graphlib.Graph()
    .setGraph({
      rankdir: 'LR',
      nodesep: gap,
      ranksep: Math.round(gap * 1.6),
      marginx: 12,
      marginy: 12
    })
    .setDefaultEdgeLabel(() => ({}));

  nodes.forEach(node => graph.setNode(node.id, { width, height }));
  edges.forEach(edge => {
    if (graph.hasNode(edge.fromNodeId) && graph.hasNode(edge.toNodeId)) {
      graph.setEdge(edge.fromNodeId, edge.toNodeId);
    }
  });
  dagre.layout(graph);
  return new Map(
    nodes.map(node => {
      const point = graph.node(node.id);
      return [node.id, { x: point.x - width / 2, y: point.y - height / 2 }];
    })
  );
}

function FlowCanvas({
  nodes = [],
  edges = [],
  revisionEdges = [],
  runId,
  selectedId,
  selectedEdgeId,
  focusIds,
  breakpoints,
  generatedAt,
  viewportKey,
  onSelectNode,
  onSelectEdge
}) {
  const { t } = useI18n();
  const theme = useTheme();
  const compact = useMediaQuery(theme.breakpoints.down('lg'));
  const width = compact ? 150 : 224;
  const height = compact ? 64 : 88;
  const gap = compact ? 24 : 38;
  const { fitView } = useReactFlow();

  // 回调引用稳定：父组件每次渲染都会传入新的内联函数，直接放进 data 会让 memo 完全失效。
  const handleSelect = useCallback(id => onSelectNode?.(id), [onSelectNode]);
  const handleEdgeSelect = useCallback(edge => onSelectEdge?.(edge), [onSelectEdge]);

  const positions = useMemo(
    () => layout(nodes, edges, width, height, gap),
    [nodes, edges, width, height, gap]
  );
  const byId = useMemo(() => new Map(nodes.map(node => [node.id, node])), [nodes]);

  useEffect(() => {
    if (!nodes.length) return undefined;
    const timer = setTimeout(() => fitView({ padding: 0.2, maxZoom: 1, duration: 220 }), 60);
    return () => clearTimeout(timer);
  }, [fitView, nodes.length, runId, viewportKey, compact]);

  const graphNodes = useMemo(
    () =>
      nodes.map(node => ({
        id: node.id,
        type: 'trace',
        position: positions.get(node.id) ?? { x: 0, y: 0 },
        draggable: false,
        selectable: false,
        data: {
          node,
          selected: selectedId === node.id,
          hasBreakpoint: Boolean(breakpoints?.has(node.id)),
          compact,
          onSelect: handleSelect,
          generatedAt,
          t
        }
      })),
    [nodes, positions, selectedId, breakpoints, compact, handleSelect, generatedAt, t]
  );

  const graphEdges = useMemo(() => {
    const hasFocus = Boolean(focusIds?.size);
    const dependencyEdges = edges
      .filter(edge => byId.has(edge.fromNodeId) && byId.has(edge.toNodeId))
      .map(edge => {
        const id = `${edge.fromNodeId}:${edge.toNodeId}`;
        const selectedEdge = selectedEdgeId === id;
        const highlighted =
          selectedEdge ||
          edge.fromNodeId === selectedId ||
          edge.toNodeId === selectedId ||
          focusIds?.has(edge.fromNodeId) ||
          focusIds?.has(edge.toNodeId);
        const stroke = selectedEdge
          ? '#1967D2'
          : highlighted
            ? googleTokens.primary
            : hasFocus
              ? '#9DB3C8'
              : '#B6C2D2';
        return {
          id,
          source: edge.fromNodeId,
          target: edge.toNodeId,
          type: 'default',
          interactionWidth: 24,
          style: {
            stroke,
            strokeWidth: selectedEdge ? 2.75 : highlighted ? 2 : 1.6,
            opacity: highlighted || selectedEdge ? 1 : 0.9
          },
          data: { edge },
          ariaLabel: t('run.flowDependency')
        };
      });

    const reflectionEdges = (revisionEdges ?? [])
      .filter(edge => byId.has(edge.reviewerNodeId) && byId.has(edge.targetNodeId))
      .map(edge => {
        const label = t('run.revisionEdge', {
          count: edge.revisionCount ?? 0,
          max: edge.maxRevisions ?? 0
        });
        return {
          id: `revision:${edge.reviewerNodeId}:${edge.targetNodeId}`,
          source: edge.reviewerNodeId,
          target: edge.targetNodeId,
          type: 'default',
          interactionWidth: 20,
          label,
          labelStyle: { fill: '#B06000', fontSize: 10.5, fontWeight: 600 },
          labelBgStyle: { fill: '#FEF7E0', fillOpacity: 0.95 },
          labelBgPadding: [4, 2],
          labelBgBorderRadius: 4,
          style: { stroke: '#B06000', strokeWidth: 1.5, strokeDasharray: '6 4' },
          data: { revisionEdge: edge },
          ariaLabel: label
        };
      });

    return [...dependencyEdges, ...reflectionEdges];
  }, [edges, byId, selectedEdgeId, selectedId, focusIds, revisionEdges, t]);

  return (
    <Box sx={{ width: '100%', height: '100%', minHeight: 280, bgcolor: googleTokens.canvas }}>
      <ReactFlow
        key={runId || 'draft'}
        nodes={graphNodes}
        edges={graphEdges}
        nodeTypes={nodeTypes}
        onNodeClick={(_, node) => handleSelect(node.id)}
        onEdgeClick={(_, edge) => handleEdgeSelect(edge.data?.edge)}
        nodesDraggable={false}
        nodesConnectable={false}
        nodesFocusable={false}
        elementsSelectable={false}
        deleteKeyCode={null}
        fitView
        fitViewOptions={{ padding: 0.2, maxZoom: 1 }}
        minZoom={0.25}
        maxZoom={1.6}
      >
        <Background color="#D5DBE3" gap={20} size={1.2} />
      </ReactFlow>
    </Box>
  );
}

/**
 * 运行流程图：服务端节点与依赖为准，前端只负责布局。
 * 节点卡采用 M3 白底 + 左侧语义色条；运行节点高亮为 Google Blue。
 */
export default function RunFlowGraph(props) {
  return (
    <ReactFlowProvider>
      <FlowCanvas {...props} />
    </ReactFlowProvider>
  );
}
