import { Box, Button, ButtonBase, Stack, Typography } from '@mui/material';
import ChevronRightRounded from '@mui/icons-material/ChevronRightRounded';
import PlayCircleOutlineRounded from '@mui/icons-material/PlayCircleOutlineRounded';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTasks } from '../../hooks/useTasks';
import { usePageChrome } from '../../layout/pageChrome';
import StatusChip from '../../components/StatusChip';
import EmptyState from '../../components/EmptyState';
import TaskComposer from './TaskComposer';
import { ENTRY_KINDS, modelLabel } from '../../constants/labels';
import { googleTokens } from '../../theme/googleTheme';
import { oneLine, timeAgo } from '../../utils/format';

function RunRow({ task }) {
  const navigate = useNavigate();
  return (
    <ButtonBase
      onClick={() => navigate(`/runs/${task.runId}`)}
      sx={{
        width: '100%',
        textAlign: 'left',
        px: 2,
        py: 1.4,
        borderRadius: 2.5,
        borderTop: `1px solid ${googleTokens.outline}`,
        '&:hover': { bgcolor: '#F8F9FA' }
      }}
    >
      <Stack
        direction="row"
        spacing={1.5}
        sx={{ width: '100%', minWidth: 0, alignItems: 'center' }}
      >
        <StatusChip value={task.status} />
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography noWrap sx={{ fontSize: 13.5, fontWeight: 500 }}>
            {oneLine(task.goal, 96) || '未命名任务'}
          </Typography>
          <Stack direction="row" spacing={1} sx={{ minWidth: 0, alignItems: 'center' }}>
            <Typography
              variant="caption"
              sx={{ fontFamily: googleTokens.mono, color: googleTokens.textSecondary }}
            >
              #{task.runId?.slice(0, 8)}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {ENTRY_KINDS[task.entryKind] || '通用目标'}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {timeAgo(task.createdAt)}
            </Typography>
          </Stack>
        </Box>
        <ChevronRightRounded sx={{ fontSize: 18, color: '#9AA0A6', flexShrink: 0 }} />
      </Stack>
    </ButtonBase>
  );
}

/** 工作台首页：渐进式披露的任务编写区 + 最近运行概览。 */
export default function HomePage() {
  const navigate = useNavigate();
  const tasks = useTasks();
  const recent = (tasks.data ?? []).filter(task => !task.archived).slice(0, 5);
  const [model, setModel] = useState('mock');

  usePageChrome({
    trail: [{ label: '工作空间' }, { label: '工作台' }],
    model: { label: modelLabel(model), value: model, readOnly: false }
  });

  return (
    <Box sx={{ minHeight: '100%', overflowY: 'auto' }}>
      <Box sx={{ maxWidth: 920, mx: 'auto', px: { xs: 2, sm: 3 }, py: { xs: 3, md: 5 } }}>
        <Stack spacing={0.75} sx={{ mb: 3 }}>
          <Typography
            component="h1"
            sx={{ fontSize: 22, fontWeight: 500, letterSpacing: '-0.01em' }}
          >
            你想构建什么？
          </Typography>
          <Typography variant="body2" color="text.secondary">
            描述目标与验收条件，Agent 会先给出计划；低频预算与授权参数收在高级配置里。
          </Typography>
        </Stack>

        <TaskComposer onModelChange={setModel} />

        <Stack
          direction="row"

          sx={{ mt: 4.5, mb: 1, alignItems: 'center', justifyContent: 'space-between' }}
        >
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <PlayCircleOutlineRounded sx={{ fontSize: 19, color: googleTokens.textSecondary }} />
            <Typography sx={{ fontSize: 15, fontWeight: 500 }}>最近运行</Typography>
          </Stack>
          <Button size="small" endIcon={<ChevronRightRounded />} onClick={() => navigate('/runs')}>
            查看全部
          </Button>
        </Stack>

        <Box
          sx={{
            bgcolor: '#FFFFFF',
            border: `1px solid ${googleTokens.outline}`,
            borderRadius: 4,
            overflow: 'hidden'
          }}
        >
          {tasks.isLoading && (
            <Typography variant="body2" color="text.secondary" sx={{ p: 3 }}>
              正在读取最近运行…
            </Typography>
          )}
          {!tasks.isLoading && recent.length === 0 && (
            <EmptyState
              compact
              title="还没有运行记录"
              description="在上方描述第一个任务，确认计划后即可在这里追踪执行过程。"
            />
          )}
          {recent.map(task => (
            <RunRow key={task.id} task={task} />
          ))}
        </Box>

        <Typography
          variant="caption"
          color="text.secondary"
          sx={{ display: 'block', mt: 2, textAlign: 'center' }}
        >
          模拟模型与真实模型分开标示；运行记录全部来自服务端持久化事件。
        </Typography>
      </Box>
    </Box>
  );
}
