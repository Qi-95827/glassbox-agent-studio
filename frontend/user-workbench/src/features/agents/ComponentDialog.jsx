import { useEffect, useState } from 'react';
import {
  Alert,
  Checkbox,
  Chip,
  FormControlLabel,
  ListItemText,
  MenuItem,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import WorkbenchDialog from '../../components/WorkbenchDialog';
import { agentsApi } from '../../api/agents';
import { useToast } from '../../components/ToastProvider';
import { useQueryClient } from '@tanstack/react-query';

const empty = {
  skill: {
    name: '',
    summary: '',
    usageNotes: '',
    inputContract: '',
    outputContract: '',
    contentMd: ''
  },
  tool: {
    name: '',
    description: '',
    executorKind: 'SEARCH',
    fileExtensions: '.jsx, .tsx'
  },
  agent: {
    name: '',
    description: '',
    responsibility: '',
    outputRequirements: '',
    permissionRole: 'ANALYST',
    recommendable: true,
    modelStrategy: 'INHERIT_TASK',
    modelMode: 'mock',
    skillIds: [],
    toolIds: [],
    skillPins: {},
    toolPins: {}
  }
};

const titles = {
  skill: '私有 Skill',
  tool: '私有工具',
  agent: '私有 Agent'
};

/** Skill / 工具 / Agent 的统一编辑弹窗，保存的是可编辑草稿。 */
export default function ComponentDialog({
  open,
  type,
  initial,
  skills = [],
  tools = [],
  onClose,
  onSaved
}) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [form, setForm] = useState(empty[type]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    setError('');
    if (!initial) {
      setForm(empty[type]);
      return;
    }
    if (type === 'tool') {
      setForm({
        ...empty.tool,
        ...initial,
        fileExtensions: (initial.fileExtensions ?? []).join(', ')
      });
      return;
    }
    if (type === 'agent') {
      const skillPins = Object.fromEntries(
        (initial.skills ?? []).map(item => [item.id, item.version])
      );
      const toolPins = Object.fromEntries(
        (initial.tools ?? []).map(item => [item.id, item.version])
      );
      setForm({
        ...empty.agent,
        ...initial,
        modelStrategy: initial.modelStrategy || 'INHERIT_TASK',
        modelMode: initial.modelMode || 'mock',
        skillIds: (initial.skills ?? []).map(item => item.id),
        toolIds: (initial.tools ?? []).map(item => item.id),
        skillPins,
        toolPins
      });
      return;
    }
    setForm({ ...empty.skill, ...initial });
  }, [open, type, initial]);

  const update = (field, value) => setForm(previous => ({ ...previous, [field]: value }));

  const save = async () => {
    if (!form.name?.trim()) {
      setError('请填写名称。');
      return;
    }
    setBusy(true);
    setError('');
    try {
      if (type === 'skill') {
        const payload = {
          name: form.name,
          summary: form.summary,
          usageNotes: form.usageNotes,
          inputContract: form.inputContract,
          outputContract: form.outputContract,
          contentMd: form.contentMd
        };
        if (initial?.id) await agentsApi.updateSkill(initial.id, payload);
        else await agentsApi.createSkill(payload);
      } else if (type === 'tool') {
        const payload = {
          name: form.name,
          description: form.description,
          executorKind: form.executorKind,
          fileExtensions: String(form.fileExtensions)
            .split(',')
            .map(item => item.trim())
            .filter(Boolean)
        };
        if (initial?.id) await agentsApi.updateTool(initial.id, payload);
        else await agentsApi.createTool(payload);
      } else {
        const payload = {
          name: form.name,
          description: form.description,
          responsibility: form.responsibility,
          outputRequirements: form.outputRequirements,
          permissionRole: form.permissionRole,
          recommendable: form.recommendable,
          modelStrategy: form.modelStrategy,
          modelMode: form.modelMode,
          skills: form.skillIds.map(id => ({ id, version: form.skillPins?.[id] ?? null })),
          tools: form.toolIds.map(id => ({ id, version: form.toolPins?.[id] ?? null }))
        };
        if (initial?.id) await agentsApi.updateAgent(initial.id, payload);
        else await agentsApi.createAgent(payload);
      }
      queryClient.invalidateQueries({ queryKey: ['my-skills'] });
      queryClient.invalidateQueries({ queryKey: ['my-tools'] });
      queryClient.invalidateQueries({ queryKey: ['my-agents'] });
      toast.success(`${titles[type]}草稿已${initial?.id ? '更新' : '创建'}`);
      onSaved?.();
      onClose();
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <WorkbenchDialog
      open={open}
      onCancel={onClose}
      onOk={save}
      confirmLoading={busy}
      okText={initial?.id ? '保存草稿' : '创建草稿'}
      width={700}
      title={`${initial?.id ? '编辑' : '新建'}${titles[type]}`}
      subtitle="保存只创建草稿；发布后版本固定，旧运行继续使用发布时的组件。"
    >
      <Stack spacing={1.75} sx={{ pt: 0.5 }}>
        {error && <Alert severity="error">{error}</Alert>}
        <TextField
          label="名称"
          size="small"
          value={form.name}
          onChange={event => update('name', event.target.value)}
          autoFocus
          slotProps={{ htmlInput: { maxLength: 80 } }}
        />

        {type === 'skill' && (
          <>
            <TextField
              label="用途摘要"
              size="small"
              value={form.summary}
              onChange={event => update('summary', event.target.value)}
              slotProps={{ htmlInput: { maxLength: 300 } }}
            />

            <TextField
              label="输入约定（可选）"
              size="small"
              multiline
              minRows={2}
              value={form.inputContract}
              onChange={event => update('inputContract', event.target.value)}
            />

            <TextField
              label="预期输出（可选）"
              size="small"
              multiline
              minRows={2}
              value={form.outputContract}
              onChange={event => update('outputContract', event.target.value)}
            />

            <TextField
              label="Markdown 指导正文"
              multiline
              minRows={8}
              value={form.contentMd}
              onChange={event => update('contentMd', event.target.value)}
              helperText="可直接粘贴 .md 内容；发布后固定为不可变版本。"
            />
          </>
        )}

        {type === 'tool' && (
          <>
            <Alert severity="info">
              首批私有工具只使用受信只读执行器；扩展名过滤在任务与节点的授权文件范围内再次收窄，不能扩大文件权限。
            </Alert>
            <TextField
              label="说明"
              size="small"
              value={form.description}
              onChange={event => update('description', event.target.value)}
            />

            <TextField
              select
              size="small"
              label="执行器"
              value={form.executorKind}
              onChange={event => update('executorKind', event.target.value)}
            >
              <MenuItem value="SEARCH">授权搜索（固定查询方式）</MenuItem>
              <MenuItem value="READ">授权读取</MenuItem>
            </TextField>
            <TextField
              label="允许的扩展名"
              size="small"
              value={form.fileExtensions}
              onChange={event => update('fileExtensions', event.target.value)}
              helperText="逗号分隔，例如 .jsx, .tsx；留空表示服从授权范围。"
            />
          </>
        )}

        {type === 'agent' && (
          <>
            <TextField
              label="简介"
              size="small"
              value={form.description}
              onChange={event => update('description', event.target.value)}
            />

            <TextField
              label="职责"
              multiline
              minRows={3}
              value={form.responsibility}
              onChange={event => update('responsibility', event.target.value)}
              helperText="装配为系统职责，不会自动授予工具。"
            />

            <TextField
              label="输出要求"
              multiline
              minRows={2}
              value={form.outputRequirements}
              onChange={event => update('outputRequirements', event.target.value)}
            />

            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25}>
              <TextField
                select
                size="small"
                label="权限角色"
                value={form.permissionRole}
                onChange={event => update('permissionRole', event.target.value)}
                sx={{ flex: 1 }}
              >
                <MenuItem value="ANALYST">分析（只读）</MenuItem>
                <MenuItem value="REVIEWER">审查（无工具）</MenuItem>
              </TextField>
              <TextField
                select
                size="small"
                label="模型策略"
                value={form.modelStrategy}
                onChange={event => update('modelStrategy', event.target.value)}
                sx={{ flex: 1 }}
              >
                <MenuItem value="INHERIT_TASK">继承任务模型</MenuItem>
                <MenuItem value="FIXED">固定模型</MenuItem>
              </TextField>
              {form.modelStrategy === 'FIXED' && (
                <TextField
                  select
                  size="small"
                  label="模型"
                  value={form.modelMode}
                  onChange={event => update('modelMode', event.target.value)}
                  sx={{ flex: 1 }}
                >
                  <MenuItem value="mock">模拟模型</MenuItem>
                  <MenuItem value="deepseek">DeepSeek Flash</MenuItem>
                </TextField>
              )}
            </Stack>
            <FormControlLabel
              control={
                <Checkbox
                  size="small"
                  checked={form.recommendable}
                  onChange={event => update('recommendable', event.target.checked)}
                />
              }
              label={<Typography variant="body2">允许规划器在任务勾选后推荐</Typography>}
            />

            <TextField
              select
              size="small"
              label="Skill（发布时固定版本）"
              value={form.skillIds}
              onChange={event => {
                const value = event.target.value;
                update('skillIds', value);
                setForm(previous => ({
                  ...previous,
                  skillPins: Object.fromEntries(
                    value.map(id => [id, previous.skillPins?.[id] ?? null])
                  )
                }));
              }}
              slotProps={{
                select: {
                  multiple: true,
                  renderValue: value =>
                    value
                      .map(id => skills.find(item => item.id === id)?.name || id.slice(0, 6))
                      .join('、')
                }
              }}
            >
              {skills.map(row => (
                <MenuItem key={row.id} value={row.id}>
                  <Checkbox size="small" checked={form.skillIds.includes(row.id)} />
                  <ListItemText
                    primary={`${row.name} v${form.skillPins?.[row.id] ?? row.currentVersion}`}
                    secondary={row.status}
                  />
                </MenuItem>
              ))}
            </TextField>
            <TextField
              select
              size="small"
              label="只读工具（发布时固定版本）"
              value={form.toolIds}
              onChange={event => {
                const value = event.target.value;
                update('toolIds', value);
                setForm(previous => ({
                  ...previous,
                  toolPins: Object.fromEntries(
                    value.map(id => [id, previous.toolPins?.[id] ?? null])
                  )
                }));
              }}
              slotProps={{
                select: {
                  multiple: true,
                  renderValue: value =>
                    value
                      .map(id => tools.find(item => item.id === id)?.name || id.slice(0, 6))
                      .join('、')
                }
              }}
            >
              {tools.map(row => (
                <MenuItem key={row.id} value={row.id}>
                  <Checkbox size="small" checked={form.toolIds.includes(row.id)} />
                  <ListItemText
                    primary={`${row.name} v${form.toolPins?.[row.id] ?? row.currentVersion}`}
                    secondary={row.executorLabel || row.executorKind}
                  />
                </MenuItem>
              ))}
            </TextField>
            <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap' }}>
              <Chip size="small" label={`Skill ${form.skillIds.length}`} />
              <Chip size="small" label={`工具 ${form.toolIds.length}`} />
              {form.modelStrategy === 'FIXED' && (
                <Chip size="small" label={`固定 ${form.modelMode}`} />
              )}
            </Stack>
            <Typography variant="caption" color="text.secondary">
              未选择发布版本时，保存草稿会记录当时最新版本；发布后不可修改该版本。
            </Typography>
          </>
        )}
      </Stack>
    </WorkbenchDialog>
  );
}
