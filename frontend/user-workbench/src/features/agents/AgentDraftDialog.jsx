import { useState } from 'react';
import { Alert, Button, MenuItem, Stack, TextField, Typography } from '@mui/material';
import AutoAwesomeOutlined from '@mui/icons-material/AutoAwesomeOutlined';
import WorkbenchDialog from '../../components/WorkbenchDialog';
import { agentsApi } from '../../api/agents';
import { useToast } from '../../components/ToastProvider';

/** 从设计思路生成可编辑草稿；不会直接发布或执行。 */
export default function AgentDraftDialog({ open, onClose, onApply }) {
  const toast = useToast();
  const [idea, setIdea] = useState('');
  const [mode, setMode] = useState('LOCAL');
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const generate = async () => {
    if (!idea.trim()) {
      setError('请先描述职责与边界。');
      return;
    }
    setBusy(true);
    setError('');
    try {
      setResult(await agentsApi.draftAgent({ idea: idea.trim(), mode }));
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  const apply = () => {
    if (!result) return;
    const initial = {
      ...result,
      name: result.name || '',
      description: result.description || '',
      responsibility: result.responsibility || '',
      outputRequirements: result.outputRequirements || '',
      permissionRole: result.permissionRole || 'ANALYST',
      recommendable: result.recommendable ?? true,
      modelStrategy: result.modelStrategy || 'INHERIT_TASK',
      modelMode: result.modelMode || 'mock',
      skills: result.suggestedSkills || [],
      tools: result.suggestedTools || []
    };
    onApply?.(initial);
    toast.success('已填入 Agent 草稿，请检查后再保存');
    onClose();
  };

  return (
    <WorkbenchDialog
      open={open}
      onCancel={onClose}
      onOk={result ? apply : generate}
      okText={result ? '填入 Agent 草稿' : '生成草稿'}
      confirmLoading={busy}
      width={720}
      title="从设计思路生成 Agent 草稿"
      subtitle="本地规则不调用模型；模型生成会把思路发送给已配置的 DeepSeek。"
    >
      <Stack spacing={1.75} sx={{ pt: 0.5 }}>
        <Alert severity="info">
          生成结果只是可编辑草稿；不会直接发布或执行，Skill / 工具搭配需要人工检查。
        </Alert>
        {error && <Alert severity="error">{error}</Alert>}
        <TextField
          label="Agent 设计思路"
          placeholder="例如：检查前端页面的交互和可访问性，只搜索 .jsx/.tsx 文件，列出覆盖和遗漏"
          multiline
          minRows={3}
          value={idea}
          onChange={event => setIdea(event.target.value)}
          slotProps={{ htmlInput: { maxLength: 1200 } }}
        />

        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={1.25}
          sx={{ alignItems: { sm: 'center' } }}
        >
          <TextField
            select
            size="small"
            label="生成方式"
            value={mode}
            onChange={event => setMode(event.target.value)}
            sx={{ minWidth: 210 }}
          >
            <MenuItem value="LOCAL">本地规则（不调用模型）</MenuItem>
            <MenuItem value="MODEL">模型生成（DeepSeek）</MenuItem>
          </TextField>
          <Button
            variant="outlined"
            startIcon={<AutoAwesomeOutlined />}
            loading={busy}
            onClick={generate}
          >
            生成草稿
          </Button>
        </Stack>
        {result && (
          <Stack spacing={0.8}>
            <Typography variant="subtitle2">草稿预览</Typography>
            <Typography variant="body2">
              {result.name || '未命名 Agent'} · {result.permissionRole || 'ANALYST'} ·{' '}
              {result.modelStrategy === 'FIXED' ? `固定 ${result.modelMode}` : '继承任务模型'}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ whiteSpace: 'pre-wrap' }}>
              {result.responsibility || result.description || '未记录职责说明'}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              建议 Skill：{(result.suggestedSkills ?? []).map(item => item.name).join('、') || '无'}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              建议工具：{(result.suggestedTools ?? []).map(item => item.name).join('、') || '无'}
            </Typography>
          </Stack>
        )}
      </Stack>
    </WorkbenchDialog>
  );
}
