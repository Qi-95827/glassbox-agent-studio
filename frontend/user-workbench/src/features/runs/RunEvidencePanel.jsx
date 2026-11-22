import { Alert, Box, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { runsApi } from '../../api/runs';
import { modelConnectionsApi } from '../../api/modelConnections';
import { TraceSection } from '../../components/WorkbenchDialog';
import StatusChip from '../../components/StatusChip';
import { modelLabel, skillNameLabel, toolNameLabel } from '../../constants/labels';
import { duration, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

const categories = {
  PROJECT_CONVENTION: '项目约定',
  PROJECT_BACKGROUND: '项目背景',
  PRECAUTION: '注意事项'
};

/** 运行证据：Agent 统计、装配技能、工具授权、会话与结构化交接。 */
export default function RunEvidencePanel({ runId, detail }) {
  const { t, locale } = useI18n();
  const evidence = useQuery({
    queryKey: ['run-evidence', runId],
    queryFn: async () => {
      const [metrics, skills, memoryLoad, grants, sessions, estimate] = await Promise.all([
        runsApi.agentMetrics(runId),
        runsApi.skills(runId),
        runsApi.memoryLoad(runId),
        runsApi.toolGrants(runId),
        runsApi.agentSessions(runId),
        // 历史运行或没有自带 Key 时估算不可用，不应拖垮其余证据读取。
        modelConnectionsApi.estimateRun(runId).catch(() => null)
      ]);
      return { metrics, skills, memoryLoad, grants, sessions, estimate };
    },
    enabled: Boolean(runId)
  });

  const data = evidence.data;
  const metrics = data?.metrics ?? [];
  const skills = data?.skills ?? [];
  const grants = data?.grants ?? [];
  const sessions = data?.sessions ?? [];
  const memory = data?.memoryLoad;
  const estimate = data?.estimate;
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
      <Typography sx={{ fontSize: 15, fontWeight: 500 }}>{t('evidence.title')}</Typography>
      <Typography variant="caption" color="text.secondary">
        {t('evidence.subtitle')}
      </Typography>

      {evidence.isError && (
        <Alert severity="warning" sx={{ mt: 1 }}>
          {t('evidence.partialFailure', { message: evidence.error.message })}
        </Alert>
      )}

      <Stack spacing={0.5} sx={{ mt: 1.2 }}>
        <TraceSection
          title={t('evidence.agentMetrics')}
          subtitle={t('evidence.agentInstances', { count: metrics.length })}
        >
          {metrics.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              {t('evidence.noAgentMetrics')}
            </Typography>
          ) : (
            <Stack spacing={0.9}>
              {metrics.map(metric => (
                <Box key={metric.agentId}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {metric.roleName} · 实例 #{shortId(metric.agentId)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                    {t('evidence.metricLine', {
                      duration: duration(metric.durationMs),
                      models: metric.modelCalls,
                      tools: metric.toolCalls,
                      known: metric.knownInputTokens ?? t('list.notRecorded'),
                      unknown: metric.unknownInputCount
                    })}
                  </Typography>
                  {metric.toolNames?.length > 0 && (
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                      {t('evidence.tools', {
                        tools: metric.toolNames.join(locale === 'en-US' ? ', ' : '、')
                      })}
                    </Typography>
                  )}
                </Box>
              ))}
            </Stack>
          )}
        </TraceSection>

        <TraceSection
          title={t('evidence.costEstimate')}
          subtitle={
            estimate?.byok
              ? t('evidence.byokModel', {
                  name: estimate.connectionName || t('labels.model.user'),
                  label: estimate.label
                })
              : t('evidence.noByok')
          }
        >
          {estimate?.byok ? (
            <Stack spacing={0.35}>
              <Typography variant="caption" color="text.secondary">
                {t('evidence.modelTokens', {
                  model: estimate.modelName,
                  input: estimate.inputTokens,
                  output: estimate.outputTokens
                })}
                {estimate.unknownTokenCalls
                  ? t('evidence.unknownUsage', { count: estimate.unknownTokenCalls })
                  : ''}
              </Typography>
              {estimate.priced ? (
                <Typography variant="body2">
                  {t('evidence.referenceCost', {
                    cost: estimate.estimatedCost,
                    currency: estimate.currency,
                    inputPrice: estimate.inputPricePer1m ?? 0,
                    outputPrice: estimate.outputPricePer1m ?? 0
                  })}
                </Typography>
              ) : (
                <Typography variant="body2" color="text.secondary">
                  {t('evidence.noPrice')}
                </Typography>
              )}
              <Typography variant="caption" color="text.secondary">
                费用由你的模型服务商结算，这里只用于本机预算提示。
              </Typography>
            </Stack>
          ) : (
            <Typography variant="body2" color="text.secondary">
              此运行使用内置模型或历史记录，不产生自带 Key 参考估算。
            </Typography>
          )}
        </TraceSection>

        <TraceSection title={t('evidence.skillsTitle')} subtitle={t('evidence.skillsSubtitle')}>
          {skills.length === 0 && grants.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              {t('evidence.noSkills')}
            </Typography>
          ) : (
            <Stack spacing={0.8}>
              {skills.map(item => (
                <Typography key={`${item.agentId}:${item.skillId}`} variant="body2">
                  {skillNameLabel(item.skillId, locale)} v{item.version} ·{' '}
                  {t('evidence.instanceId', { id: shortId(item.agentId) })}
                </Typography>
              ))}
              {grants.map(grant => (
                <Typography key={grant.agentId} variant="body2">
                  {t('evidence.registeredTools', {
                    id: shortId(grant.agentId),
                    tools:
                      grant.toolVersions
                        ?.map(
                          item =>
                            `${toolNameLabel(item.tool, locale)} ${
                              item.version ?? t('evidence.unknownVersion')
                            }`
                        )
                        .join(locale === 'en-US' ? ', ' : '、') || t('common.none')
                  })}
                </Typography>
              ))}
            </Stack>
          )}
        </TraceSection>

        <TraceSection
          title={t('evidence.memoryTitle')}
          subtitle={memory?.recorded ? t('evidence.memorySubtitle') : t('list.notRecorded')}
        >
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
              {t('evidence.noMemory')}
            </Typography>
          )}
        </TraceSection>

        <TraceSection
          title={t('evidence.sessionsTitle')}
          subtitle={t('evidence.rows', { count: sessions.length })}
        >
          {sessions.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              {t('evidence.noSessions')}
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

        <TraceSection
          title={t('glassbox.handoff')}
          subtitle={t('evidence.rows', { count: handoffs.length })}
        >
          {handoffs.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              {t('evidence.noHandoffs')}
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
                  {t('evidence.handoffMeta', {
                    goal: handoff.PAYLOAD?.goal,
                    status: handoff.PAYLOAD?.status
                  })}
                </Typography>
              </Box>
            ))
          )}
        </TraceSection>

        <TraceSection
          title={t('evidence.callsTitle')}
          subtitle={t('evidence.callsSubtitle', {
            models: detail?.models?.length ?? 0,
            tools: detail?.tools?.length ?? 0
          })}
        >
          <Stack spacing={0.4}>
            {(detail?.models ?? []).map(call => (
              <Typography key={call.ID} variant="caption" color="text.secondary">
                {t('evidence.modelLine', {
                  model:
                    call.MODEL_NAME === 'mock'
                      ? modelLabel('mock', undefined, locale)
                      : call.MODEL_NAME,
                  status: call.STATUS,
                  duration: duration(call.DURATION_MS),
                  input: call.INPUT_TOKENS ?? t('common.unknown'),
                  output: call.OUTPUT_TOKENS ?? t('common.unknown')
                })}
              </Typography>
            ))}
            {(detail?.tools ?? []).map(call => (
              <Typography key={call.ID} variant="caption" color="text.secondary">
                {t('evidence.toolLine', {
                  tool: toolNameLabel(call.TOOL_NAME, locale),
                  status: call.STATUS
                })}{' '}
                · {duration(call.DURATION_MS)}
              </Typography>
            ))}
            {(detail?.models ?? []).length === 0 && (detail?.tools ?? []).length === 0 && (
              <Typography variant="body2" color="text.secondary">
                {t('evidence.noCalls')}
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
