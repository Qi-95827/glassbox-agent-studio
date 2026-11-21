import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography
} from '@mui/material';
import OpenInNewRounded from '@mui/icons-material/OpenInNewRounded';
import StatusChip from '../../components/StatusChip';
import { PLAN_ROLES, STOP_CAUSES, nodeTitle } from '../../constants/labels';
import { dateTime, duration, nodeDuration, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import PlanAdjustmentEditor from '../workflows/PlanAdjustmentEditor';
import RunExtensionsPanel from '../workflows/RunExtensionsPanel';
import BugEvidencePanel from '../bug/BugEvidencePanel';
import RunEvidencePanel from './RunEvidencePanel';

function Section({ title, subtitle, children, action }) {
  return (
    <Box
      sx={{
        bgcolor: '#FFFFFF',
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 4,
        p: { xs: 2, sm: 2.5 }
      }}
    >
      <Stack
        direction="row"

        spacing={1.5}
        sx={{ mb: 1.5, alignItems: 'baseline', justifyContent: 'space-between' }}
      >
        <Box>
          <Typography sx={{ fontSize: 15, fontWeight: 500 }}>{title}</Typography>
          {subtitle && (
            <Typography variant="caption" color="text.secondary">
              {subtitle}
            </Typography>
          )}
        </Box>
        {action}
      </Stack>
      {children}
    </Box>
  );
}

/** 列表视图：节点表 + 计划确认、预算、阻塞、恢复与运行扩展证据。 */
export default function RunListView({
  detail,
  view,
  onAction,
  onSelectNode,
  onRecover,
  onSwitchRun,
  onSubmitSupplement,
  onArchiveTask
}) {
  const [path, setPath] = useState('tests/test_calc.py');
  const [content, setContent] = useState('');
  const [sending, setSending] = useState(false);
  const nodes = view?.nodes ?? [];
  const byId = new Map(nodes.map(node => [node.id, node]));
  const plan = view?.plan;
  const approveAction = (view?.availableActions ?? []).find(
    action => action.actionCode === 'APPROVE_PLAN'
  );
  const waitingPlan = plan?.reviewRequired && !plan?.approvedAt && view?.runStatus === 'PENDING';
  const budget = view?.budget;
  const counts = view?.counts;
  const recovery = view?.recovery;
  const support = view?.recoverySupport;
  const supplement = view?.supplement;
  const handover = view?.handover;
  const run = detail?.run;
  const task = detail?.task;
  const checkpoints = [...(detail?.workflowCheckpoints ?? []), ...(detail?.checkpoints ?? [])];

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Stack spacing={2} sx={{ maxWidth: 1180, mx: 'auto', px: { xs: 2, sm: 3 }, py: 2.5 }}>
        {view?.reason && (
          <Alert severity="warning">
            {view.reason}
            {view.stopCause && ` · 停止原因：${STOP_CAUSES[view.stopCause] || view.stopCause}`}
          </Alert>
        )}
        {(view?.blocking ?? []).map((item, index) => (
          <Alert key={`${item.code}:${index}`} severity="warning">
            {item.message}
            {item.blockingNodeIds?.length > 0 && (
              <Typography variant="caption" sx={{ display: 'block', mt: 0.5 }}>
                未启动下游：
                {item.blockingNodeIds
                  .map(id => (byId.get(id) ? nodeTitle(byId.get(id)) : shortId(id)))
                  .join('、')}
              </Typography>
            )}
          </Alert>
        ))}

        {waitingPlan && (
          <Alert
            severity="info"
            action={
              approveAction?.enabled ? (
                <Button size="small" variant="contained" onClick={() => onAction(approveAction)}>
                  确认并运行
                </Button>
              ) : undefined
            }
          >
            计划已保存，等待确认后执行。确认后运行会按节点级授权开始。
          </Alert>
        )}

        <Section
          title="执行节点"
          subtitle={
            plan
              ? `计划 v${plan.version} · ${plan.templateId} v${plan.templateVersion} · 最多 ${plan.maxAttempts} 次尝试`
              : '历史运行没有计划版本'
          }
          action={
            <Typography variant="caption" color="text.secondary">
              完成 {counts?.completed ?? 0} / {counts?.total ?? nodes.length} · 模型{' '}
              {counts?.modelCalls ?? 0} 次 · 工具 {counts?.toolCalls ?? 0} 次
            </Typography>
          }
        >
          <TableContainer>
            <Table size="small" aria-label="执行节点列表">
              <TableHead>
                <TableRow>
                  <TableCell>节点</TableCell>
                  <TableCell>角色</TableCell>
                  <TableCell>状态</TableCell>
                  <TableCell>耗时</TableCell>
                  <TableCell>尝试</TableCell>
                  <TableCell>执行定义</TableCell>
                  <TableCell>前置依赖</TableCell>
                  <TableCell align="right">详情</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {nodes.map(node => (
                  <TableRow key={node.id} hover>
                    <TableCell sx={{ fontWeight: 500 }}>{nodeTitle(node)}</TableCell>
                    <TableCell>{PLAN_ROLES[node.role] || node.role || '—'}</TableCell>
                    <TableCell>
                      <StatusChip value={node.status} />
                    </TableCell>
                    <TableCell>{duration(nodeDuration(node, view?.generatedAt))}</TableCell>
                    <TableCell>{node.attemptCount ?? 0}</TableCell>
                    <TableCell sx={{ fontFamily: googleTokens.mono, fontSize: 12 }}>
                      {node.definitionId
                        ? `${node.definitionId} v${node.definitionVersion ?? '—'}`
                        : node.agentName || '—'}
                    </TableCell>
                    <TableCell sx={{ maxWidth: 220 }}>
                      {(node.dependencyIds ?? [])
                        .map(id => (byId.get(id) ? nodeTitle(byId.get(id)) : shortId(id)))
                        .join('、') || '—'}
                    </TableCell>
                    <TableCell align="right">
                      <Button
                        size="small"
                        endIcon={<OpenInNewRounded fontSize="small" />}
                        onClick={() => onSelectNode(node.id)}
                      >
                        查看
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
                {nodes.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={8}>
                      <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
                        此运行没有计划节点。
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
        </Section>

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', lg: 'repeat(2, minmax(0,1fr))' },
            gap: 2,
            alignItems: 'start'
          }}
        >
          <Section title="运行预算与计数" subtitle="剩余额度来自服务端快照">
            <Stack spacing={0.6}>
              <Typography variant="body2">
                已用轮次 {budget?.usedRounds ?? run?.ROUNDS ?? '—'} · 剩余{' '}
                {budget?.remainingRounds ?? '未设上限'}
              </Typography>
              <Typography variant="body2">
                已用工具 {budget?.usedTools ?? run?.TOOLS ?? '—'} · 剩余{' '}
                {budget?.remainingTools ?? '未设上限'}
              </Typography>
              <Typography variant="body2">
                执行时间 {duration(view?.elapsedMs ?? run?.ELAPSED_MS)} · 剩余{' '}
                {budget?.remainingMs == null ? '未设上限' : duration(budget.remainingMs)}
              </Typography>
              <Typography variant="body2">
                待执行 {counts?.pending ?? 0} · 运行中 {counts?.running ?? 0} · 复用{' '}
                {counts?.reused ?? 0} · 检查 {counts?.checks ?? 0}
              </Typography>
              {counts?.unknownTokenCalls > 0 && (
                <Typography variant="caption" color="warning.dark">
                  未知 Token 用量 {counts.unknownTokenCalls} 次；未知不等于 0。
                </Typography>
              )}
            </Stack>
          </Section>

          <Section title="恢复与人工接手" subtitle="最终恢复条件由服务端再次校验">
            <Stack spacing={1}>
              {support && (
                <Typography
                  variant="body2"
                  color={support.supported ? 'text.primary' : 'warning.dark'}
                >
                  {support.supported ? '支持恢复' : '不支持自动恢复'}：{support.reason}
                </Typography>
              )}
              {recovery && (
                <Stack spacing={0.4}>
                  <Typography variant="caption" color="text.secondary">
                    父运行：
                    {recovery.parentRunId ? (
                      <Button size="small" onClick={() => onSwitchRun?.(recovery.parentRunId)}>
                        #{shortId(recovery.parentRunId)}
                      </Button>
                    ) : (
                      '未记录'
                    )}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    继承消耗：{recovery.inherited?.rounds ?? '未知'} 次模型 /{' '}
                    {recovery.inherited?.tools ?? '未知'} 次工具；本运行新增模型{' '}
                    {recovery.newModelCalls} 次、工具 {recovery.newToolCalls} 次
                  </Typography>
                </Stack>
              )}
              {handover && (
                <Alert severity={handover.revalidationRequired ? 'warning' : 'info'}>
                  人工接手 {handover.status === 'ACTIVE' ? '进行中' : '已交还'} · 接手于{' '}
                  {dateTime(handover.takenAt)}
                  {handover.revalidationRequired
                    ? '；代码已变化，旧测试与审批失效，需重新验证后再继续。'
                    : ''}
                </Alert>
              )}
              {checkpoints.length > 0 ? (
                <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
                  {checkpoints.map(checkpoint => {
                    const available = checkpoint.available ?? checkpoint.VALID;
                    const id = checkpoint.id ?? checkpoint.ID;
                    return (
                      <Button
                        key={id}
                        size="small"
                        variant="outlined"
                        disabled={!available}
                        onClick={() => onRecover(id)}
                      >
                        {checkpoint.completedNodes != null
                          ? `完成 ${checkpoint.completedNodes} 步后恢复`
                          : checkpoint.STAGE === 'BEFORE_REVIEW'
                            ? '分析后、审查前恢复'
                            : 'Agent 启动前恢复'}
                      </Button>
                    );
                  })}
                </Stack>
              ) : (
                <Typography variant="body2" color="text.secondary">
                  暂无可用检查点。
                </Typography>
              )}
            </Stack>
          </Section>
        </Box>

        {supplement && (
          <Section
            title={supplement.status === 'APPLIED' ? '补充已应用' : '等待补充信息'}
            subtitle={`任务 v${view.taskVersion} · ${supplement.nodeLabel || '未记录关联节点'}`}
          >
            <Typography variant="body2" sx={{ mb: 1.2 }}>
              {supplement.question}
            </Typography>
            {supplement.status === 'APPLIED' ? (
              <Typography variant="body2" color="text.secondary">
                已补充 {supplement.answerPath || '未记录路径'}
                ；运行从安全边界继续，首次失败的检查记录保留。
              </Typography>
            ) : (
              <Stack spacing={1}>
                <TextField
                  size="small"
                  label="测试文件路径"
                  value={path}
                  onChange={event => setPath(event.target.value)}
                  sx={{ maxWidth: 320 }}
                />

                <TextField
                  label="测试文件内容"
                  multiline
                  minRows={5}
                  value={content}
                  onChange={event => setContent(event.target.value)}
                  slotProps={{ htmlInput: { maxLength: 20000 } }}
                />

                <Button
                  variant="contained"
                  loading={sending}
                  sx={{ alignSelf: 'flex-start' }}
                  onClick={async () => {
                    setSending(true);
                    try {
                      await onSubmitSupplement?.(supplement.id, view.taskVersion, path, content);
                    } finally {
                      setSending(false);
                    }
                  }}
                >
                  提交补充并继续
                </Button>
                <Typography variant="caption" color="text.secondary">
                  按任务 v{view.taskVersion} 校验；路径必须是新的 tests/test_*.py 文件。
                </Typography>
              </Stack>
            )}
          </Section>
        )}

        <PlanAdjustmentEditor detail={detail} />

        <RunEvidencePanel runId={run?.ID} detail={detail} />

        {task?.BUG_ID && (
          <Section title="模拟 Bug 检测证据" subtitle="仅显示明确归属本次运行的结果">
            <BugEvidencePanel runId={run?.ID} />
          </Section>
        )}

        <Box
          sx={{
            bgcolor: '#FFFFFF',
            border: `1px solid ${googleTokens.outline}`,
            borderRadius: 4,
            p: { xs: 2, sm: 2.5 }
          }}
        >
          <RunExtensionsPanel detail={detail} />
        </Box>

        <Section
          title="运行快照"
          subtitle="数据库当前保留的记录，不代表完整恢复能力"
          action={
            <Button size="small" variant="outlined" onClick={onArchiveTask}>
              {task?.ARCHIVED_AT ? '取消归档' : '归档任务'}
            </Button>
          }
        >
          <Stack spacing={0.5}>
            <Typography variant="body2">
              事件 {detail?.events?.length ?? '按需读取'} · 检查点{' '}
              {detail?.checkpoints?.length ?? 0} · Agent {detail?.agents?.length ?? 0} · 工具{' '}
              {detail?.tools?.length ?? 0} · 模型 {detail?.models?.length ?? 0}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              运行 #{shortId(run?.ID)} · 任务 #{shortId(task?.ID)} · 创建于{' '}
              {dateTime(run?.CREATED_AT)}
            </Typography>
            <Chip
              size="small"
              label={`执行状态 ${run?.STATUS ?? '—'}`}
              sx={{ alignSelf: 'flex-start', bgcolor: '#F1F3F4', color: '#5F6368' }}
            />
          </Stack>
        </Section>
      </Stack>
    </Box>
  );
}
