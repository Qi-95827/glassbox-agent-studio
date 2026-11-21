import { Alert, Box, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { runsApi } from '../../api/runs';
import { TraceSection } from '../../components/WorkbenchDialog';
import StatusChip from '../../components/StatusChip';
import { SKILL_NAMES, TOOL_NAMES } from '../../constants/labels';
import { duration, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';

const categories = {
  PROJECT_CONVENTION: '项目约定',
  PROJECT_BACKGROUND: '项目背景',
  PRECAUTION: '注意事项'
};

/** 运行证据：Agent 统计、装配技能、工具授权、会话与结构化交接。 */
export default function RunEvidencePanel({ runId, detail }) {
  const evidence = useQuery({
    queryKey: ['run-evidence', runId],
    queryFn: async () => {
      const [metrics, skills, memoryLoad, grants, sessions] = await Promise.all([
        runsApi.agentMetrics(runId),
        runsApi.skills(runId),
        runsApi.memoryLoad(runId),
        runsApi.toolGrants(runId),
        runsApi.agentSessions(runId)
      ]);
      return { metrics, skills, memoryLoad, grants, sessions };
    },
    enabled: Boolean(runId)
  });

  const data = evidence.data;
  const metrics = data?.metrics ?? [];
  const skills = data?.skills ?? [];
  const grants = data?.grants ?? [];
  const sessions = data?.sessions ?? [];
  const memory = data?.memoryLoad;
  const handoffs = detail?.handoffs ?? [];

  return (
    <Box
      sx={{
        bgcolor: '#FFFFFF',
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 4,
        p: { xs: 2, sm: 2.5 }
      }}
    >
      <Typography sx={{ fontSize: 15, fontWeight: 500 }}>运行证据与观测</Typography>
      <Typography variant="caption" color="text.secondary">
        这些记录来自服务端持久化证据；缺少记录不代表能力未执行。
      </Typography>

      {evidence.isError && (
        <Alert severity="warning" sx={{ mt: 1 }}>
          部分运行证据暂时无法读取：{evidence.error.message}
        </Alert>
      )}

      <Stack spacing={0.5} sx={{ mt: 1.2 }}>
        <TraceSection title="Agent 运行统计" subtitle={`${metrics.length} 个实例`}>
          {metrics.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              暂无 Agent 调用统计。
            </Typography>
          ) : (
            <Stack spacing={0.9}>
              {metrics.map(metric => (
                <Box key={metric.agentId}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {metric.roleName} · 实例 #{shortId(metric.agentId)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                    墙钟 {duration(metric.durationMs)} · 模型 {metric.modelCalls} 次 · 工具{' '}
                    {metric.toolCalls} 次 · 已知输入 Token {metric.knownInputTokens ?? '无记录'} ·
                    未知 {metric.unknownInputCount} 次
                  </Typography>
                  {metric.toolNames?.length > 0 && (
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                      工具：{metric.toolNames.join('、')}
                    </Typography>
                  )}
                </Box>
              ))}
            </Stack>
          )}
        </TraceSection>

        <TraceSection title="装配技能与工具授权" subtitle="装配记录不等于实际调用">
          {skills.length === 0 && grants.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              暂无装配记录。
            </Typography>
          ) : (
            <Stack spacing={0.8}>
              {skills.map(item => (
                <Typography key={`${item.agentId}:${item.skillId}`} variant="body2">
                  {SKILL_NAMES[item.skillId] || item.skillId} v{item.version} · 实例 #
                  {shortId(item.agentId)}
                </Typography>
              ))}
              {grants.map(grant => (
                <Typography key={grant.agentId} variant="body2">
                  实例 #{shortId(grant.agentId)} 注册工具：
                  {grant.toolVersions
                    ?.map(
                      item => `${TOOL_NAMES[item.tool] || item.tool} ${item.version ?? '版本未知'}`
                    )
                    .join('、') || '无'}
                </Typography>
              ))}
            </Stack>
          )}
        </TraceSection>

        <TraceSection title="项目背景引用" subtitle={memory?.recorded ? '装配输入选择' : '未记录'}>
          {memory?.recorded ? (
            <Stack spacing={0.4}>
              <Typography variant="caption" color="text.secondary">
                声明 {memory.declaredCount} 条，现存 {memory.references?.length ?? 0} 条；
                {memory.integrity === 'INCONSISTENT' ? '数量或顺序不一致' : '完整性记录一致'}
              </Typography>
              {(memory.references ?? []).map(item => (
                <Typography key={item.memoryId} variant="body2">
                  #{shortId(item.memoryId)} · {categories[item.category] || item.category} · v
                  {item.version}
                </Typography>
              ))}
            </Stack>
          ) : (
            <Typography variant="body2" color="text.secondary">
              当前无装配记录；历史数据缺失不代表没有使用。
            </Typography>
          )}
        </TraceSection>

        <TraceSection title="Agent 会话" subtitle={`${sessions.length} 条`}>
          {sessions.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              暂无框架会话记录。
            </Typography>
          ) : (
            sessions.map(session => (
              <Typography key={session.agentId} variant="body2">
                实例 #{shortId(session.agentId)} ·{' '}
                {session.status === 'SAVED' ? `已保存 v${session.version ?? '—'}` : '尚无保存记录'}
              </Typography>
            ))
          )}
        </TraceSection>

        <TraceSection title="结构化交接" subtitle={`${handoffs.length} 条`}>
          {handoffs.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              此运行没有结构化交接记录。
            </Typography>
          ) : (
            handoffs.map(handoff => (
              <Box key={handoff.ID} sx={{ mb: 0.8 }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {handoff.DIRECTION === 'DELEGATION'
                    ? '协调者委派'
                    : handoff.DIRECTION === 'AGENT_TRANSFER'
                      ? 'Agent 间交接'
                      : 'Agent 返回结果'}{' '}
                  · {handoff.PAYLOAD?.sender} → {handoff.PAYLOAD?.receiver}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  目标：{handoff.PAYLOAD?.goal}；状态：{handoff.PAYLOAD?.status}
                </Typography>
              </Box>
            ))
          )}
        </TraceSection>

        <TraceSection
          title="模型与工具调用记录"
          subtitle={`${detail?.models?.length ?? 0} / ${detail?.tools?.length ?? 0} 条`}
        >
          <Stack spacing={0.4}>
            {(detail?.models ?? []).map(call => (
              <Typography key={call.ID} variant="caption" color="text.secondary">
                模型 {call.MODEL_NAME === 'mock' ? '模拟模型' : call.MODEL_NAME} · {call.STATUS} ·{' '}
                {duration(call.DURATION_MS)} · 输入 {call.INPUT_TOKENS ?? '未知'} / 输出{' '}
                {call.OUTPUT_TOKENS ?? '未知'}
              </Typography>
            ))}
            {(detail?.tools ?? []).map(call => (
              <Typography key={call.ID} variant="caption" color="text.secondary">
                工具 {TOOL_NAMES[call.TOOL_NAME] || call.TOOL_NAME} · {call.STATUS} ·{' '}
                {duration(call.DURATION_MS)}
              </Typography>
            ))}
            {(detail?.models ?? []).length === 0 && (detail?.tools ?? []).length === 0 && (
              <Typography variant="body2" color="text.secondary">
                暂无调用记录。
              </Typography>
            )}
          </Stack>
        </TraceSection>

        <TraceSection title="参与实例" subtitle={`${detail?.agents?.length ?? 0} 个`}>
          {(detail?.agents ?? []).map(agent => (
            <Stack
              key={agent.ID}
              direction="row"
              spacing={1}
              sx={{ alignItems: 'center', mb: 0.55 }}
            >
              <StatusChip value={agent.STATUS} />
              <Typography variant="body2">
                {agent.ROLE_NAME} · 实例 #{shortId(agent.ID)} · 定义{' '}
                {agent.DEFINITION_ID ?? '未记录'} v{agent.DEFINITION_VERSION ?? '—'}
              </Typography>
            </Stack>
          ))}
          {(detail?.agents ?? []).length === 0 && (
            <Typography variant="body2" color="text.secondary">
              此运行没有 Agent 实例记录。
            </Typography>
          )}
        </TraceSection>
      </Stack>
    </Box>
  );
}
