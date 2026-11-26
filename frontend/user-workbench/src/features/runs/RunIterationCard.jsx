import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import AutoAwesomeMotionRounded from '@mui/icons-material/AutoAwesomeMotionRounded';
import { runsApi } from '../../api/runs';
import { useToast } from '../../components/ToastProvider';
import { googleTokens } from '../../theme/googleTheme';
import { statusLabel } from '../../constants/labels';
import { useI18n } from '../../i18n/I18nProvider';
import { useAuth } from '../auth/AuthGate';

const TERMINAL = new Set(['COMPLETED', 'BLOCKED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'LIMITED']);
const MAX_FEEDBACK = 2000;

/**
 * 交付说明底部的「提调改意见继续运行」卡片。
 *
 * <p>只在运行进入终态后出现：提交后服务端会在同一个 taskId 下新建一轮运行（源运行的证据保持不变），
 * 页面随即跳到新一轮实时观看执行。模型可沿用当前任务设置，也可显式切换；宿主机全局 Key 仅管理员可见，
 * 避免普通用户点出 403。
 */
export default function RunIterationCard({ run, taskId, roundIndex, onStarted }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const auth = useAuth();
  const [feedback, setFeedback] = useState('');
  const [modelMode, setModelMode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const isAdmin = auth?.user?.role === 'ADMIN';
  const runStatus = run?.STATUS ?? null;
  const terminal = Boolean(runStatus && TERMINAL.has(runStatus));
  const nextRound = (Number(roundIndex) || 1) + 1;

  if (!terminal) {
    return (
      <Box
        sx={{
          bgcolor: '#FFFFFF',
          border: `1px solid ${googleTokens.outline}`,
          borderRadius: 4,
          p: { xs: 2, sm: 2.5 }
        }}
      >
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <AutoAwesomeMotionRounded sx={{ fontSize: 18, color: '#5F6368' }} />
          <Typography sx={{ fontSize: 15, fontWeight: 500 }}>{t('run.iteration.title')}</Typography>
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.6 }}>
          {t('run.iteration.unavailable')}
        </Typography>
      </Box>
    );
  }

  const submit = async () => {
    const text = feedback.trim();
    if (!text) {
      setError(t('run.iteration.feedbackRequired'));
      return;
    }
    setSubmitting(true);
    setError('');
    try {
      const created = await runsApi.iterate(run.ID, {
        feedback: text.slice(0, MAX_FEEDBACK),
        ...(modelMode ? { modelMode } : {})
      });
      toast.success(t('run.iteration.started', { round: created.roundIndex }));
      setFeedback('');
      onStarted?.(created);
    } catch (failure) {
      setError(failure.message);
      toast.error(failure.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Box
      data-testid="run-iteration-card"
      data-task-id={taskId || undefined}
      sx={{
        bgcolor: '#F6F9FF',
        border: '1px solid #D2E3FC',
        borderRadius: 4,
        p: { xs: 2, sm: 2.5 }
      }}
    >
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1}
        sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between' }}
      >
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <AutoAwesomeMotionRounded sx={{ fontSize: 18, color: '#1967D2' }} />
          <Typography sx={{ fontSize: 15, fontWeight: 500 }}>{t('run.iteration.title')}</Typography>
          <Chip
            size="small"
            label={t('run.iteration.nextRound', { round: nextRound })}
            sx={{ bgcolor: '#FFFFFF' }}
          />
        </Stack>
        <Chip
          size="small"
          label={statusLabel(runStatus, locale)}
          sx={{ bgcolor: '#FFFFFF', alignSelf: { xs: 'flex-start', sm: 'center' } }}
        />
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.6 }}>
        {t('run.iteration.description')}
      </Typography>
      <TextField
        fullWidth
        multiline
        minRows={3}
        maxRows={8}
        value={feedback}
        slotProps={{
          htmlInput: { maxLength: MAX_FEEDBACK, 'data-testid': 'run-iteration-feedback' }
        }}
        label={t('run.iteration.feedbackLabel')}
        placeholder={t('run.iteration.feedbackPlaceholder')}
        onChange={event => setFeedback(event.target.value)}
        sx={{ mt: 1.5, bgcolor: '#FFFFFF' }}
        helperText={`${feedback.trim().length} / ${MAX_FEEDBACK}`}
      />
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.2}
        sx={{ mt: 1.2, alignItems: { sm: 'center' }, justifyContent: 'space-between' }}
      >
        <FormControl size="small" sx={{ minWidth: 220, bgcolor: '#FFFFFF' }}>
          <InputLabel id="run-iteration-model-label">{t('run.iteration.modelMode')}</InputLabel>
          <Select
            labelId="run-iteration-model-label"
            label={t('run.iteration.modelMode')}
            value={modelMode}
            onChange={event => setModelMode(event.target.value)}
          >
            <MenuItem value="">{t('run.iteration.modelInherit')}</MenuItem>
            <MenuItem value="mock">{t('run.iteration.modelMock')}</MenuItem>
            <MenuItem value="user">{t('run.iteration.modelUser')}</MenuItem>
            {isAdmin && <MenuItem value="deepseek">{t('run.iteration.modelDeepseek')}</MenuItem>}
          </Select>
        </FormControl>
        <Button
          variant="contained"
          data-testid="run-iteration-submit"
          loading={submitting}
          disabled={submitting || !feedback.trim()}
          onClick={submit}
          sx={{ whiteSpace: 'nowrap' }}
        >
          {submitting ? t('run.iteration.submitting') : t('run.iteration.submit')}
        </Button>
      </Stack>
      {error && (
        <Alert severity="error" sx={{ mt: 1.2 }} data-testid="run-iteration-error">
          {error}
        </Alert>
      )}
    </Box>
  );
}
