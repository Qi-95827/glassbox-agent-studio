import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Typography
} from '@mui/material';
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
import AddRounded from '@mui/icons-material/AddRounded';
import CloudUploadRounded from '@mui/icons-material/CloudUploadRounded';
import DeleteOutlineRounded from '@mui/icons-material/DeleteOutlineRounded';
import { plansApi } from '../../api/plans';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

const NODE_WIDTH = 190;
const NODE_HEIGHT = 76;

/** 冲突码 → 前端提示键；未知码原样显示，避免新后端枚举被静默吞掉。 */
const CONFLICT_LABEL = {
  WRITE_CONFLICT: 'composer.conflictWrite',
  CYCLE: 'composer.conflictCycle',
  INVALID_SCOPE: 'composer.conflictScope',
  UNKNOWN_DEPENDENCY: 'composer.conflictDependency',
  DUPLICATE_KEY: 'composer.conflictDuplicate'
};

const ROLE_OPTIONS = ['ANALYZE', 'IMPLEMENT', 'REVIEW'];

/** 前端只做展示需要的规范化；真正的合法性判定全部在服务端。 */
export function draftFromCandidate(candidate) {
  const nodes = (candidate?.nodes ?? []).map(node => ({
    key: node.key,
    title: node.title ?? node.key,
    role: node.role ?? 'IMPLEMENT',
    objective: node.objective ?? '',
    dependsOn: [...(node.dependsOn ?? [])],
    readFiles: [...(node.readFiles ?? [])],
    writeFiles: [...(node.writeFiles ?? [])],
    expectedOutputs: [...(node.expectedOutputs ?? [])],
    acceptanceCriteria: [...(node.acceptanceCriteria ?? [])]
  }));
  const revisionEdges = (candidate?.revisionEdges ?? []).map(edge => ({
    fromReviewerNodeKey: edge.fromReviewerNodeKey ?? edge.reviewerNodeKey ?? '',
    toTargetNodeKey: edge.toTargetNodeKey ?? edge.targetNodeKey ?? '',
    maxRevisions: edge.maxRevisions ?? 2
  }));
  return {
    schemaVersion: candidate?.schemaVersion ?? 1,
    nodes,
    revisionEdges,
    // 假设与待确认事项不在画布上编辑，但必须原样带回，否则应用一次就会丢模型的说明。
    assumptions: [...(candidate?.assumptions ?? [])],
    openQuestions: [...(candidate?.openQuestions ?? [])]
  };
}

/** 画布布局：与运行详情同一套 Dagre 参数，保持两处图形语言一致。 */
function layout(nodes, edges) {
  const graph = new dagre.graphlib.Graph()
    .setGraph({ rankdir: 'LR', nodesep: 28, ranksep: 56, marginx: 16, marginy: 16 })
    .setDefaultEdgeLabel(() => ({}));
  nodes.forEach(node => graph.setNode(node.key, { width: NODE_WIDTH, height: NODE_HEIGHT }));
  edges.forEach(edge => {
    if (graph.hasNode(edge.from) && graph.hasNode(edge.to)) graph.setEdge(edge.from, edge.to);
  });
  dagre.layout(graph);
  return new Map(
    nodes.map(node => {
      const point = graph.node(node.key);
      return [node.key, { x: point.x - NODE_WIDTH / 2, y: point.y - NODE_HEIGHT / 2 }];
    })
  );
}

function ComposerNode({ data }) {
  const { node, conflicts, selected } = data;
  const { t } = useI18n();
  const bad = conflicts.length > 0;
  const codes = [...new Set(conflicts.map(item => item.code))];
  return (
    <>
      <Handle type="target" position={Position.Left} style={{ opacity: 0 }} />
      <Box
        data-testid={`composer-node-${node.key}`}
        data-conflict={bad ? codes.join(',') : ''}
        sx={{
          width: NODE_WIDTH,
          minHeight: NODE_HEIGHT,
          p: 1.1,
          borderRadius: 2.5,
          bgcolor: '#FFFFFF',
          border: `${bad || selected ? '2px' : '1px'} solid ${
            bad ? '#C5221F' : selected ? '#1A73E8' : googleTokens.outline
          }`,
          boxShadow: bad
            ? '0 0 0 3px rgba(197,34,31,.14)'
            : selected
              ? '0 0 0 3px rgba(26,115,232,.14)'
              : '0 1px 2px rgba(60,64,67,.1)',
          animation: bad ? 'composerPulse 1.2s ease-in-out infinite' : 'none',
          '@keyframes composerPulse': {
            '0%, 100%': { boxShadow: '0 0 0 3px rgba(197,34,31,.10)' },
            '50%': { boxShadow: '0 0 0 5px rgba(197,34,31,.22)' }
          }
        }}
      >
        <Typography sx={{ fontSize: 12.5, fontWeight: 600 }} noWrap title={node.title}>
          {node.title || node.key}
        </Typography>
        <Stack direction="row" spacing={0.4} sx={{ mt: 0.4, flexWrap: 'wrap', rowGap: 0.4 }}>
          <Chip size="small" label={node.role} sx={{ height: 18, fontSize: 10 }} />
          <Chip
            size="small"
            variant="outlined"
            label={t('composer.writeCount', { count: node.writeFiles.length })}
            sx={{ height: 18, fontSize: 10 }}
          />
        </Stack>
        {bad && (
          <Stack spacing={0.3} sx={{ mt: 0.4 }}>
            {codes.map(code => (
              <Typography
                key={code}
                data-testid={`composer-conflict-${code}`}
                sx={{ fontSize: 10, color: '#C5221F', fontWeight: 600 }}
              >
                {CONFLICT_LABEL[code] ? t(CONFLICT_LABEL[code]) : code}
              </Typography>
            ))}
            {conflicts
              .flatMap(item => item.paths)
              .slice(0, 2)
              .map(path => (
                <Chip
                  key={`${path}`}
                  size="small"
                  label={path}
                  sx={{ height: 17, fontSize: 9.5, fontFamily: googleTokens.mono }}
                />
              ))}
          </Stack>
        )}
      </Box>
      <Handle type="source" position={Position.Right} style={{ opacity: 0 }} />
    </>
  );
}

const nodeTypes = { composer: ComposerNode };

function Canvas({ draft, analysis, selectedKey, onSelectNode, onSelectReflection }) {
  const { t } = useI18n();
  const { fitView } = useReactFlow();

  // 服务端已直接给出 byNodeKey；仍保留按 conflicts 归组的兜底，避免历史/代理响应缺字段时红框整体消失。
  const conflictsByNode = useMemo(() => {
    if (!analysis) return {};
    if (analysis.byNodeKey && typeof analysis.byNodeKey === 'object') return analysis.byNodeKey;
    const grouped = {};
    for (const conflict of analysis.conflicts ?? []) {
      for (const key of conflict.nodeKeys ?? []) {
        (grouped[key] ??= []).push(conflict);
      }
    }
    return grouped;
  }, [analysis]);

  const dependencyEdges = useMemo(() => {
    const result = [];
    (draft.nodes ?? []).forEach(node => {
      (node.dependsOn ?? []).forEach(target => {
        result.push({
          id: `dep:${target}->${node.key}`,
          source: target,
          target: node.key,
          type: 'default',
          style: { stroke: '#5F6368', strokeWidth: 1.6 }
        });
      });
    });
    return result;
  }, [draft.nodes]);

  const reflectionEdges = useMemo(
    () =>
      (draft.revisionEdges ?? []).map((edge, index) => ({
        id: `rev:${index}:${edge.fromReviewerNodeKey}->${edge.toTargetNodeKey}`,
        source: edge.fromReviewerNodeKey,
        target: edge.toTargetNodeKey,
        type: 'default',
        label: t('composer.maxRevisionsShort', { count: edge.maxRevisions }),
        labelStyle: { fill: '#B06000', fontSize: 10.5, fontWeight: 600 },
        labelBgStyle: { fill: '#FEF7E0', fillOpacity: 0.95 },
        style: { stroke: '#B06000', strokeWidth: 1.6, strokeDasharray: '6 4' },
        data: { reflectionIndex: index }
      })),
    [draft.revisionEdges, t]
  );

  const edges = useMemo(
    () => [...dependencyEdges, ...reflectionEdges],
    [dependencyEdges, reflectionEdges]
  );
  const positions = useMemo(() => layout(draft.nodes ?? [], edges), [draft.nodes, edges]);

  const graphNodes = useMemo(
    () =>
      (draft.nodes ?? []).map(node => ({
        id: node.key,
        type: 'composer',
        position: positions.get(node.key) ?? { x: 0, y: 0 },
        draggable: false,
        data: {
          node,
          selected: selectedKey === node.key,
          conflicts: conflictsByNode[node.key] ?? []
        }
      })),
    [draft.nodes, positions, selectedKey, conflictsByNode]
  );

  useEffect(() => {
    if (!graphNodes.length) return undefined;
    const timer = setTimeout(() => fitView({ padding: 0.2, maxZoom: 1, duration: 200 }), 80);
    return () => clearTimeout(timer);
  }, [fitView, graphNodes.length]);

  return (
    <Box sx={{ height: 320, bgcolor: googleTokens.canvas, borderRadius: 2 }}>
      <ReactFlow
        nodes={graphNodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodeClick={(_, node) => onSelectNode(node.id)}
        onEdgeClick={(_, edge) =>
          edge.data?.reflectionIndex != null && onSelectReflection(edge.data.reflectionIndex)
        }
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
        deleteKeyCode={null}
        fitView
        minZoom={0.3}
        maxZoom={1.5}
      >
        <Background color="#D5DBE3" gap={18} size={1.2} />
      </ReactFlow>
    </Box>
  );
}

/**
 * 可视化编排画布。
 *
 * <p><b>边界（务必保留）</b>：可以增删节点、改角色与读写集合、连依赖、调反思回边的
 * {@code maxRevisions}，并实时调用受鉴权的校验接口看结构是否合法；改动只有点了
 * 「应用画布到计划」才会落库——应用时服务端会再用同一份 {@code PlanCandidateValidator}
 * 复核一遍，不通过就整份拒绝、候选保持原样。落库后仍是「待确认候选」，
 * 依然要走既有的摘要复核与人工确认才会创建运行，画布不会绕过冻结与授权。
 *
 * @param candidate 服务端返回的候选计划（只读投影）。
 * @param onApply 应用回调：收到画布草稿后负责落库，返回 {@code {applied, errors, candidate}}。
 */
export function PlanComposerCanvasInner({ candidate, onApply }) {
  const { t } = useI18n();
  const [draft, setDraft] = useState(() => draftFromCandidate(candidate));
  const [selectedKey, setSelectedKey] = useState(null);
  const [reflectionIndex, setReflectionIndex] = useState(null);
  const [analysis, setAnalysis] = useState(null);
  const [error, setError] = useState('');
  const [applying, setApplying] = useState(false);
  const [appliedSha, setAppliedSha] = useState('');
  const [applyErrors, setApplyErrors] = useState([]);

  useEffect(() => {
    setDraft(draftFromCandidate(candidate));
    setSelectedKey(null);
    setReflectionIndex(null);
    setAppliedSha('');
    setApplyErrors([]);
  }, [candidate]);

  // 结构化校验：本地改动后防抖 350ms 调用一次，避免每次按键都打服务端。
  useEffect(() => {
    if (!draft.nodes.length) {
      setAnalysis(null);
      return undefined;
    }
    const timer = setTimeout(async () => {
      try {
        const result = await plansApi.validateDraft({
          schemaVersion: draft.schemaVersion ?? 1,
          nodes: draft.nodes,
          revisionEdges: draft.revisionEdges,
          assumptions: draft.assumptions ?? [],
          openQuestions: draft.openQuestions ?? []
        });
        setAnalysis(result);
        setError('');
      } catch (failure) {
        setError(failure.message);
      }
    }, 350);
    return () => clearTimeout(timer);
  }, [draft]);

  const patchNode = useCallback((key, patch) => {
    setDraft(previous => ({
      ...previous,
      nodes: previous.nodes.map(node => (node.key === key ? { ...node, ...patch } : node))
    }));
  }, []);

  const addNode = role => {
    const index = draft.nodes.length + 1;
    const key = `${role === 'REVIEW' ? 'review' : role === 'ANALYZE' ? 'analyze' : 'implement'}-${index}`;
    setDraft(previous => ({
      ...previous,
      nodes: [
        ...previous.nodes,
        {
          key,
          title: t(`composer.newNodeTitle.${role}`),
          role,
          objective: '',
          dependsOn: previous.nodes.length ? [previous.nodes[previous.nodes.length - 1].key] : [],
          readFiles: [],
          writeFiles: [],
          expectedOutputs: [],
          acceptanceCriteria: []
        }
      ]
    }));
    setSelectedKey(key);
  };

  const removeNode = key => {
    setDraft(previous => ({
      ...previous,
      nodes: previous.nodes
        .filter(node => node.key !== key)
        .map(node => ({ ...node, dependsOn: node.dependsOn.filter(item => item !== key) })),
      revisionEdges: previous.revisionEdges.filter(
        edge => edge.fromReviewerNodeKey !== key && edge.toTargetNodeKey !== key
      )
    }));
    setSelectedKey(current => (current === key ? null : current));
  };

  const addReflectionEdge = (from, to) => {
    if (!from || !to || from === to) return;
    setDraft(previous => ({
      ...previous,
      revisionEdges: [
        ...previous.revisionEdges,
        { fromReviewerNodeKey: from, toTargetNodeKey: to, maxRevisions: 2 }
      ]
    }));
  };

  // 只有服务端实时校验通过才允许落库：按钮禁用状态本身就说明「这份图还不能应用」。
  const canApply =
    Boolean(onApply) && Boolean(analysis?.valid) && !applying && draft.nodes.length > 0;

  const applyDraft = async () => {
    if (!canApply) return;
    setApplying(true);
    setApplyErrors([]);
    setAppliedSha('');
    try {
      const result = await onApply({
        schemaVersion: draft.schemaVersion ?? 1,
        nodes: draft.nodes,
        revisionEdges: draft.revisionEdges,
        assumptions: draft.assumptions ?? [],
        openQuestions: draft.openQuestions ?? []
      });
      if (result?.applied) setAppliedSha(result.candidate?.candidateSha256 ?? '');
      else setApplyErrors(result?.errors ?? []);
    } catch (failure) {
      setApplyErrors([failure.message]);
    } finally {
      setApplying(false);
    }
  };

  const selected = draft.nodes.find(node => node.key === selectedKey) ?? null;
  const reflection = reflectionIndex == null ? null : draft.revisionEdges[reflectionIndex];

  return (
    <Box data-testid="plan-composer">
      <Alert severity="info" sx={{ mb: 1.25 }}>
        {t('composer.boundary')}
      </Alert>

      <Stack direction="row" spacing={0.75} sx={{ mb: 1, flexWrap: 'wrap', rowGap: 0.5 }}>
        <Button size="small" startIcon={<AddRounded />} onClick={() => addNode('ANALYZE')}>
          {t('composer.addAnalyze')}
        </Button>
        <Button size="small" startIcon={<AddRounded />} onClick={() => addNode('IMPLEMENT')}>
          {t('composer.addImplement')}
        </Button>
        <Button size="small" startIcon={<AddRounded />} onClick={() => addNode('REVIEW')}>
          {t('composer.addReview')}
        </Button>
        <Box sx={{ flex: 1 }} />
        {analysis && (
          <Chip
            size="small"
            data-testid="composer-verdict"
            label={
              analysis.valid
                ? t('composer.valid')
                : t('composer.invalid', { count: analysis.conflicts.length })
            }
            sx={{
              bgcolor: analysis.valid ? '#E6F4EA' : '#FCE8E6',
              color: analysis.valid ? '#137333' : '#C5221F',
              fontWeight: 600
            }}
          />
        )}
        {onApply && (
          <Button
            size="small"
            variant="contained"
            data-testid="composer-apply"
            disabled={!canApply}
            loading={applying}
            startIcon={<CloudUploadRounded />}
            onClick={applyDraft}
          >
            {t('composer.apply')}
          </Button>
        )}
      </Stack>

      {error && (
        <Alert severity="warning" sx={{ mb: 1 }}>
          {t('composer.validateFailed', { message: error })}
        </Alert>
      )}

      {appliedSha && (
        <Alert severity="success" sx={{ mb: 1 }} data-testid="composer-applied">
          {t('composer.applied', { sha: appliedSha.slice(0, 12) })}
        </Alert>
      )}

      {applyErrors.length > 0 && (
        <Alert severity="error" sx={{ mb: 1 }} data-testid="composer-apply-errors">
          <Typography variant="subtitle2">{t('composer.applyRejected')}</Typography>
          {applyErrors.map(reason => (
            <Typography variant="body2" key={reason}>
              · {reason}
            </Typography>
          ))}
        </Alert>
      )}

      <Canvas
        draft={draft}
        analysis={analysis}
        selectedKey={selectedKey}
        onSelectNode={setSelectedKey}
        onSelectReflection={setReflectionIndex}
      />

      {analysis && analysis.conflicts.length > 0 && (
        <Stack spacing={0.5} sx={{ mt: 1 }} data-testid="composer-conflicts">
          {analysis.conflicts.map((conflict, index) => (
            <Typography key={`${conflict.code}:${index}`} variant="caption" color="error">
              {CONFLICT_LABEL[conflict.code] ? t(CONFLICT_LABEL[conflict.code]) : conflict.code}
              {' · '}
              {conflict.nodeKeys.join(' / ')}
              {conflict.paths.length ? ` · ${conflict.paths.join(', ')}` : ''}
            </Typography>
          ))}
        </Stack>
      )}

      {selected && (
        <Box
          sx={{
            mt: 1.25,
            p: 1.25,
            borderRadius: 2.5,
            border: `1px solid ${googleTokens.outline}`,
            bgcolor: '#FFFFFF'
          }}
          data-testid="composer-node-panel"
        >
          <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', mb: 1 }}>
            <Typography sx={{ fontSize: 13, fontWeight: 700 }}>
              {t('composer.nodePanel', { key: selected.key })}
            </Typography>
            <Box sx={{ flex: 1 }} />
            <Button
              size="small"
              color="error"
              startIcon={<DeleteOutlineRounded />}
              onClick={() => removeNode(selected.key)}
            >
              {t('composer.removeNode')}
            </Button>
          </Stack>

          <Stack spacing={1}>
            <TextField
              size="small"
              label={t('composer.title')}
              value={selected.title}
              onChange={event => patchNode(selected.key, { title: event.target.value })}
            />
            <TextField
              size="small"
              select
              label={t('composer.role')}
              value={selected.role}
              onChange={event => patchNode(selected.key, { role: event.target.value })}
            >
              {ROLE_OPTIONS.map(role => (
                <MenuItem key={role} value={role}>
                  {t(`composer.roleName.${role}`)}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              size="small"
              multiline
              minRows={2}
              label={t('composer.writeFiles')}
              helperText={t('composer.pathsHint')}
              value={selected.writeFiles.join('\n')}
              onChange={event =>
                patchNode(selected.key, {
                  writeFiles: event.target.value
                    .split('\n')
                    .map(item => item.trim())
                    .filter(Boolean)
                })
              }
            />
            <TextField
              size="small"
              multiline
              minRows={2}
              label={t('composer.readFiles')}
              value={selected.readFiles.join('\n')}
              onChange={event =>
                patchNode(selected.key, {
                  readFiles: event.target.value
                    .split('\n')
                    .map(item => item.trim())
                    .filter(Boolean)
                })
              }
            />
            <TextField
              size="small"
              select
              label={t('composer.dependsOn')}
              value={selected.dependsOn}
              slotProps={{
                select: {
                  multiple: true
                }
              }}
              onChange={event => {
                const value = event.target.value;
                patchNode(selected.key, {
                  dependsOn: Array.isArray(value) ? value : [value]
                });
              }}
            >
              {draft.nodes
                .filter(node => node.key !== selected.key)
                .map(node => (
                  <MenuItem key={node.key} value={node.key}>
                    {node.title || node.key}
                  </MenuItem>
                ))}
            </TextField>

            <Divider />
            <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <TextField
                size="small"
                select
                label={t('composer.reflectionTarget')}
                value={selected.dependsOn[0] ?? ''}
                onChange={event => addReflectionEdge(selected.key, event.target.value)}
                sx={{ minWidth: 180 }}
              >
                {draft.nodes
                  .filter(node => node.key !== selected.key)
                  .map(node => (
                    <MenuItem key={node.key} value={node.key}>
                      {node.title || node.key}
                    </MenuItem>
                  ))}
              </TextField>
              <Typography variant="caption" color="text.secondary">
                {t('composer.reflectionHint')}
              </Typography>
            </Stack>

            {reflection && (
              <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
                <Typography variant="caption">
                  {t('composer.reflectionPanel', {
                    from: reflection.fromReviewerNodeKey,
                    to: reflection.toTargetNodeKey
                  })}
                </Typography>
                <TextField
                  size="small"
                  type="number"
                  label={t('composer.maxRevisions')}
                  value={reflection.maxRevisions}
                  onChange={event => {
                    const value = Math.max(1, Math.min(5, Number(event.target.value) || 1));
                    setDraft(previous => ({
                      ...previous,
                      revisionEdges: previous.revisionEdges.map((edge, index) =>
                        index === reflectionIndex ? { ...edge, maxRevisions: value } : edge
                      )
                    }));
                  }}
                  sx={{ width: 120 }}
                  slotProps={{ htmlInput: { min: 1, max: 5 } }}
                />
              </Stack>
            )}
          </Stack>
        </Box>
      )}
    </Box>
  );
}

/** 默认导出自带 Provider：调用方不需要关心 React Flow 的上下文要求。 */
export default function PlanComposerCanvas(props) {
  return (
    <ReactFlowProvider>
      <PlanComposerCanvasInner {...props} />
    </ReactFlowProvider>
  );
}
