import React, { useState } from 'react';
import {
  Accordion, AccordionDetails, AccordionSummary, Box, Chip, Divider, FormControlLabel,
  List, ListItemButton, Paper, Stack, Switch, TextField, ToggleButton, ToggleButtonGroup, Typography
} from '@mui/material';
import ExpandMoreRounded from '@mui/icons-material/ExpandMoreRounded';
import { RunStatusChip } from '../features/workflows/RunTraceGraph.jsx';

const active = new Set(['PENDING', 'RUNNING', 'CANCELLING', 'PAUSED']);
const attention = new Set(['BLOCKED', 'FAILED', 'INTERRUPTED', 'LIMITED']);

function TaskList({ tasks, selectedId, archived, onArchived, onSelect, dateTime }) {
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('all');
  const text = search.trim().toLocaleLowerCase();
  const visible = tasks.filter(task => (archived || !task.ARCHIVED_AT)
    && (!text || [task.GOAL, task.RUN_ID].some(value => String(value ?? '').toLocaleLowerCase().includes(text)))
    && (filter === 'all' || (filter === 'active' ? active.has(task.STATUS) : attention.has(task.STATUS))));

  return <>
    <Stack spacing={1.15} sx={{ px: 1.5, pb: 1.25 }}>
      <TextField size="small" label="查找任务" value={search} onChange={event => setSearch(event.target.value)} />
      <ToggleButtonGroup size="small" exclusive value={filter} onChange={(_, next) => next && setFilter(next)}
        aria-label="任务状态筛选" fullWidth>
        <ToggleButton value="all">全部</ToggleButton>
        <ToggleButton value="active">进行中</ToggleButton>
        <ToggleButton value="attention">需处理</ToggleButton>
      </ToggleButtonGroup>
      <FormControlLabel control={<Switch size="small" checked={archived} onChange={event => onArchived(event.target.checked)} />}
        label={<Typography variant="caption">显示归档</Typography>} />
    </Stack>
    <Divider />
    {visible.length === 0 ? <Typography variant="body2" color="text.secondary" sx={{ p: 2 }}>
      {tasks.length ? '当前条件下没有任务。' : '还没有任务。'}
    </Typography> : <List disablePadding aria-label="任务列表" sx={{ p: 0.75, maxHeight: { md: 'min(62vh, 740px)' }, overflow: 'auto' }}>
      {visible.map(task => <ListItemButton key={task.ID} selected={task.RUN_ID === selectedId}
        onClick={() => onSelect(task.RUN_ID)} sx={{ borderRadius: 2, py: 1.2, mb: 0.45 }}>
        <Stack spacing={0.85} sx={{ minWidth: 0, width: '100%' }}>
          <Typography variant="body2" title={task.GOAL} sx={{
            fontWeight: task.RUN_ID === selectedId ? 650 : 500,
            display: '-webkit-box', WebkitBoxOrient: 'vertical', WebkitLineClamp: 2,
            overflow: 'hidden', overflowWrap: 'anywhere'
          }}>{task.GOAL}</Typography>
          <Stack direction="row" sx={{ alignItems: 'center', gap: 0.75, flexWrap: 'wrap' }}>
            {task.PLAN_REVIEW_PENDING ? <Chip variant="outlined" size="small" label="待确认计划" color="warning" />
              : <RunStatusChip value={task.STATUS} />}
            <Typography variant="caption" color="text.secondary">{dateTime(task.CREATED_AT)}</Typography>
            {task.ARCHIVED_AT && <Typography variant="caption" color="text.secondary">已归档</Typography>}
          </Stack>
        </Stack>
      </ListItemButton>)}
    </List>}
  </>;
}

export default function TaskNavigator(props) {
  const count = props.tasks.filter(task => props.archived || !task.ARCHIVED_AT).length;
  return <>
    <Paper component="aside" variant="outlined" sx={{ display: { xs: 'none', md: 'block' }, position: 'sticky', top: 84, minWidth: 0 }}>
      <Box sx={{ p: 1.5 }}>
        <Typography component="h2" variant="subtitle1" sx={{ fontWeight: 650 }}>任务列表</Typography>
        <Typography variant="caption" color="text.secondary">{count} 个任务</Typography>
      </Box>
      <TaskList {...props} />
    </Paper>
    <Accordion disableGutters variant="outlined" sx={{ display: { xs: 'block', md: 'none' } }}>
      <AccordionSummary expandIcon={<ExpandMoreRounded />}>
        <Typography variant="subtitle2">切换任务 · {count} 个</Typography>
      </AccordionSummary>
      <AccordionDetails sx={{ p: 0 }}>
        <TaskList {...props} />
      </AccordionDetails>
    </Accordion>
  </>;
}

export function WorkbenchOverview({ overview, onMonitor }) {
  const values = [['运行总数', overview?.TOTAL], ['运行中', overview?.RUNNING], ['已完成', overview?.COMPLETED]];
  return <Stack spacing={2.5}>
    <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', gap: 1.5, flexWrap: 'wrap' }}>
      <Typography component="h1" variant="h4">运行概况</Typography>
      <Chip component="button" clickable onClick={onMonitor} label="打开运行监控" color="primary" variant="outlined" />
    </Stack>
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, minmax(0, 1fr))' }, gap: 2 }}>
      {values.map(([label, value]) => <Paper key={label} variant="outlined" sx={{ p: 2.5 }}>
        <Typography variant="body2" color="text.secondary">{label}</Typography>
        <Typography variant="h4" sx={{ mt: 0.7, fontVariantNumeric: 'tabular-nums' }}>{value ?? '未记录'}</Typography>
      </Paper>)}
    </Box>
  </Stack>;
}
