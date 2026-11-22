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
import TeachingPlaybooks from './TeachingPlaybooks';
import { entryKindLabel, modelLabel, statusLabel } from '../../constants/labels';
import { googleTokens } from '../../theme/googleTheme';
import { oneLine, timeAgo } from '../../utils/format';
import { useI18n } from '../../i18n/I18nProvider';

function RunRow({ task, t, locale }) {
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
        <StatusChip value={task.taskStatus || task.status} />
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography noWrap sx={{ fontSize: 13.5, fontWeight: 500 }}>
            {oneLine(task.goal, 96) || t('common.unnamedTask')}
          </Typography>
          <Stack direction="row" spacing={1} sx={{ minWidth: 0, alignItems: 'center' }}>
            <Typography
              variant="caption"
              sx={{ fontFamily: googleTokens.mono, color: googleTokens.textSecondary }}
            >
              #{task.runId?.slice(0, 8)}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {t('home.agentStatus', { status: statusLabel(task.status, locale) })}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {entryKindLabel(task.entryKind, locale)}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {timeAgo(task.createdAt, locale)}
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
  const { t, locale } = useI18n();
  const tasks = useTasks();
  const recent = (tasks.data ?? []).filter(task => !task.archived).slice(0, 5);
  const [model, setModel] = useState('mock');

  usePageChrome({
    trail: [{ label: t('nav.workspace') }, { label: t('nav.home') }],
    model: { label: modelLabel(model, undefined, locale), value: model, readOnly: false }
  });

  return (
    <Box sx={{ minHeight: '100%', overflowY: 'auto' }}>
      <Box sx={{ maxWidth: 920, mx: 'auto', px: { xs: 2, sm: 3 }, py: { xs: 3, md: 5 } }}>
        <Stack spacing={0.75} sx={{ mb: 3 }}>
          <Typography
            component="h1"
            sx={{ fontSize: 22, fontWeight: 500, letterSpacing: '-0.01em' }}
          >
            {t('home.title')}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {t('home.description')}
          </Typography>
        </Stack>

        <TaskComposer onModelChange={setModel} />

        <TeachingPlaybooks />

        <Stack
          direction="row"
          sx={{ mt: 4.5, mb: 1, alignItems: 'center', justifyContent: 'space-between' }}
        >
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <PlayCircleOutlineRounded sx={{ fontSize: 19, color: googleTokens.textSecondary }} />
            <Typography sx={{ fontSize: 15, fontWeight: 500 }}>{t('home.recentRuns')}</Typography>
          </Stack>
          <Button size="small" endIcon={<ChevronRightRounded />} onClick={() => navigate('/runs')}>
            {t('common.viewAll')}
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
              {t('home.loadingRuns')}
            </Typography>
          )}
          {!tasks.isLoading && recent.length === 0 && (
            <EmptyState
              compact
              title={t('home.emptyTitle')}
              description={t('home.emptyDescription')}
            />
          )}
          {recent.map(task => (
            <RunRow key={task.id} task={task} t={t} locale={locale} />
          ))}
        </Box>

        <Typography
          variant="caption"
          color="text.secondary"
          sx={{ display: 'block', mt: 2, textAlign: 'center' }}
        >
          {t('home.footer')}
        </Typography>
      </Box>
    </Box>
  );
}
