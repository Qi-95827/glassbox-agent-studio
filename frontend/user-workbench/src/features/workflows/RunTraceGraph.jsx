import React, { useMemo } from 'react';
import { Box, ButtonBase, Chip, Paper, Stack, Typography, useMediaQuery } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import AccountTreeOutlined from '@mui/icons-material/AccountTreeOutlined';
import FactCheckOutlined from '@mui/icons-material/FactCheckOutlined';
import { Background, BaseEdge, Controls, Handle, MarkerType, Position, ReactFlow } from '@xyflow/react';
import dagre from '@dagrejs/dagre';
import '@xyflow/react/dist/style.css';

const width = 190;
const height = 108;
const handleStyle = { opacity: 0, pointerEvents: 'none' };
const status = {
  PENDING: ['待执行', 'default'],
  RUNNING: ['运行中', 'primary'],
  PAUSED: ['已暂停', 'warning'],
  CANCELLING: ['正在取消', 'warning'],
  COMPLETED: ['已完成', 'success'],
  REUSED: ['复用历史结果', 'info'],
  FAILED: ['失败', 'error'],
  BLOCKED: ['等待处理', 'warning'],
  LIMITED: ['已超限', 'warning'],
  INTERRUPTED: ['已中断', 'warning'],
  CANCELLED: ['已取消', 'default'],
  SKIPPED: ['未执行', 'default']
};

export function RunStatusChip({ value }) {
  const [label, color] = status[value] || [value || '未记录', 'default'];
  return <Chip size="small" label={label} color={color} variant="outlined" />;
}

export const traceNodeLabel = node => node?.label || node?.key || '未记录步骤';

function TraceNode({ data }) {
  const { node, isSelected, isActive, onSelect, vertical } = data;
  const isCheck = node.kind === 'SYSTEM_CHECK';
  const Icon = isCheck ? FactCheckOutlined : AccountTreeOutlined;

  return <>
    <Handle type="target" position={vertical ? Position.Top : Position.Left} style={handleStyle} />
    <Paper variant="outlined" sx={{
      width, height, overflow: 'hidden',
      borderColor: isSelected ? 'primary.main' : 'divider',
      bgcolor: isSelected ? 'action.selected' : 'background.paper'
    }}>
      <ButtonBase className="nodrag" onClick={() => onSelect(node.id)}
        aria-pressed={isSelected}
        aria-label={traceNodeLabel(node)}
        sx={{ display: 'block', textAlign: 'left', width: '100%', height: '100%', px: 1.5, py: 1 }}>
        <Stack spacing={0.45}>
          <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
            <Icon fontSize="small" color={isActive ? 'primary' : 'action'} />
            <Typography variant="caption" color="text.secondary">
              {isCheck ? '系统检查' : 'Agent'}
            </Typography>
          </Stack>
          <Typography variant="subtitle2" noWrap title={traceNodeLabel(node)}>
            {traceNodeLabel(node)}
          </Typography>
          <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
            <RunStatusChip value={node.status} />
            {node.attemptCount > 0 && <Typography variant="caption" color="text.secondary">
              {node.attemptCount} 次尝试
            </Typography>}
          </Stack>
        </Stack>
      </ButtonBase>
    </Paper>
    <Handle type="source" position={vertical ? Position.Bottom : Position.Right} style={handleStyle} />
  </>;
}

const nodeTypes = { trace: TraceNode };
const edgeKey = edge => `${edge.fromNodeId}:${edge.toNodeId}`;

function TraceEdge({ id, sourceX, sourceY, targetX, targetY, data, style, markerEnd, interactionWidth, label }) {
  const points = [{ x: sourceX, y: sourceY }, ...(data.points ?? []).slice(1, -1), { x: targetX, y: targetY }];
  const path = points.map(({ x, y }, index) => `${index === 0 ? 'M' : 'L'} ${x} ${y}`).join(' ');
  const midpoint = points[Math.floor(points.length / 2)];
  return <BaseEdge id={id} path={path} style={style} markerEnd={markerEnd} interactionWidth={interactionWidth}
    label={label} labelX={midpoint.x} labelY={midpoint.y} labelBgPadding={[5, 3]} labelBgBorderRadius={4} />;
}

const edgeTypes = { trace: TraceEdge };

function positions(nodes, edges, vertical) {
  const graph = new dagre.graphlib.Graph()
    .setGraph({ rankdir: vertical ? 'TB' : 'LR', nodesep: 25, ranksep: 52 })
    .setDefaultEdgeLabel(() => ({}));

  nodes.forEach(node => graph.setNode(node.id, { width, height }));
  edges.forEach(edge => {
    if (graph.hasNode(edge.fromNodeId) && graph.hasNode(edge.toNodeId)) {
      graph.setEdge(edge.fromNodeId, edge.toNodeId);
    }
  });
  dagre.layout(graph);
  return {
    nodes: new Map(nodes.map(node => {
      const point = graph.node(node.id);
      return [node.id, { x: point.x - width / 2, y: point.y - height / 2 }];
    })),
    edges: new Map(graph.edges().map(edge => [`${edge.v}:${edge.w}`, graph.edge(edge).points]))
  };
}

/** 计划依赖以服务端为准，已有回执时才把选中连线标成已接收。 */
export default function RunTraceGraph({
  nodes, edges, runId, selectedId, selectedEdge, focusIds, nodeDetail, onSelectNode, onSelectEdge
}) {
  const theme = useTheme();
  const vertical = useMediaQuery(theme.breakpoints.down('sm'));
  const layout = useMemo(() => positions(nodes, edges, vertical), [nodes, edges, vertical]);
  const graphNodes = nodes.map(node => ({
    id: node.id,
    type: 'trace',
    position: layout.nodes.get(node.id),
    data: { node, isSelected: selectedId === node.id, isActive: focusIds.has(node.id), onSelect: onSelectNode, vertical }
  }));
  const byId = new Map(nodes.map(node => [node.id, node]));
  const receipts = [
    ...(nodeDetail?.inboundHandoffs ?? []), ...(nodeDetail?.outboundHandoffs ?? []), ...(nodeDetail?.checkReceipts ?? [])
  ];
  const graphEdges = edges.filter(edge => byId.has(edge.fromNodeId) && byId.has(edge.toNodeId)).map(edge => {
    const id = edgeKey(edge);
    const active = selectedEdge && id === edgeKey(selectedEdge);
    const received = receipts.some(receipt =>
      receipt.receiverNodeId === edge.toNodeId && receipt.dependencyNodeId === edge.fromNodeId)
      || (edge.toNodeId === nodeDetail?.node?.id && nodeDetail.checks?.some(check =>
        check.inputs?.some(input => input.dependencyNodeId === edge.fromNodeId)));
    const color = active ? theme.palette.primary.main
      : received ? theme.palette.success.main : theme.palette.text.disabled;
    return {
      id,
      source: edge.fromNodeId,
      target: edge.toNodeId,
      data: { edge, points: layout.edges.get(id) },
      type: 'trace',
      label: active ? (received ? '已接收' : '查看记录') : undefined,
      ariaLabel: `${traceNodeLabel(byId.get(edge.fromNodeId))} 到 ${traceNodeLabel(byId.get(edge.toNodeId))}的交接`,
      interactionWidth: 24,
      style: { stroke: color, strokeWidth: active ? 2.75 : 1.6, strokeDasharray: received ? undefined : '5 4' },
      markerEnd: { type: MarkerType.ArrowClosed, color }
    };
  });

  function openFocusedEdge(event) {
    if ((event.key !== 'Enter' && event.key !== ' ') || event.altKey || event.ctrlKey || event.metaKey) return;
    const id = event.target.closest('.react-flow__edge')?.getAttribute('data-id');
    const edge = graphEdges.find(item => item.id === id);
    if (edge) {
      event.preventDefault();
      onSelectEdge(edge.data.edge);
    }
  }

  return <Paper variant="outlined">
    <Box onKeyDown={openFocusedEdge} sx={{ height: vertical ? 375 : nodes.length > 4 ? 348 : 278, minWidth: 0, bgcolor: 'grey.50' }}>
      <ReactFlow key={`${runId}:${vertical}`} nodes={graphNodes} edges={graphEdges} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
        onEdgeClick={(_, edge) => onSelectEdge(edge.data.edge)}
        nodesDraggable={false} nodesConnectable={false} nodesFocusable={false} deleteKeyCode={null}
        fitView fitViewOptions={{ padding: 0.18, maxZoom: 1 }}
        minZoom={0.2} maxZoom={2} zoomOnScroll={false} onlyRenderVisibleElements>
        <Background color={theme.palette.divider} gap={18} />
        <Controls position="bottom-left" showInteractive={false} />
      </ReactFlow>
    </Box>
    <Typography component="p" variant="caption" color="text.secondary" sx={{ m: 0, px: 1.5, py: 1 }}>
      点节点看执行，点连线看交接。虚线表示计划依赖，实线表示已查到接收记录。
    </Typography>
  </Paper>;
}
