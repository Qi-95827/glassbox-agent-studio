import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, Box, Button, Chip, CircularProgress, Divider, MenuItem, Stack, TextField, Typography
} from '@mui/material';
import RefreshOutlined from '@mui/icons-material/RefreshOutlined';
import StopCircleOutlined from '@mui/icons-material/StopCircleOutlined';
import WorkbenchDialog from '../../layout/WorkbenchDialog.jsx';
import RunTraceGraph from './RunTraceGraph.jsx';

const statusLabels = {
  GENERATING: ['生成中', 'info'],
  READY: ['可审阅草稿', 'success'],
  INVALID: ['校验未通过', 'warning'],
  FAILED: ['生成失败', 'error'],
  CANCELLED: ['已取消', 'default'],
  INTERRUPTED: ['已中断', 'warning'],
  STALE: ['已过期', 'warning'],
  CONSUMED: ['已用于运行', 'success']
};

const roleLabels = { ANALYZE: '分析', IMPLEMENT: '开发', REVIEW: '审查' };

function requestId() {
  return globalThis.crypto?.randomUUID?.().replace(/-/g, '') ?? `req${Date.now()}${Math.random().toString(16).slice(2)}`;
}

/** 候选节点映射到现有流程图组件；草稿节点没有执行状态，统一显示待执行。 */
function graphModel(candidate) {
  const nodes = (candidate?.nodes ?? []).map(node => ({
    id: node.key, key: node.key, label: node.title, status: 'PENDING', kind: 'AGENT', attemptCount: 0
  }));
  const edges = (candidate?.nodes ?? []).flatMap(node =>
    (node.dependsOn ?? []).map(dependency => ({ fromNodeId: dependency, toNodeId: node.key })));
  return { nodes, edges };
}

function NodeDetail({ node, binding }) {
  if (!node) return <Typography variant="body2" color="text.secondary">选择一个节点查看目标、范围和产出。</Typography>;
  const files = values => values?.length
    ? <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 0.5 }}>{values.map(path => <Chip size="small" key={path} label={path} />)}</Stack>
    : <Typography variant="body2" color="text.secondary">无</Typography>;
  const sourceLabels = { BUILTIN: '内置', MANUAL: '手动指定', RECOMMENDED: '规划器推荐' };
  return <Stack spacing={1.1}>
    <Stack direction="row" sx={{ gap: 0.75, alignItems: 'center', flexWrap: 'wrap' }}>
      <Typography variant="subtitle1">{node.title}</Typography>
      <Chip size="small" variant="outlined" label={roleLabels[node.role] ?? node.role} />
      <Typography variant="caption" color="text.secondary">键 {node.key}</Typography>
    </Stack>
    {binding && <Box sx={{ bgcolor: 'action.hover', borderRadius: 1.5, p: 1 }}>
      <Typography variant="body2" sx={{ fontWeight: 600 }}>
        {sourceLabels[binding.source] || binding.source || '未记录'} · {binding.name || binding.definitionId}
        {' '}v{binding.definitionVersion ?? binding.definition_version ?? '—'}
      </Typography>
      <Typography variant="body2">{binding.reason || '未记录选择理由'}</Typography>
      <Typography variant="caption" color="text.secondary">
        模型：{binding.modelStrategy === 'FIXED' ? `固定 ${binding.modelMode}` : '继承运行模型'}
        {'；'}Skill：{(binding.skills || []).map(item => `${item.name} v${item.version}`).join('、') || '无'}
        {'；'}工具：{(binding.tools || []).map(item => `${item.name} v${item.version}`).join('、') || '无'}
      </Typography>
    </Box>}
    <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{node.objective}</Typography>
    <Box>
      <Typography variant="caption" color="text.secondary">直接依赖</Typography>
      {files(node.dependsOn)}
    </Box>
    <Box><Typography variant="caption" color="text.secondary">读取范围</Typography>{files(node.readFiles)}</Box>
    <Box><Typography variant="caption" color="text.secondary">写入范围</Typography>{files(node.writeFiles)}</Box>
    <Box>
      <Typography variant="caption" color="text.secondary">预期产出</Typography>
      {(node.expectedOutputs ?? []).map((output, index) => <Typography variant="body2" key={`${output.kind}:${output.path ?? index}`}>
        {output.kind === 'FILE' ? `文件 ${output.path}` : '文本结论'}{output.required ? '（必需）' : '（可选）'}
      </Typography>)}
    </Box>
    <Box>
      <Typography variant="caption" color="text.secondary">节点验收条件</Typography>
      {(node.acceptanceCriteria ?? []).map(item => <Typography variant="body2" key={item}>· {item}</Typography>)}
    </Box>
  </Stack>;
}

/**
 * 候选计划草稿。只调用规划模型并保存候选，不创建任务、运行或工作区写入；
 * 用户在这里审阅图与文件范围，创建运行在任务 2 接通。
 */
export default function PlanCandidateDialog({ api, input, onClose, onRun, maxCostMicros }) {
  const [current, setCurrent] = useState(null);
  const [error, setError] = useState('');
  const [phase, setPhase] = useState('generate');
  const [selected, setSelected] = useState(null);
  const [runModel, setRunModel] = useState('mock');
  const [maxCost, setMaxCost] = useState(maxCostMicros ?? null);
  const [agents, setAgents] = useState([]);
  const [mode, setMode] = useState(input.agentMode || 'BUILTIN');
  const [manualId, setManualId] = useState(input.manualAgentId || null);
  const [candidateIds, setCandidateIds] = useState(input.candidateAgentIds || []);
  const disposed = useRef(false);
  // 同一草稿的每次重新生成共用一个分组；输入变化会让旧候选在服务端过期。
  const draftKey = useRef(requestId());

  useEffect(() => {
    api('/my/agents').then(setAgents).catch(() => setAgents([]));
  }, []);

  const effectiveInput = useMemo(() => ({
    ...input,
    agentMode: mode,
    manualAgentId: mode === 'MANUAL' ? manualId : null,
    manualAgentVersion: null,
    candidateAgentIds: mode === 'RECOMMEND' ? candidateIds : [],
    readOnly: Boolean(input.readOnly),
    writeEnabled: input.readOnly ? false : Boolean(input.writeEnabled)
  }), [input, mode, manualId, candidateIds]);

  const published = agents.filter(agent => agent.status === 'PUBLISHED' && agent.currentVersion > 0);

  async function generate(previousId) {
    setPhase('generate');
    setError('');
    try {
      const created = await api('/plan-candidates', {
        method: 'POST',
        body: JSON.stringify({
          requestId: requestId(), input: effectiveInput, previousCandidateId: previousId ?? null,
          draftKey: draftKey.current
        })
      });
      setCurrent(created);
      setSelected(created.candidate?.nodes?.[0]?.key ?? null);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setPhase('idle');
    }
  }

  useEffect(() => { generate(null); }, []);
  useEffect(() => () => { disposed.current = true; }, []);

  useEffect(() => {
    if (current?.status !== 'GENERATING') return undefined;
    const timer = setInterval(async () => {
      try {
        const next = await api(`/plan-candidates/${current.id}`);
        if (!disposed.current) {
          setCurrent(next);
          setSelected(previousSelected => previousSelected ?? next.candidate?.nodes?.[0]?.key ?? null);
        }
      } catch (failure) {
        if (!disposed.current) setError(failure.message);
      }
    }, 1200);
    return () => clearInterval(timer);
  }, [current?.status, current?.id]);

  async function cancel() {
    setPhase('cancel');
    try {
      setCurrent(await api(`/plan-candidates/${current.id}/cancel`, { method: 'POST' }));
    } catch (failure) {
      setError(failure.message);
    } finally {
      setPhase('idle');
    }
  }

  async function startRun() {
    setPhase('run');
    setError('');
    try {
      const created = await api(`/plan-candidates/${current.id}/runs`, {
        method: 'POST',
        body: JSON.stringify({
          expectedCandidateSha256: current.candidateSha256,
          expectedInputSha256: current.inputSha256,
          expectedInputVersion: current.inputVersion,
          modelMode: runModel,
          maxCostMicros: maxCost == null || maxCost === '' ? null : Number(maxCost)
        })
      });
      if (onRun) onRun(created.runId, created.workspaceId);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setPhase('idle');
    }
  }

  const graph = useMemo(() => graphModel(current?.candidate), [current?.candidate]);
  const [label, color] = statusLabels[current?.status] ?? ['等待', 'default'];
  const selectedNode = current?.candidate?.nodes?.find(node => node.key === selected) ?? null;

  return <WorkbenchDialog title="执行计划草稿" open width={880} footer={null} onCancel={onClose}>
    <Stack spacing={1.6}>
      <Alert severity="info">
        生成和校验阶段只保存候选，不创建任务、运行或写入授权；只有点击「确认并创建运行」才会创建任务、运行和节点级授权。生成会把已确认的目标、约束、验收条件和文件名发送给规划模型。
      </Alert>
      <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 2, p: 1.25 }}>
        <Typography variant="subtitle2" sx={{ mb: 0.75 }}>节点 Agent 选择</Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1, alignItems: { sm: 'center' }, flexWrap: 'wrap' }}>
          <TextField select size="small" label="选择方式" value={mode} disabled={phase === 'run'}
            onChange={event => setMode(event.target.value)} sx={{ minWidth: 180 }}>
            <MenuItem value="BUILTIN">沿用内置模板</MenuItem>
            <MenuItem value="MANUAL">指定私有 Agent 版本</MenuItem>
            <MenuItem value="RECOMMEND">允许规划器从勾选中推荐</MenuItem>
          </TextField>
          {mode === 'MANUAL' && <TextField select size="small" label="私有 Agent" value={manualId}
            onChange={event => setManualId(event.target.value)} sx={{ minWidth: 240 }}>
            {published.map(agent => <MenuItem key={agent.id} value={agent.id}>
              {agent.name} · v{agent.currentVersion} · {agent.permissionRole}
            </MenuItem>)}
          </TextField>}
          <Button size="small" variant="outlined" disabled={phase === 'generate' || current?.status === 'GENERATING'}
            onClick={() => generate(current?.id ?? null)}>应用选择并重新生成</Button>
        </Stack>
        {mode === 'RECOMMEND' && <Stack sx={{ mt: 1 }} spacing={0.25}>
          <Typography variant="caption" color="text.secondary">只列出已发布且允许推荐的私有 Agent；模型只能返回服务端生成的短引用。</Typography>
          {published.filter(agent => agent.recommendable).map(agent => <label key={agent.id}>
            <input type="checkbox" checked={candidateIds.includes(agent.id)}
              onChange={event => setCandidateIds(previous => event.target.checked
                ? [...previous, agent.id]
                : previous.filter(id => id !== agent.id))} />
            {' '}{agent.name} v{agent.currentVersion} · {agent.permissionRole}
          </label>)}
          {published.filter(agent => agent.recommendable).length === 0 &&
            <Typography variant="body2" color="text.secondary">还没有可推荐的已发布 Agent，请先在「我的 Agent」创建并发布。</Typography>}
        </Stack>}
        {input.readOnly && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>
          这是只读计划：不会获得写入工具，私有 Agent 只能进入分析或审查节点。
        </Typography>}
      </Box>
      {error && <Alert severity="error">
        <Typography>{error}</Typography>
        <Typography variant="caption">如果提示 Agent 失效、撤权或归档，请在上方重新选择后点击「应用选择并重新生成」再确认。</Typography>
      </Alert>}
      <Stack direction="row" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
        <Chip size="small" color={color} variant="outlined" label={label} />
        {current && <Typography variant="caption" color="text.secondary">
          版本 {current.inputVersion} · 输入摘要 {current.inputSha256.slice(0, 12)}…
          {current.candidateSha256 ? ` · 候选摘要 ${current.candidateSha256.slice(0, 12)}…` : ''}
        </Typography>}
        {current?.status === 'GENERATING' && <>
          <CircularProgress size={16} />
          <Button size="small" startIcon={<StopCircleOutlined />} loading={phase === 'cancel'} onClick={cancel}>取消生成</Button>
        </>}
        <Box sx={{ flex: 1 }} />
        <Button size="small" startIcon={<RefreshOutlined />} disabled={phase === 'generate' || current?.status === 'GENERATING'}
          onClick={() => generate(current?.id ?? null)}>重新生成</Button>
      </Stack>

      {current?.modelName && <Typography variant="caption" color="text.secondary">
        规划模型：{current.modelName}；Token 用量：{current.inputTokens == null ? '未知' : current.inputTokens} / {current.outputTokens == null ? '未知' : current.outputTokens}（未知不等于 0）。
      </Typography>}

      {current?.errors?.length > 0 && <Alert severity="warning">
        <Typography variant="subtitle2">校验未通过</Typography>
        {current.errors.map(reason => <Typography variant="body2" key={reason}>· {reason}</Typography>)}
      </Alert>}

      {current?.candidate && <>
        <Divider />
        <RunTraceGraph nodes={graph.nodes} edges={graph.edges} runId={current.id} selectedId={selected}
          selectedEdge={null} focusIds={new Set()} nodeDetail={null}
          onSelectNode={setSelected} onSelectEdge={() => {}} />
        <Box>
          <Typography component="h3" variant="subtitle1" sx={{ mb: 0.75 }}>
            {selectedNode ? selectedNode.title : '节点详情'}
          </Typography>
          <NodeDetail node={selectedNode} binding={current?.agentBindings?.[selected]} />
        </Box>
        {current.candidate.assumptions?.length > 0 && <Box>
          <Typography variant="caption" color="text.secondary">模型假设</Typography>
          {current.candidate.assumptions.map(item => <Typography variant="body2" key={item}>· {item}</Typography>)}
        </Box>}
        {current.candidate.openQuestions?.length > 0 && <Alert severity="info">
          <Typography variant="subtitle2">待确认事项</Typography>
          {current.candidate.openQuestions.map(item => <Typography variant="body2" key={item}>· {item}</Typography>)}
        </Alert>}
        {current.status === 'READY' && <Alert severity="success">
          <Stack spacing={1}>
            <Typography>候选已通过节点、依赖、文件和审查规则校验。确认后创建任务、运行和节点级授权；服务端会再次核对候选摘要、输入版本、工作区基线和文件版本。</Typography>
            <Box>
              <Typography variant="caption" color="text.secondary">每个节点确认后的实际模型</Typography>
              {(current.candidate?.nodes ?? []).map(node => {
                const binding = current.agentBindings?.[node.key];
                const model = binding?.modelStrategy === 'FIXED' ? `固定 ${binding.modelMode}` : '继承本次运行模型';
                return <Typography variant="body2" key={node.key}>{node.title}：{model}</Typography>;
              })}
            </Box>
            <Stack direction="row" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
              <TextField select size="small" label="运行模型" value={runModel} disabled={phase === 'run'}
                onChange={event => setRunModel(event.target.value)} sx={{ minWidth: 190 }}>
                <MenuItem value="mock">模拟模型</MenuItem>
                <MenuItem value="deepseek">DeepSeek Flash（真实调用）</MenuItem>
              </TextField>
              <TextField size="small" type="number" label="费用硬预算（微单位，留空不限额）"
                value={maxCost ?? ''} disabled={phase === 'run'} sx={{ minWidth: 210 }}
                inputProps={{ min: 1, max: 1_000_000_000 }}
                onChange={event => setMaxCost(event.target.value === '' ? null : Number(event.target.value))} />
              <Button variant="contained" loading={phase === 'run'} onClick={startRun}>确认并创建运行</Button>
            </Stack>
            <Typography variant="caption" color="text.secondary">
              固定模型节点使用发布时保存的模型，不受「运行模型」覆盖。混合模型计划按每次实际调用计价；启用硬预算时必须为所有实际模型配置同币种价格，缺价或跨币种会在确认时给出原因。费用硬预算会随确认请求提交；留空表示不限额。
            </Typography>
          </Stack>
        </Alert>}
        {current.runId && <Alert severity="info">
          该候选已用于运行 {current.runId}。{onRun && <Button size="small" onClick={() => onRun(current.runId)}>打开运行</Button>}
        </Alert>}
      </>}

      {!current && phase === 'generate' && <Stack direction="row" sx={{ gap: 1, alignItems: 'center' }}>
        <CircularProgress size={18} /><Typography variant="body2">正在保存候选并等待规划模型…</Typography>
      </Stack>}

      <Typography variant="caption" color="text.secondary">
        模型提出的只是建议：工具、根目录、模型凭据、检查命令和审批结果都由服务端决定；校验未通过的候选不能用于创建运行。
      </Typography>
    </Stack>
  </WorkbenchDialog>;
}
