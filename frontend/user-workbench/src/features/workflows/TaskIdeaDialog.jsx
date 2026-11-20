import React, { useState } from 'react';
import {
  Alert, Box, Button, Checkbox, Chip, Divider, FormControlLabel, MenuItem, Stack, TextField, Typography
} from '@mui/material';
import AutoAwesomeOutlined from '@mui/icons-material/AutoAwesomeOutlined';
import WorkbenchDialog from '../../layout/WorkbenchDialog.jsx';
import PlanCandidateDialog from './PlanCandidateDialog.jsx';

const entries = {
  GENERAL: '通用目标', REQUIREMENT: '需求', DESIGN: '概要设计', CODE: '代码', TEST: '测试'
};
const lines = value => value.split('\n').map(line => line.trim()).filter(Boolean);
const post = (value) => ({ method: 'POST', body: JSON.stringify(value) });

export default function TaskIdeaDialog({ api, onClose, onCreated, onManual }) {
  const [idea, setIdea] = useState('');
  const [mode, setMode] = useState('DEEPSEEK');
  const [form, setForm] = useState(null);
  const [modelMode, setModelMode] = useState('mock');
  const [reviewPlan, setReviewPlan] = useState(true);
  const [phase, setPhase] = useState(null);
  const [error, setError] = useState('');
  const [prepared, setPrepared] = useState(null);
  const [draftIdea, setDraftIdea] = useState(null);
  const [planOpen, setPlanOpen] = useState(false);
  const stale = form && idea.trim() !== draftIdea;
  const busy = phase != null;
  const files = form?.entryKind === 'CODE' ? lines(form.files) : [];
  const needsExistingWorkspace = form?.entryKind === 'TEST' || form?.entryKind === 'CODE' && !files.length;

  function change(field, value) {
    setForm(previous => ({ ...previous, [field]: value }));
    setError('');
  }

  async function generate() {
    setPhase('draft');
    setError('');
    try {
      const draft = await api('/task-ideas/draft', post({ idea: idea.trim(), mode }));
      setForm({
        entryKind: draft.entryKind,
        goal: draft.goal,
        deliverable: draft.deliverable,
        constraints: draft.constraints.join('\n'),
        acceptance: draft.acceptanceCriteria.join('\n'),
        files: draft.suggestedFiles.join('\n'),
        assumptions: draft.assumptions,
        questions: draft.questions
      });
      setDraftIdea(idea.trim());
      setModelMode(mode === 'DEEPSEEK' ? 'deepseek' : 'mock');
    } catch (failure) {
      setError(failure.message);
    } finally {
      setPhase(null);
    }
  }

  function taskInput() {
    const constraints = lines(form.constraints);
    const deliverable = form.deliverable.trim();
    const acceptanceCriteria = lines(form.acceptance);
    if (!form.goal.trim() || !deliverable || !acceptanceCriteria.length) {
      throw new Error('请填写目标、交付物和至少一条验收条件。');
    }
    constraints.push(`交付物：${deliverable}`);
    if (constraints.length > 10 || acceptanceCriteria.length > 10 ||
        [...constraints, ...acceptanceCriteria].some(line => line.length > 300)) {
      throw new Error('约束（含交付物）及验收条件各最多 10 条，每条最多 300 字。');
    }
    return { goal: form.goal.trim(), entryKind: form.entryKind, constraints, acceptanceCriteria, modelMode, reviewPlan };
  }

  function fillManual() {
    try {
      onManual(form ? taskInput() : null);
    } catch (failure) {
      setError(failure.message);
    }
  }

  async function create() {
    setError('');
    setPhase('create');
    try {
      const input = taskInput();
      if (form.entryKind === 'CODE') {
        const fileSet = JSON.stringify(files);
        let workspace = prepared?.fileSet === fileSet ? prepared.workspace : null;
        if (!workspace) {
          workspace = await api('/workspaces/from-idea', post({ files }));
          setPrepared({ fileSet, workspace });
        }
        Object.assign(input, { workspaceId: workspace.id, fileScopes: files, writeEnabled: true });
      }
      if (form.entryKind === 'GENERAL') input.workflowTemplate = 'dual-analysis';
      const task = await api('/tasks', post(input));
      onCreated(task.runId);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setPhase(null);
    }
  }

  return <WorkbenchDialog title="新建任务" open width={720} footer={null} onCancel={() => !busy && onClose()}>
    <Stack spacing={2}>
      <TextField label="你想做什么？" placeholder="例如：做一个能看柱状条交换的冒泡排序网页"
        multiline minRows={3} fullWidth value={idea} disabled={busy} slotProps={{ htmlInput: { maxLength: 2500 } }}
        onChange={event => { setIdea(event.target.value); setError(''); }} />
      <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ alignItems: { sm: 'center' }, gap: 1 }}>
        <TextField select size="small" label="整理方式" value={mode} onChange={event => setMode(event.target.value)}
          disabled={busy} sx={{ minWidth: 145 }}>
          <MenuItem value="DEEPSEEK">DeepSeek</MenuItem>
          <MenuItem value="LOCAL">本地规则</MenuItem>
        </TextField>
        <Button variant={form ? 'outlined' : 'contained'} startIcon={<AutoAwesomeOutlined />} loading={phase === 'draft'}
          disabled={busy || !idea.trim()} onClick={generate}>{form ? '重新整理' : '整理成任务草稿'}</Button>
        <Button disabled={busy || stale} onClick={fillManual}>{form ? '填入完整表单' : '手动填写任务'}</Button>
      </Stack>
      {mode === 'DEEPSEEK' && <Typography variant="caption" color="text.secondary">
        点击整理会将上方想法发送给 DeepSeek，可能产生费用。
      </Typography>}
      {error && <Alert severity="error">{error}</Alert>}
      {form && <>
        <Divider />
        <Typography component="h3" variant="h6">可编辑草稿</Typography>
        {stale && <Alert severity="info">想法已修改，请重新整理后再创建任务。</Alert>}
        <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1.25 }}>
          <TextField select fullWidth size="small" label="任务入口" value={form.entryKind} disabled={busy}
            onChange={event => change('entryKind', event.target.value)}>
            {Object.entries(entries).map(([value, label]) => <MenuItem key={value} value={value}>{label}</MenuItem>)}
          </TextField>
          <TextField select fullWidth size="small" label="执行模型" value={modelMode} disabled={busy}
            onChange={event => setModelMode(event.target.value)}>
            <MenuItem value="mock">模拟模型</MenuItem>
            <MenuItem value="deepseek">DeepSeek Flash</MenuItem>
          </TextField>
        </Stack>
        <TextField label="任务目标" multiline minRows={2} value={form.goal} disabled={busy}
          slotProps={{ htmlInput: { maxLength: 500 } }} onChange={event => change('goal', event.target.value)} />
        <TextField label="交付物" value={form.deliverable} disabled={busy}
          slotProps={{ htmlInput: { maxLength: 200 } }} onChange={event => change('deliverable', event.target.value)} />
        <TextField label="任务约束（每行一条）" multiline minRows={2} value={form.constraints}
          disabled={busy} onChange={event => change('constraints', event.target.value)} />
        <TextField label="验收条件（每行一条）" multiline minRows={3} value={form.acceptance}
          disabled={busy} onChange={event => change('acceptance', event.target.value)} />
        {form.assumptions.length > 0 && <Box>
          <Typography variant="subtitle2">草稿假设</Typography>
          {form.assumptions.map(text => <Typography variant="body2" color="text.secondary" key={text}>{text}</Typography>)}
        </Box>}
        {form.questions.length > 0 && <Alert severity="info">
          <Typography variant="subtitle2">待确认</Typography>
          {form.questions.map(text => <Typography variant="body2" key={text}>{text}</Typography>)}
          <Typography variant="caption">可以补充到上方想法，重新整理。</Typography>
        </Alert>}
        {form.entryKind === 'CODE' && <TextField label="独立交付文件（每行一个）" multiline value={form.files}
          disabled={busy} onChange={event => change('files', event.target.value)}
          helperText="仅在从零制作时填写简单文件名。已有项目请进入完整表单选择工作区。" />}
        {needsExistingWorkspace ? <Alert severity="info">请点「填入完整表单」，选择已有工作区和授权文件。</Alert> : <>
          {files.length > 0 && <Box>
            <Typography variant="body2" sx={{ mb: 0.6 }}>确认后新建独立本地工作区，并允许 Agent 写入这些文件。</Typography>
            <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 0.5 }}>
              {files.map((path, index) => <Chip size="small" key={`${path}:${index}`} label={path} />)}
            </Stack>
          </Box>}
          <FormControlLabel label="先审阅执行计划" control={<Checkbox checked={reviewPlan} disabled={busy}
            onChange={event => setReviewPlan(event.target.checked)} />} />
          {files.length > 0 && <Button variant="outlined" disabled={busy || stale}
            onClick={() => setPlanOpen(true)}>生成执行计划草稿</Button>}
          <Button variant="contained" sx={{ alignSelf: 'flex-start' }} loading={phase === 'create'}
            disabled={busy || stale} onClick={create}>
            {files.length ? (reviewPlan ? '创建工作区并生成计划' : '创建工作区并开始运行')
              : (reviewPlan ? '确认并创建计划' : '确认并开始运行')}
          </Button>
        </>}
      </>}
    </Stack>
    {planOpen && <PlanCandidateDialog api={api} onClose={() => setPlanOpen(false)} onRun={onCreated}
      input={{
        goal: form.goal.trim(), entryKind: 'CODE', workspaceId: null, writeEnabled: true,
        fileScopes: files, constraints: lines(form.constraints),
        acceptanceCriteria: lines(form.acceptance), deliverable: form.deliverable.trim() || null
      }} />}
  </WorkbenchDialog>;
}
