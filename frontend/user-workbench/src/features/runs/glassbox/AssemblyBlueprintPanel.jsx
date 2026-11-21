import { Box, Chip, Stack, Typography } from '@mui/material';
import LockOutlined from '@mui/icons-material/LockOutlined';
import CheckCircleOutlineRounded from '@mui/icons-material/CheckCircleOutlineRounded';
import { TraceSection, Readout } from '../../../components/WorkbenchDialog';
import { TOOL_NAMES, modelLabel } from '../../../constants/labels';
import { googleTokens } from '../../../theme/googleTheme';

const ALL_CORE_TOOLS = ['SIMULATED_ISSUE', 'WORKSPACE_READ', 'WORKSPACE_SEARCH', 'WORKSPACE_WRITE'];

/** 玻璃盒面板一：Agent 诞生配方（角色、Skill、工具权限漏斗与上下文来源）。 */
export default function AssemblyBlueprintPanel({ node, attempts }) {
  const latest = attempts.find(item => item.assembly) ?? null;
  const components = latest?.assembly?.components ?? null;
  const grantedMap = components?.tools ?? {};
  const grantedKeys = Object.keys(grantedMap);
  const filteredKeys = ALL_CORE_TOOLS.filter(key => !grantedKeys.includes(key));
  const sources = components?.input?.sources ?? [];

  const filterReason = key => {
    if (key === 'WORKSPACE_WRITE') {
      return node?.workspaceWrite ? '当前角色定义未声明写工具' : '节点为只读权限，平台已剥离写工具';
    }
    if (key === 'WORKSPACE_READ' || key === 'WORKSPACE_SEARCH') {
      return node?.workspaceRead
        ? '当前角色定义未声明该工具'
        : '纯文本/未授权工作区节点，已隔离文件访问';
    }
    return '当前任务入口未装配此工具';
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
          身份与绑定配方
        </Typography>
        <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap', rowGap: 0.75 }}>
          <Chip size="small" label={`定义：${node?.definitionId || '内置默认'}`} />
          {node?.definitionVersion != null && (
            <Chip size="small" label={`版本 v${node.definitionVersion}`} />
          )}
          {components?.model?.name && (
            <Chip
              size="small"
              sx={{ bgcolor: googleTokens.infoBg, color: googleTokens.infoFg }}
              label={`模型：${modelLabel(components.model.mode || components.model.name)}`}
            />
          )}
          <Chip
            size="small"
            label={`Skill：${(node?.agentSkills ?? []).map(s => s.name).join('、') || '无'}`}
          />
        </Stack>
        {node?.agentReason && (
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>
            规划选择理由：{node.agentReason}
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
          工具权限漏斗（平台硬控制）
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
          已放行注入给模型（{grantedKeys.length}）
        </Typography>
        <Stack direction="row" spacing={0.6} sx={{ flexWrap: 'wrap', rowGap: 0.6, mb: 1.25 }}>
          {grantedKeys.length === 0 ? (
            <Typography variant="caption" color="text.secondary">
              无工具注入（纯推理/审查节点）
            </Typography>
          ) : (
            grantedKeys.map(key => (
              <Chip
                key={key}
                size="small"
                icon={<CheckCircleOutlineRounded sx={{ fontSize: 14 }} />}
                label={`${TOOL_NAMES[key] || key} v${grantedMap[key]}`}
                sx={{ bgcolor: googleTokens.successBg, color: googleTokens.successFg }}
              />
            ))
          )}
        </Stack>

        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
          被权限边界裁剪移除（{filteredKeys.length}）
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
                {TOOL_NAMES[key] || key}
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
            上下文组装来源（{sources.length} 项）
          </Typography>
          <Stack direction="row" spacing={0.6} sx={{ flexWrap: 'wrap', rowGap: 0.6 }}>
            {sources.map((src, index) => (
              <Chip
                key={`${src.kind}:${src.reference}:${index}`}
                size="small"
                variant="outlined"
                label={`${src.kind} · ${src.reference}${src.version ? ` v${src.version}` : ''}`}
              />
            ))}
          </Stack>
        </Box>
      )}

      {latest?.assembly && (
        <TraceSection title="完整装配快照 JSON" subtitle={`尝试 #${latest.number}`} dense>
          <Readout mono maxHeight={220}>
            {JSON.stringify(latest.assembly, null, 2)}
          </Readout>
        </TraceSection>
      )}
    </Stack>
  );
}
