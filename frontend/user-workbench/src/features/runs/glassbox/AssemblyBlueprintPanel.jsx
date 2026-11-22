import { Box, Chip, Stack, Typography } from '@mui/material';
import LockOutlined from '@mui/icons-material/LockOutlined';
import CheckCircleOutlineRounded from '@mui/icons-material/CheckCircleOutlineRounded';
import { TraceSection, Readout } from '../../../components/WorkbenchDialog';
import { modelLabel, toolNameLabel } from '../../../constants/labels';
import { googleTokens } from '../../../theme/googleTheme';
import { useI18n } from '../../../i18n/I18nProvider';

const ALL_CORE_TOOLS = ['SIMULATED_ISSUE', 'WORKSPACE_READ', 'WORKSPACE_SEARCH', 'WORKSPACE_WRITE'];

/** 玻璃盒面板一：Agent 诞生配方（角色、Skill、工具权限漏斗与上下文来源）。 */
export default function AssemblyBlueprintPanel({ node, attempts }) {
  const { t, locale } = useI18n();
  const latest = attempts.find(item => item.assembly) ?? null;
  const components = latest?.assembly?.components ?? null;
  const grantedMap = components?.tools ?? {};
  const grantedKeys = Object.keys(grantedMap);
  const filteredKeys = ALL_CORE_TOOLS.filter(key => !grantedKeys.includes(key));
  const sources = components?.input?.sources ?? [];
  const memoryMatches = components?.memoryMatches ?? [];

  const filterReason = key => {
    if (key === 'WORKSPACE_WRITE') {
      return node?.workspaceWrite ? t('assembly.writeNotDeclared') : t('assembly.readOnly');
    }
    if (key === 'WORKSPACE_READ' || key === 'WORKSPACE_SEARCH') {
      return node?.workspaceRead ? t('assembly.toolNotDeclared') : t('assembly.noWorkspace');
    }
    return t('assembly.notAssembled');
  };

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
          {t('assembly.granted', { count: grantedKeys.length })}
        </Typography>
        <Stack direction="row" spacing={0.6} sx={{ flexWrap: 'wrap', rowGap: 0.6, mb: 1.25 }}>
          {grantedKeys.length === 0 ? (
            <Typography variant="caption" color="text.secondary">
              {t('assembly.noTools')}
            </Typography>
          ) : (
            grantedKeys.map(key => (
              <Chip
                key={key}
                size="small"
                icon={<CheckCircleOutlineRounded sx={{ fontSize: 14 }} />}
                label={`${toolNameLabel(key, locale)} v${grantedMap[key]}`}
                sx={{ bgcolor: googleTokens.successBg, color: googleTokens.successFg }}
              />
            ))
          )}
        </Stack>

        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
          {t('assembly.filtered', { count: filteredKeys.length })}
        </Typography>
        <Stack spacing={0.6}>
          {filteredKeys.map(key => (
            <Stack
              key={key}
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
                {toolNameLabel(key, locale)}
              </Typography>
              <Typography variant="caption" sx={{ color: '#A50E0E' }}>
                — {filterReason(key)}
              </Typography>
            </Stack>
          ))}
        </Stack>
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
