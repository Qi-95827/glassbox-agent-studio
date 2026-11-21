import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  Divider,
  FormControlLabel,
  MenuItem,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import RefreshOutlined from '@mui/icons-material/RefreshOutlined';
import StopCircleOutlined from '@mui/icons-material/StopCircleOutlined';
import { useQuery } from '@tanstack/react-query';
import WorkbenchDialog from '../../components/WorkbenchDialog';
import StatusChip from '../../components/StatusChip';
import RunFlowGraph from '../workflows/RunFlowGraph';
import { usePlanCandidate } from '../../hooks/usePlanCandidate';
import { agentsApi } from '../../api/agents';
import { googleTokens } from '../../theme/googleTheme';

const roleLabels = { ANALYZE: '分析', IMPLEMENT: '开发', REVIEW: '审查' };

function requestId() {
  return (
    globalThis.crypto?.randomUUID?.().replace(/-/g, '') ??
    `req${Date.now()}${Math.random().toString(16).slice(2)}`
  );
}

function graphModel(candidate) {
  const nodes = (candidate?.nodes ?? []).map(node => ({
    id: node.key,
    key: node.key,
    label: node.title,
    status: 'PENDING',
    kind: 'AGENT',
    attemptCount: 0
  }));
  const edges = (candidate?.nodes ?? []).flatMap(node =>
    (node.dependsOn ?? []).map(dependency => ({ fromNodeId: dependency, toNodeId: node.key }))
  );
  return { nodes, edges };
}

function NodeDetail({ node, binding }) {
  if (!node) {
    return (
      <Typography variant="body2" color="text.secondary">
        选择一个节点查看目标、范围和产出。
      </Typography>
    );
  }
  const values = (list, empty = '无') =>
    list?.length ? (
      <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 0.5 }}>
        {list.map(value => (
          <Chip size="small" key={value} label={value} />
        ))}
      </Stack>
    ) : (
      <Typography variant="body2" color="text.secondary">
        {empty}
      </Typography>
    );

  return (
    <Stack spacing={1.1}>
      <Stack direction="row" sx={{ gap: 0.75, alignItems: 'center', flexWrap: 'wrap' }}>
        <Typography sx={{ fontSize: 15, fontWeight: 500 }}>{node.title}</Typography>
        <Chip size="small" label={roleLabels[node.role] ?? node.role} />
        <Typography variant="caption" color="text.secondary">
          键 {node.key}
        </Typography>
      </Stack>
      {binding && (
        <Box sx={{ bgcolor: '#F8F9FA', borderRadius: 2, p: 1.25 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {binding.name || binding.definitionId} v
            {binding.definitionVersion ?? binding.definition_version ?? '—'}
          </Typography>
          <Typography variant="body2">{binding.reason || '未记录选择理由'}</Typography>
          <Typography variant="caption" color="text.secondary">
            模型：
            {binding.modelStrategy === 'FIXED' ? `固定 ${binding.modelMode}` : '继承运行模型'}
            {'；'}Skill：{(binding.skills || []).map(item => item.name).join('、') || '无'}
            {'；'}工具：{(binding.tools || []).map(item => item.name).join('、') || '无'}
          </Typography>
        </Box>
      )}
      <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
        {node.objective}
      </Typography>
      <Box>
        <Typography variant="caption" color="text.secondary">
          直接依赖
        </Typography>
        {values(node.dependsOn)}
      </Box>
      <Box>
        <Typography variant="caption" color="text.secondary">
          读取范围
        </Typography>
        {values(node.readFiles)}
      </Box>
      <Box>
        <Typography variant="caption" color="text.secondary">
          写入范围
        </Typography>
        {values(node.writeFiles)}
      </Box>
      <Box>
        <Typography variant="caption" color="text.secondary">
          节点验收条件
        </Typography>
        {(node.acceptanceCriteria ?? []).map(item => (
          <Typography variant="body2" key={item}>
            · {item}
          </Typography>
        ))}
      </Box>
    </Stack>
  );
}

/**
 * 候选计划草稿：只保存候选，不创建任务、运行或写入授权。
 * 「确认并创建运行」会提交候选摘要、输入版本与预算，服务端再次校验。
 */
export default function PlanCandidateDialog({ open, input, onClose, onRun, maxCostMicros }) {
  const plan = usePlanCandidate(open);
  const [selected, setSelected] = useState(null);
  const [mode, setMode] = useState(input.agentMode || 'BUILTIN');
  const [manualId, setManualId] = useState(input.manualAgentId || null);
  const [candidateIds, setCandidateIds] = useState(input.candidateAgentIds || []);
  const [runModel, setRunModel] = useState('mock');
  const [maxCost, setMaxCost] = useState(maxCostMicros ?? null);
  const [error, setError] = useState('');
  const draftKey = useRef(requestId());
  const agents = useQuery({ queryKey: ['my-agents'], queryFn: agentsApi.myAgents, enabled: open });

  const effectiveInput = useMemo(
    () => ({
      ...input,
      agentMode: mode,
      manualAgentId: mode === 'MANUAL' ? manualId : null,
      manualAgentVersion: null,
      candidateAgentIds: mode === 'RECOMMEND' ? candidateIds : [],
      readOnly: Boolean(input.readOnly),
      writeEnabled: input.readOnly ? false : Boolean(input.writeEnabled)
    }),
    [input, mode, manualId, candidateIds]
  );

  const generate = previousId => {
    setError('');
    plan.generate({
      requestId: requestId(),
      input: effectiveInput,
      previousCandidateId: previousId ?? null,
      draftKey: draftKey.current
    });
  };

  useEffect(() => {
    if (!open) return;
    setSelected(null);
    generate(null);
    // 只在对话框打开时生成，后续输入变化由按钮显式触发。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const current = plan.current;
  const published = (agents.data ?? []).filter(
    agent => agent.status === 'PUBLISHED' && agent.currentVersion > 0
  );
  const graph = useMemo(() => graphModel(current?.candidate), [current?.candidate]);
  const selectedNode = current?.candidate?.nodes?.find(node => node.key === selected) ?? null;
  const busy = plan.isLoading || current?.status === 'GENERATING';
  const finalError = error || plan.error?.message;

  const startRun = async () => {
    setError('');
    try {
      const created = await plan.createRun({
        expectedCandidateSha256: current.candidateSha256,
        expectedInputSha256: current.inputSha256,
        expectedInputVersion: current.inputVersion,
        modelMode: runModel,
        maxCostMicros: maxCost == null || maxCost === '' ? null : Number(maxCost)
      });
      onRun?.(created.runId, created.workspaceId);
    } catch (failure) {
      setError(failure.message);
    }
  };

  return (
    <WorkbenchDialog
      open={open}
      onCancel={onClose}
      footer={null}
      width={880}
      title="执行计划草稿"
      subtitle="生成和校验阶段只保存候选，不创建任务、运行或写入授权。"
    >
      <Stack spacing={1.6}>
        <Alert severity="info">
          确认后会创建任务、运行和节点级授权。生成会把已确认的目标、约束、验收条件和文件名发送给规划模型。
        </Alert>

        <Box sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 3, p: 1.5 }}>
          <Typography variant="subtitle2" sx={{ mb: 0.75 }}>
            节点 Agent 选择
          </Typography>
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            sx={{ gap: 1, alignItems: { sm: 'center' } }}
          >
            <TextField
              select
              size="small"
              label="选择方式"
              value={mode}
              disabled={busy}
              onChange={event => setMode(event.target.value)}
              sx={{ minWidth: 190 }}
            >
              <MenuItem value="BUILTIN">沿用内置模板</MenuItem>
              <MenuItem value="MANUAL">指定私有 Agent 版本</MenuItem>
              <MenuItem value="RECOMMEND">允许规划器从勾选中推荐</MenuItem>
            </TextField>
            {mode === 'MANUAL' && (
              <TextField
                select
                size="small"
                label="私有 Agent"
                value={manualId ?? ''}
                onChange={event => setManualId(event.target.value)}
                sx={{ minWidth: 250 }}
              >
                {published.map(agent => (
                  <MenuItem key={agent.id} value={agent.id}>
                    {agent.name} · v{agent.currentVersion} · {agent.permissionRole}
                  </MenuItem>
                ))}
              </TextField>
            )}
            <Button
              size="small"
              variant="outlined"
              disabled={busy}
              onClick={() => generate(current?.id ?? null)}
            >
              应用选择并重新生成
            </Button>
          </Stack>
          {mode === 'RECOMMEND' && (
            <Stack sx={{ mt: 1 }} spacing={0.25}>
              <Typography variant="caption" color="text.secondary">
                只列出已发布且允许推荐的私有 Agent。
              </Typography>
              {published
                .filter(agent => agent.recommendable)
                .map(agent => (
                  <FormControlLabel
                    key={agent.id}
                    control={
                      <Checkbox
                        size="small"
                        checked={candidateIds.includes(agent.id)}
                        onChange={event =>
                          setCandidateIds(previous =>
                            event.target.checked
                              ? [...previous, agent.id]
                              : previous.filter(id => id !== agent.id)
                          )
                        }
                      />
                    }
                    label={
                      <Typography variant="body2">
                        {agent.name} v{agent.currentVersion} · {agent.permissionRole}
                      </Typography>
                    }
                  />
                ))}
              {published.filter(agent => agent.recommendable).length === 0 && (
                <Typography variant="body2" color="text.secondary">
                  还没有可推荐的已发布 Agent，请先在「我的 Agent」创建并发布。
                </Typography>
              )}
            </Stack>
          )}
          {input.readOnly && (
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block', mt: 0.75 }}
            >
              这是只读计划：不会获得写入工具，私有 Agent 只能进入分析或审查节点。
            </Typography>
          )}
        </Box>

        {finalError && (
          <Alert severity="error">
            {finalError}
            <Typography variant="caption" sx={{ display: 'block', mt: 0.5 }}>
              如果提示 Agent 失效、撤权或归档，请重新选择后点击「应用选择并重新生成」。
            </Typography>
          </Alert>
        )}

        <Stack direction="row" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
          <StatusChip value={current?.status ?? 'GENERATING'} />
          {current && (
            <Typography variant="caption" color="text.secondary">
              版本 {current.inputVersion} · 输入摘要 {current.inputSha256?.slice(0, 12)}…
              {current.candidateSha256 && ` · 候选摘要 ${current.candidateSha256.slice(0, 12)}…`}
            </Typography>
          )}
          {busy && <CircularProgress size={15} />}
          <Box sx={{ flex: 1 }} />
          {current?.status === 'GENERATING' && (
            <Button size="small" startIcon={<StopCircleOutlined />} onClick={() => plan.cancel()}>
              取消生成
            </Button>
          )}
          <Button
            size="small"
            startIcon={<RefreshOutlined />}
            disabled={busy}
            onClick={() => generate(current?.id ?? null)}
          >
            重新生成
          </Button>
        </Stack>

        {current?.modelName && (
          <Typography variant="caption" color="text.secondary">
            规划模型：{current.modelName}；Token 用量：
            {current.inputTokens == null ? '未知' : current.inputTokens} /{' '}
            {current.outputTokens == null ? '未知' : current.outputTokens}（未知不等于 0）。
          </Typography>
        )}

        {current?.errors?.length > 0 && (
          <Alert severity="warning">
            <Typography variant="subtitle2">校验未通过</Typography>
            {current.errors.map(reason => (
              <Typography variant="body2" key={reason}>
                · {reason}
              </Typography>
            ))}
          </Alert>
        )}

        {current?.candidate && (
          <>
            {!selected && (
              <Box
                sx={{
                  height: 320,
                  border: '1px solid',
                  borderColor: 'divider',
                  borderRadius: 3,
                  overflow: 'hidden',
                  bgcolor: googleTokens.canvas
                }}
              >
                <RunFlowGraph
                  nodes={graph.nodes}
                  edges={graph.edges}
                  selectedId={selected}
                  onSelectNode={setSelected}
                  onSelectEdge={edge => setSelected(edge.toNodeId)}
                />
              </Box>
            )}
            <Box>
              <Typography component="h3" sx={{ fontSize: 15, fontWeight: 500, mb: 0.75 }}>
                {selectedNode ? selectedNode.title : '节点详情'}
              </Typography>
              <NodeDetail node={selectedNode} binding={current?.agentBindings?.[selected]} />
            </Box>
            {selected && (
              <Button
                size="small"
                onClick={() => setSelected(null)}
                sx={{ alignSelf: 'flex-start' }}
              >
                返回计划的完整流程图
              </Button>
            )}
            {current.candidate.assumptions?.length > 0 && (
              <Box>
                <Typography variant="caption" color="text.secondary">
                  模型假设
                </Typography>
                {current.candidate.assumptions.map(item => (
                  <Typography variant="body2" key={item}>
                    · {item}
                  </Typography>
                ))}
              </Box>
            )}
            {current.candidate.openQuestions?.length > 0 && (
              <Alert severity="info">
                <Typography variant="subtitle2">待确认事项</Typography>
                {current.candidate.openQuestions.map(item => (
                  <Typography variant="body2" key={item}>
                    · {item}
                  </Typography>
                ))}
              </Alert>
            )}
            {current.status === 'READY' && (
              <Alert severity="success">
                <Stack spacing={1.1}>
                  <Typography>
                    候选已通过节点、依赖、文件和审查规则校验。确认后创建任务、运行和节点级授权。
                  </Typography>
                  <Stack direction="row" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
                    <TextField
                      select
                      size="small"
                      label="运行模型"
                      value={runModel}
                      onChange={event => setRunModel(event.target.value)}
                      sx={{ minWidth: 190 }}
                    >
                      <MenuItem value="mock">模拟模型</MenuItem>
                      <MenuItem value="deepseek">DeepSeek Flash（真实调用）</MenuItem>
                    </TextField>
                    <TextField
                      size="small"
                      type="number"
                      label="费用硬预算（微单位）"
                      value={maxCost ?? ''}
                      onChange={event =>
                        setMaxCost(event.target.value === '' ? null : Number(event.target.value))
                      }
                      slotProps={{ htmlInput: { min: 1, max: 1000000000 } }}
                      sx={{ minWidth: 210 }}
                    />

                    <Button
                      variant="contained"
                      loading={plan.isLoading}
                      disabled={busy}
                      onClick={startRun}
                    >
                      确认并创建运行
                    </Button>
                  </Stack>
                  <Typography variant="caption" color="text.secondary">
                    固定模型节点使用发布时保存的模型；费用硬预算留空表示不限额。
                  </Typography>
                </Stack>
              </Alert>
            )}
            {current.runId && (
              <Alert
                severity="info"
                action={
                  <Button size="small" onClick={() => onRun?.(current.runId)}>
                    打开运行
                  </Button>
                }
              >
                该候选已用于运行 {current.runId}。
              </Alert>
            )}
          </>
        )}

        {!current && busy && (
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <CircularProgress size={18} />
            <Typography variant="body2">正在保存候选并等待规划模型…</Typography>
          </Stack>
        )}

        <Divider />
        <Typography variant="caption" color="text.secondary">
          模型提出的只是建议：工具、根目录、模型凭据、检查命令和审批结果都由服务端决定；校验未通过的候选不能创建运行。
        </Typography>
      </Stack>
    </WorkbenchDialog>
  );
}
