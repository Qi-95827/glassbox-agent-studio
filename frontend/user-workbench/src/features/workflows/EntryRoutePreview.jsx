import { useEffect, useState } from 'react';
import { Box, Stack, Typography } from '@mui/material';
import AccountTreeOutlined from '@mui/icons-material/AccountTreeOutlined';
import { tasksApi } from '../../api/tasks';

/** 创建任务前显示服务端规则路由及选择原因；路由只解释边界，不授予权限。 */
export default function EntryRoutePreview({
  entryKind,
  workspaceId,
  writeEnabled,
  fileScopes,
  directoryScopes,
  workflowTemplate
}) {
  const [decision, setDecision] = useState(null);
  const [error, setError] = useState(null);
  const fileKey = (fileScopes || []).join('\n');
  const directoryKey = (directoryScopes || []).join('\n');

  useEffect(() => {
    let active = true;
    const timer = setTimeout(() => {
      tasksApi
        .previewEntryRoute({
          entryKind,
          workspaceId: workspaceId || null,
          writeEnabled: Boolean(writeEnabled),
          fileScopes: fileScopes || [],
          directoryScopes: directoryScopes || [],
          workflowTemplate: workflowTemplate || null
        })
        .then(value => {
          if (active) {
            setDecision(value);
            setError(null);
          }
        })
        .catch(failure => {
          if (active) {
            setDecision(null);
            setError(failure.message);
          }
        });
    }, 180);
    return () => {
      active = false;
      clearTimeout(timer);
    };
    // fileKey / directoryKey 已覆盖数组内容；直接依赖数组会因父组件每次创建新数组而重复请求。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entryKind, workspaceId, writeEnabled, fileKey, directoryKey, workflowTemplate]);

  if (error) {
    return (
      <Box
        sx={{ px: 1.75, py: 1.25, border: '1px dashed', borderColor: 'divider', borderRadius: 2.5 }}
      >
        <Typography variant="caption" color="text.secondary">
          规则路由暂时无法计算：{error}
        </Typography>
      </Box>
    );
  }
  if (!decision) return null;

  return (
    <Box
      sx={{
        px: 1.75,
        py: 1.35,
        borderRadius: 2.5,
        bgcolor: '#F8FAFF',
        border: '1px solid #E3ECFB'
      }}
    >
      <Stack direction="row" spacing={0.75} sx={{ mb: 0.4, alignItems: 'center' }}>
        <AccountTreeOutlined sx={{ fontSize: 16, color: '#1967D2' }} />
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          规则路由 · {decision.ruleCode}
        </Typography>
      </Stack>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
        执行模板：{decision.templateId || '兼容流程'}；入口：{decision.routedKind}
      </Typography>
      {decision.reasons?.map((reason, index) => (
        <Typography key={index} variant="caption" color="text.secondary" sx={{ display: 'block' }}>
          · {reason}
        </Typography>
      ))}
      {decision.warnings?.map((warning, index) => (
        <Typography key={index} variant="caption" color="warning.dark" sx={{ display: 'block' }}>
          注意：{warning}
        </Typography>
      ))}
    </Box>
  );
}
