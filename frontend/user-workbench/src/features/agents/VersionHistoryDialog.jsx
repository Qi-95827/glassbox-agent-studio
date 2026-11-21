import { useQuery } from '@tanstack/react-query';
import { CircularProgress, Stack, Typography } from '@mui/material';
import WorkbenchDialog from '../../components/WorkbenchDialog';
import { agentsApi } from '../../api/agents';

const loaders = {
  skill: agentsApi.skillVersions,
  tool: agentsApi.toolVersions,
  agent: agentsApi.agentVersions
};

/** 已发布版本：不可变，旧运行继续引用当时版本。 */
export default function VersionHistoryDialog({ open, type, id, onClose }) {
  const versions = useQuery({
    queryKey: ['versions', type, id],
    queryFn: () => loaders[type](id),
    enabled: open && Boolean(id)
  });

  return (
    <WorkbenchDialog
      open={open}
      onCancel={onClose}
      footer={null}
      width={640}
      title="已发布版本"
      subtitle="历史版本不可修改；旧运行继续使用发布时的定义。"
    >
      <Stack spacing={1.2} sx={{ pt: 0.5 }}>
        {versions.isLoading && <CircularProgress size={18} />}
        {versions.isError && (
          <Typography variant="body2" color="error.main">
            {versions.error.message}
          </Typography>
        )}
        {(versions.data ?? []).length === 0 && !versions.isLoading && (
          <Typography variant="body2" color="text.secondary">
            暂无已发布版本。
          </Typography>
        )}
        {(versions.data ?? []).map(row => (
          <Stack
            key={row.version}
            sx={{ borderBottom: '1px solid', borderColor: 'divider', pb: 1 }}
            spacing={0.3}
          >
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              v{row.version} · {row.name}
              {row.modelStrategy === 'FIXED' ? ` · 固定模型 ${row.modelMode}` : ''}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {row.specSha256
                ? `摘要 ${row.specSha256.slice(0, 16)}…`
                : row.createdAt || '未记录时间'}
            </Typography>
            {row.skills && (
              <Typography variant="caption" color="text.secondary">
                Skill：{row.skills.map(item => `${item.name} v${item.version}`).join('、') || '无'}
                {'；'}工具：
                {row.tools.map(item => `${item.name} v${item.version}`).join('、') || '无'}
              </Typography>
            )}
          </Stack>
        ))}
      </Stack>
    </WorkbenchDialog>
  );
}
