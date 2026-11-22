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
  ListSubheader,
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
import { parseModelValue } from '../../api/modelConnections';
import { useModelConnections } from '../../hooks/useModelConnections';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

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
  const { t, locale } = useI18n();
  if (!node) {
    return (
      <Typography variant="body2" color="text.secondary">
        {t('plan.selectNode')}
      </Typography>
    );
  }
  const values = (list, empty = t('common.none')) =>
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
          {t('plan.key', { key: node.key })}
        </Typography>
      </Stack>
      {binding && (
        <Box sx={{ bgcolor: '#F8F9FA', borderRadius: 2, p: 1.25 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {binding.name || binding.definitionId} v
            {binding.definitionVersion ?? binding.definition_version ?? '—'}
          </Typography>
          <Typography variant="body2">{binding.reason || t('plan.noBindingReason')}</Typography>
          <Typography variant="caption" color="text.secondary">
            {t('plan.bindingLine', {
              model:
                binding.modelStrategy === 'FIXED'
                  ? t('plan.fixedModel', { mode: binding.modelMode })
                  : t('plan.inheritModel'),
              skills:
                (binding.skills || [])
                  .map(item => item.name)
                  .join(locale === 'en-US' ? ', ' : '、') || t('common.none'),
              tools:
                (binding.tools || [])
                  .map(item => item.name)
                  .join(locale === 'en-US' ? ', ' : '、') || t('common.none')
            })}
          </Typography>
        </Box>
      )}
      <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
        {node.objective}
      </Typography>
      <Box>
        <Typography variant="caption" color="text.secondary">
          {t('plan.dependsOn')}
        </Typography>
        {values(node.dependsOn)}
      </Box>
      <Box>
        <Typography variant="caption" color="text.secondary">
          {t('plan.readFiles')}
        </Typography>
        {values(node.readFiles)}
      </Box>
      <Box>
        <Typography variant="caption" color="text.secondary">
          {t('plan.writeFiles')}
        </Typography>
        {values(node.writeFiles)}
      </Box>
      <Box>
        <Typography variant="caption" color="text.secondary">
          {t('plan.acceptance')}
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
  const { t } = useI18n();
  const plan = usePlanCandidate(open);
  const [selected, setSelected] = useState(null);
  const [mode, setMode] = useState(input.agentMode || 'BUILTIN');
  const [manualId, setManualId] = useState(input.manualAgentId || null);
  const [candidateIds, setCandidateIds] = useState(input.candidateAgentIds || []);
  const [runModel, setRunModel] = useState(
    input.modelConnectionId ? `user:${input.modelConnectionId}` : 'mock'
  );
  const [planModel, setPlanModel] = useState(
    input.modelConnectionId ? `user:${input.modelConnectionId}` : ''
  );
  const [maxCost, setMaxCost] = useState(maxCostMicros ?? null);
  const [error, setError] = useState('');
  const draftKey = useRef(requestId());
  const agents = useQuery({ queryKey: ['my-agents'], queryFn: agentsApi.myAgents, enabled: open });
  const connections = useModelConnections(open);
  const connectionList = connections.data?.connections ?? [];
  const legacyDeepSeek = (connections.data?.builtIns ?? []).some(
    item => item.id === 'legacy-deepseek'
  );

  const effectiveInput = useMemo(
    () => ({
      ...input,
      agentMode: mode,
      manualAgentId: mode === 'MANUAL' ? manualId : null,
      manualAgentVersion: null,
      candidateAgentIds: mode === 'RECOMMEND' ? candidateIds : [],
      readOnly: Boolean(input.readOnly),
      locale: input.locale,
      writeEnabled: input.readOnly ? false : Boolean(input.writeEnabled),
      modelConnectionId: planModel === 'mock' ? null : parseModelValue(planModel).modelConnectionId
    }),
    [input, mode, manualId, candidateIds, planModel]
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
      const selection = parseModelValue(runModel);
      const created = await plan.createRun({
        expectedCandidateSha256: current.candidateSha256,
        expectedInputSha256: current.inputSha256,
        expectedInputVersion: current.inputVersion,
        modelMode: selection.modelMode,
        modelConnectionId: selection.modelConnectionId,
        maxCostMicros:
          selection.modelConnectionId || maxCost == null || maxCost === '' ? null : Number(maxCost)
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
      title={t('plan.title')}
      subtitle={t('plan.subtitle')}
    >
      <Stack spacing={1.6}>
        <Alert severity="info">{t('plan.info')}</Alert>

        <Box sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 3, p: 1.5 }}>
          <Typography variant="subtitle2" sx={{ mb: 0.75 }}>
            {t('plan.agentSelection')}
          </Typography>
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            sx={{ gap: 1, alignItems: { sm: 'center' } }}
          >
            <TextField
              select
              size="small"
              label={t('plan.selectionMode')}
              value={mode}
              disabled={busy}
              onChange={event => setMode(event.target.value)}
              sx={{ minWidth: 190 }}
            >
              <MenuItem value="BUILTIN">{t('plan.mode.BUILTIN')}</MenuItem>
              <MenuItem value="MANUAL">{t('plan.mode.MANUAL')}</MenuItem>
              <MenuItem value="RECOMMEND">{t('plan.mode.RECOMMEND')}</MenuItem>
            </TextField>
            <TextField
              select
              size="small"
              label={t('plan.planningModel')}
              value={planModel}
              onChange={event => setPlanModel(event.target.value)}
              sx={{ minWidth: 210 }}
            >
              <ListSubheader>{t('idea.builtInNoKey')}</ListSubheader>
              <MenuItem value="">{t('plan.serverPlanningModel')}</MenuItem>
              <ListSubheader>{t('composer.myModels')}</ListSubheader>
              {connectionList.map(connection => (
                <MenuItem key={connection.id} value={`user:${connection.id}`}>
                  {connection.displayName} · {connection.modelName}
                </MenuItem>
              ))}
            </TextField>
            {mode === 'MANUAL' && (
              <TextField
                select
                size="small"
                label={t('plan.privateAgent')}
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
              {t('plan.applySelection')}
            </Button>
          </Stack>
          {mode === 'RECOMMEND' && (
            <Stack sx={{ mt: 1 }} spacing={0.25}>
              <Typography variant="caption" color="text.secondary">
                {t('plan.recommendHint')}
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
                  {t('plan.noRecommendable')}
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
              {t('plan.readOnlyNote')}
            </Typography>
          )}
        </Box>

        {finalError && (
          <Alert severity="error">
            {finalError}
            <Typography variant="caption" sx={{ display: 'block', mt: 0.5 }}>
              {t('plan.agentErrorHint')}
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
              {t('plan.cancelGenerate')}
            </Button>
          )}
          <Button
            size="small"
            startIcon={<RefreshOutlined />}
            disabled={busy}
            onClick={() => generate(current?.id ?? null)}
          >
            {t('plan.regenerate')}
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
            <Typography variant="subtitle2">{t('plan.invalid')}</Typography>
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
                {selectedNode ? selectedNode.title : t('plan.nodeDetail')}
              </Typography>
              <NodeDetail node={selectedNode} binding={current?.agentBindings?.[selected]} />
            </Box>
            {selected && (
              <Button
                size="small"
                onClick={() => setSelected(null)}
                sx={{ alignSelf: 'flex-start' }}
              >
                {t('plan.backToGraph')}
              </Button>
            )}
            {current.candidate.assumptions?.length > 0 && (
              <Box>
                <Typography variant="caption" color="text.secondary">
                  {t('plan.assumptions')}
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
                <Typography variant="subtitle2">{t('plan.openQuestions')}</Typography>
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
                  <Typography>{t('plan.ready')}</Typography>
                  <Stack direction="row" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
                    <TextField
                      select
                      size="small"
                      label={t('plan.runModel')}
                      value={runModel}
                      onChange={event => setRunModel(event.target.value)}
                      sx={{ minWidth: 230 }}
                    >
                      <ListSubheader>{t('idea.builtInModels')}</ListSubheader>
                      <MenuItem value="mock">{t('composer.mockModel')}</MenuItem>
                      {legacyDeepSeek && (
                        <MenuItem value="deepseek">{t('idea.deepseekFile')}</MenuItem>
                      )}
                      <ListSubheader>{t('composer.myModels')}</ListSubheader>
                      {connectionList.map(connection => (
                        <MenuItem key={connection.id} value={`user:${connection.id}`}>
                          {connection.displayName} · {connection.modelName}
                        </MenuItem>
                      ))}
                    </TextField>
                    {!parseModelValue(runModel).modelConnectionId && (
                      <TextField
                        size="small"
                        type="number"
                        label={t('advanced.maxCost')}
                        value={maxCost ?? ''}
                        onChange={event =>
                          setMaxCost(event.target.value === '' ? null : Number(event.target.value))
                        }
                        slotProps={{ htmlInput: { min: 1, max: 1000000000 } }}
                        sx={{ minWidth: 210 }}
                      />
                    )}

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
                    固定模型节点使用发布时保存的模型；自带 Key
                    模型使用你自己的额度，平台费用硬预算不适用。
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
