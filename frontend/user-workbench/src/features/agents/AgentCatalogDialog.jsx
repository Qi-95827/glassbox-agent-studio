import { useQuery } from '@tanstack/react-query';
import { Alert, Box, CircularProgress, Stack, Typography } from '@mui/material';
import WorkbenchDialog, { TraceSection } from '../../components/WorkbenchDialog';
import { agentsApi } from '../../api/agents';
import { TOOL_NAMES } from '../../constants/labels';

/** 内置 Agent 角色目录：展示能力声明，实际授权以运行记录为准。 */
export default function AgentCatalogDialog({ open, onClose }) {
  const catalog = useQuery({
    queryKey: ['agent-catalog'],
    queryFn: agentsApi.catalog,
    enabled: open
  });

  return (
    <WorkbenchDialog
      open={open}
      onCancel={onClose}
      footer={null}
      width={760}
      title="内置 Agent 角色"
      subtitle="能力声明不等于执行记录；具体任务仍需用户授权。"
    >
      <Stack spacing={1.5} sx={{ pt: 0.5 }}>
        <Alert severity="info">
          当前为固定角色协作，暂不支持在页面创建内置角色或动态调整执行图。
        </Alert>
        {catalog.isLoading && <CircularProgress size={18} />}
        {catalog.isError && <Alert severity="error">角色目录暂时不可用。</Alert>}
        {(catalog.data ?? []).map(role => (
          <TraceSection
            key={role.role}
            title={`${role.displayName} · 角色版本 ${role.version}`}
            subtitle={role.agentName}
          >
            <Stack spacing={0.75}>
              <Typography variant="body2">
                声明的工具能力：
                {role.declaredTools.map(tool => TOOL_NAMES[tool] || tool).join('、') || '无'}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                角色指导：{role.systemPrompt}
              </Typography>
              {role.skills?.map(skill => (
                <Box
                  key={skill.id}
                  sx={{
                    p: 1.25,
                    borderRadius: 2,
                    bgcolor: '#F8F9FA',
                    border: '1px solid',
                    borderColor: 'divider'
                  }}
                >
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {skill.name} · v{skill.version}
                  </Typography>
                  <Typography variant="body2">{skill.guidance}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    内容摘要：{skill.contentSha256}
                  </Typography>
                </Box>
              ))}
            </Stack>
          </TraceSection>
        ))}
      </Stack>
    </WorkbenchDialog>
  );
}
