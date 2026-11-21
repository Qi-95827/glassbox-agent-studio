import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  FormControlLabel,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography
} from '@mui/material';
import OpenInNewRounded from '@mui/icons-material/OpenInNewRounded';
import ArchiveOutlined from '@mui/icons-material/ArchiveOutlined';
import UnarchiveOutlined from '@mui/icons-material/UnarchiveOutlined';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import StatusChip from '../../components/StatusChip';
import EmptyState from '../../components/EmptyState';
import { useTasks } from '../../hooks/useTasks';
import { usePageChrome } from '../../layout/pageChrome';
import { useToast } from '../../components/ToastProvider';
import { tasksApi } from '../../api/tasks';
import { ENTRY_KINDS, RUN_STATES, statusLabel } from '../../constants/labels';
import { dateTime, oneLine, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';

/** 运行历史：服务端任务列表，刷新后仍可按运行 ID 恢复视图。 */
export default function RunHistoryPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const queryClient = useQueryClient();
  const tasks = useTasks();
  const [archived, setArchived] = useState(false);
  const [status, setStatus] = useState('ALL');
  const [keyword, setKeyword] = useState('');

  usePageChrome({ trail: [{ label: '工作空间' }, { label: '观察运行' }] });

  const rows = (tasks.data ?? [])
    .filter(task => task.archived === archived)
    .filter(
      task =>
        status === 'ALL' || (task.taskStatus || task.status) === status || task.status === status
    )
    .filter(task => {
      if (!keyword.trim()) return true;
      const query = keyword.trim().toLowerCase();
      return (
        String(task.goal ?? '')
          .toLowerCase()
          .includes(query) ||
        String(task.runId ?? '')
          .toLowerCase()
          .includes(query)
      );
    });

  const archive = async task => {
    try {
      await tasksApi.archive(task.id, !task.archived);
      await queryClient.invalidateQueries({ queryKey: ['tasks'] });
      toast.success(task.archived ? '已取消归档' : '任务已归档，运行和证据保留');
    } catch (error) {
      toast.error(error.message);
    }
  };

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Box sx={{ maxWidth: 1100, mx: 'auto', px: { xs: 2, sm: 3 }, py: { xs: 3, md: 4 } }}>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}

          sx={{ mb: 2.5, justifyContent: 'space-between', gap: 1.5 }}
        >
          <Box>
            <Typography component="h1" sx={{ fontSize: 22, fontWeight: 500 }}>
              运行历史
            </Typography>
            <Typography variant="body2" color="text.secondary">
              从这里回到任一运行，查看计划、交接、工具回执与交付结果。
            </Typography>
          </Box>
          <Button variant="contained" onClick={() => navigate('/?new=1')}>
            新建任务
          </Button>
        </Stack>

        <Stack
          direction={{ xs: 'column', md: 'row' }}
          spacing={1.5}

          sx={{
            mb: 2,
            p: 1.5,
            bgcolor: '#FFFFFF',
            border: `1px solid ${googleTokens.outline}`,
            borderRadius: 3,
            alignItems: { md: 'center' }
          }}
        >
          <TextField
            size="small"
            placeholder="搜索目标或运行 ID"
            value={keyword}
            onChange={event => setKeyword(event.target.value)}
            sx={{ flex: 1, minWidth: 220 }}
          />

          <TextField
            select
            size="small"
            label="状态"
            value={status}
            onChange={event => setStatus(event.target.value)}
            sx={{ minWidth: 150 }}
          >
            <MenuItem value="ALL">全部状态</MenuItem>
            {Object.entries(RUN_STATES)
              .filter(([key]) =>
                [
                  'IN_PROGRESS',
                  'AWAITING_REVIEW',
                  'DELIVERED',
                  'COMPLETED',
                  'BLOCKED',
                  'FAILED',
                  'CANCELLED'
                ].includes(key)
              )
              .map(([key, label]) => (
                <MenuItem key={key} value={key}>
                  {label}
                </MenuItem>
              ))}
          </TextField>
          <FormControlLabel
            control={
              <Switch
                size="small"
                checked={archived}
                onChange={event => setArchived(event.target.checked)}
              />
            }
            label={<Typography variant="body2">已归档</Typography>}
          />
        </Stack>

        {tasks.isError && <Alert severity="error">{tasks.error.message}</Alert>}
        {tasks.isLoading && (
          <Typography variant="body2" color="text.secondary">
            正在读取运行记录…
          </Typography>
        )}

        <Stack spacing={1.2}>
          {rows.map(task => (
            <Box
              key={task.id}
              sx={{
                bgcolor: '#FFFFFF',
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 4,
                px: { xs: 1.75, sm: 2.25 },
                py: 1.6,
                display: 'flex',
                gap: 1.5,
                alignItems: { sm: 'center' },
                flexDirection: { xs: 'column', sm: 'row' }
              }}
            >
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Stack
                  direction="row"
                  spacing={0.9}
                  sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                >
                  <StatusChip value={task.taskStatus || task.status} />
                  <Typography sx={{ fontSize: 14, fontWeight: 500 }}>
                    {oneLine(task.goal, 90) || '未命名任务'}
                  </Typography>
                </Stack>
                <Stack direction="row" spacing={1.2} sx={{ mt: 0.6, flexWrap: 'wrap' }}>
                  <Typography
                    variant="caption"
                    sx={{ fontFamily: googleTokens.mono }}
                    color="text.secondary"
                  >
                    #{shortId(task.runId)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    Agent：{statusLabel(task.status)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {ENTRY_KINDS[task.entryKind] || '通用目标'}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {task.modelMode === 'deepseek' ? 'DeepSeek Flash' : '模拟模型'}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {dateTime(task.createdAt)}
                  </Typography>
                  {task.archived && (
                    <Chip size="small" label="已归档" sx={{ height: 20, bgcolor: '#F1F3F4' }} />
                  )}
                </Stack>
              </Box>
              <Stack direction="row" spacing={0.75} sx={{ flexShrink: 0 }}>
                <Button
                  size="small"
                  startIcon={<OpenInNewRounded />}
                  onClick={() => navigate(`/runs/${task.runId}`)}
                >
                  打开运行
                </Button>
                <Button
                  size="small"
                  color="inherit"
                  startIcon={task.archived ? <UnarchiveOutlined /> : <ArchiveOutlined />}
                  onClick={() => archive(task)}
                >
                  {task.archived ? '取消归档' : '归档'}
                </Button>
              </Stack>
            </Box>
          ))}
        </Stack>

        {!tasks.isLoading && rows.length === 0 && (
          <Box
            sx={{
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`,
              borderRadius: 4
            }}
          >
            <EmptyState
              title={archived ? '没有已归档的运行' : '没有匹配的运行'}
              description={
                archived ? '归档任务会保留全部运行与证据。' : '调整筛选条件，或创建一个新任务。'
              }
              action={{ label: '新建任务', onClick: () => navigate('/?new=1') }}
            />
          </Box>
        )}
      </Box>
    </Box>
  );
}
