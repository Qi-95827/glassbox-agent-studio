import { Box, Chip, Stack, Typography } from '@mui/material';
import LockOutlined from '@mui/icons-material/LockOutlined';
import CheckCircleOutlineRounded from '@mui/icons-material/CheckCircleOutlineRounded';
import { TraceSection, Readout } from '../../../components/WorkbenchDialog';
import { modelLabel, toolNameLabel } from '../../../constants/labels';
import { googleTokens } from '../../../theme/googleTheme';
import { useI18n } from '../../../i18n/I18nProvider';

const FILTER_REASON_KEY = {
  NOT_DECLARED_BY_DEFINITION: 'assembly.reasonNotDeclared',
  NODE_NOT_WORKSPACE_READ: 'assembly.reasonNodeNotRead',
  NODE_NOT_WRITE_AUTHORIZED: 'assembly.reasonNodeNotWrite',
  RUN_NOT_WRITE_AUTHORIZED: 'assembly.reasonRunNotWrite',
  RUN_POLICY_FILTERED: 'assembly.reasonRunPolicy'
};

/**
 * 玻璃盒面板一：Agent 诞生配方（角色、Skill、工具权限漏斗与上下文来源）。
 *
 * <p>漏斗以服务端 {@code toolFunnel} 为唯一真相：已授予与被裁剪的工具都来自真实装配快照与 Agent 定义；
 * 服务端没有给出漏斗时只展示「无装配证据」，绝不再用硬编码工具清单反推裁剪结果。
 */
export default function AssemblyBlueprintPanel({ node, attempts, toolFunnel = null }) {
  const { t, locale } = useI18n();
  // attempts 按 attemptNo 升序返回；与后端 toolFunnel 口径保持一致，取最新一次带装配快照的尝试。
  const latest = [...(attempts ?? [])].reverse().find(item => item.assembly) ?? null;
  const components = latest?.assembly?.components ?? null;
  const grantedMap = components?.tools ?? {};
  const grantedKeys = Object.keys(grantedMap);
  const funnelGranted = toolFunnel?.granted ?? null;
  const filteredTools = toolFunnel?.filtered ?? null;
  const sources = components?.input?.sources ?? [];
  const memoryMatches = components?.memoryMatches ?? [];

  const filterReason = reason =>
    FILTER_REASON_KEY[reason] ? t(FILTER_REASON_KEY[reason]) : reason || t('assembly.notAssembled');

  return (
    <Stack spacing={1.5}>
      <Box
        sx={{
          p: 1.5,
          borderRadius: 3,
          bgcolor: '#F8F9FA',
          border: `1px solid ${googleTokens.outline}`
        }}
      >
        <Typography variant="subtitle2" sx={{ mb: 0.75 }}>
          {t('assembly.identity')}
        </Typography>
        <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap', rowGap: 0.75 }}>
          <Chip
            size="small"
            label={t('assembly.definition', { name: node?.definitionId || t('assembly.builtIn') })}
          />
          {node?.definitionVersion != null && (
            <Chip size="small" label={t('assembly.version', { version: node.definitionVersion })} />
          )}
          {components?.model?.name && (
            <Chip
              size="small"
              sx={{ bgcolor: googleTokens.infoBg, color: googleTokens.infoFg }}
              label={t('assembly.model', {
                model: modelLabel(components.model.mode || components.model.name, undefined, locale)
              })}
            />
          )}
          <Chip
            size="small"
            label={t('assembly.skills', {
              skills:
                (node?.agentSkills ?? []).map(s => s.name).join(locale === 'en-US' ? ', ' : '、') ||
                t('common.none')
            })}
          />
        </Stack>
        {node?.agentReason && (
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>
            {t('assembly.reason', { reason: node.agentReason })}
          </Typography>
        )}
      </Box>

      <Box
        sx={{
          p: 1.5,
          borderRadius: 3,
          bgcolor: '#FFFFFF',
          border: `1px solid ${googleTokens.outline}`
        }}
      >
        <Typography variant="subtitle2" sx={{ mb: 1 }}>
          {t('assembly.funnel')}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
          {t('assembly.granted', {
            count: funnelGranted ? funnelGranted.length : grantedKeys.length
          })}
        </Typography>
        <Stack direction="row" spacing={0.6} sx={{ flexWrap: 'wrap', rowGap: 0.6, mb: 1.25 }}>
          {(funnelGranted ?? grantedKeys.map(key => ({ kind: key, version: grantedMap[key] })))
            .length === 0 ? (
            <Typography variant="caption" color="text.secondary">
              {t('assembly.noTools')}
            </Typography>
          ) : (
            (
              funnelGranted ?? grantedKeys.map(key => ({ kind: key, version: grantedMap[key] }))
            ).map(tool => (
              <Chip
                key={tool.kind}
                size="small"
                icon={<CheckCircleOutlineRounded sx={{ fontSize: 14 }} />}
                label={`${toolNameLabel(tool.kind, locale)} v${tool.version}`}
                sx={{ bgcolor: googleTokens.successBg, color: googleTokens.successFg }}
              />
            ))
          )}
        </Stack>

        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
          {t('assembly.filtered', { count: filteredTools ? filteredTools.length : 0 })}
        </Typography>
        {!filteredTools ? (
          <Typography
            variant="caption"
            color="text.secondary"
            data-testid="assembly-filtered-unknown"
          >
            {t('assembly.filteredUnknown')}
          </Typography>
        ) : (
          <Stack spacing={0.6}>
            {filteredTools.map(tool => (
              <Stack
                key={tool.kind}
                data-testid={`assembly-filtered-${tool.kind}`}
                direction="row"
                spacing={0.75}
                sx={{
                  alignItems: 'center',
                  px: 1,
                  py: 0.55,
                  borderRadius: 2,
                  bgcolor: '#FCE8E6',
                  color: '#C5221F'
                }}
              >
                <LockOutlined sx={{ fontSize: 14, flexShrink: 0 }} />
                <Typography variant="caption" sx={{ fontWeight: 600 }}>
                  {toolNameLabel(tool.kind, locale)}
                </Typography>
                <Typography variant="caption" sx={{ color: '#A50E0E' }}>
                  — {filterReason(tool.reason)}
                </Typography>
              </Stack>
            ))}
          </Stack>
        )}
      </Box>

      {sources.length > 0 && (
        <Box
          sx={{
            p: 1.5,
            borderRadius: 3,
            bgcolor: '#FFFFFF',
            border: `1px solid ${googleTokens.outline}`
          }}
        >
          <Typography variant="subtitle2" sx={{ mb: 0.75 }}>
            {t('assembly.sources', { count: sources.length })}
          </Typography>
          <Stack direction="row" spacing={0.6} sx={{ flexWrap: 'wrap', rowGap: 0.6 }}>
            {sources.map((src, index) => {
              const refLabel = src.reference || src.id || src.sourceId || src.label || '';
              return (
                <Chip
                  key={`${src.kind}:${refLabel}:${index}`}
                  size="small"
                  variant="outlined"
                  label={`${src.kind}${refLabel ? ` · ${refLabel}` : ''}${src.version ? ` v${src.version}` : ''}`}
                />
              );
            })}
          </Stack>
        </Box>
      )}

      {memoryMatches.length > 0 && (
        <Box
          sx={{
            p: 1.5,
            borderRadius: 3,
            bgcolor: '#FFFFFF',
            border: `1px solid ${googleTokens.outline}`
          }}
        >
          <Typography variant="subtitle2" sx={{ mb: 0.75 }}>
            {t('assembly.memory')}
          </Typography>
          <Stack spacing={0.6}>
            {memoryMatches.map(match => (
              <Stack
                key={`${match.memoryId}:${match.version}`}
                direction={{ xs: 'column', sm: 'row' }}
                spacing={0.6}
                sx={{
                  alignItems: { sm: 'center' },
                  px: 0.75,
                  py: 0.5,
                  borderRadius: 2,
                  bgcolor: '#F8F9FA'
                }}
              >
                <Chip
                  size="small"
                  label={`${t(`memory.type.${match.memoryType}`)} · ${Number(match.score).toFixed(2)}`}
                  sx={{ bgcolor: '#E6F4EA', color: '#137333', fontWeight: 600 }}
                />
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ overflowWrap: 'anywhere' }}
                >
                  {match.matchedReason}
                </Typography>
              </Stack>
            ))}
          </Stack>
        </Box>
      )}

      {latest?.assembly && (
        <TraceSection
          title={t('assembly.snapshot')}
          subtitle={t('assembly.attempt', { number: latest.number })}
          dense
        >
          <Readout mono maxHeight={220}>
            {JSON.stringify(latest.assembly, null, 2)}
          </Readout>
        </TraceSection>
      )}
    </Stack>
  );
}
