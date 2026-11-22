import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Collapse,
  Divider,
  Drawer,
  IconButton,
  Stack,
  Typography
} from '@mui/material';
import CloseRounded from '@mui/icons-material/CloseRounded';
import ExpandMoreRounded from '@mui/icons-material/ExpandMoreRounded';
import CheckCircleRounded from '@mui/icons-material/CheckCircleRounded';
import WarningAmberRounded from '@mui/icons-material/WarningAmberRounded';
import ErrorOutlineRounded from '@mui/icons-material/ErrorOutlineRounded';
import HealthAndSafetyOutlined from '@mui/icons-material/HealthAndSafetyOutlined';
import OpenInNewRounded from '@mui/icons-material/OpenInNewRounded';
import { useNavigate } from 'react-router-dom';
import { useRunDiagnostics } from '../../hooks/useEvaluations';
import { statusLabel } from '../../constants/labels';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

const HEALTH_TONES = {
  HEALTHY: { bg: '#E6F4EA', fg: '#137333', key: 'diagnostics.healthy' },
  WARNING: { bg: '#FEF7E0', fg: '#B06000', key: 'diagnostics.warning' },
  CRITICAL: { bg: '#FCE8E6', fg: '#C5221F', key: 'diagnostics.critical' }
};

const STEP_TONES = {
  ISSUE_FOUND: {
    bg: '#FCE8E6',
    border: '#F6C7C4',
    fg: '#C5221F',
    key: 'diagnostics.issueFound',
    icon: <ErrorOutlineRounded sx={{ fontSize: 19 }} />
  },
  CHECK_RECOMMENDED: {
    bg: '#FEF7E0',
    border: '#FEEFC3',
    fg: '#B06000',
    key: 'diagnostics.checkRecommended',
    icon: <WarningAmberRounded sx={{ fontSize: 19 }} />
  },
  LOOKS_OK: {
    bg: '#E6F4EA',
    border: '#CEEAD6',
    fg: '#137333',
    key: 'diagnostics.looksOk',
    icon: <CheckCircleRounded sx={{ fontSize: 19 }} />
  }
};

/**
 * 快捷故障排查向导：展示服务端基于持久化证据生成的五步排查结论。
 * 命中问题的步骤自动展开并高亮，正常步骤默认折叠；每步可一键跳转到对应节点透视 Tab。
 */
export default function RunDiagnosticsDrawer({ open, runId, onClose }) {
  const { t, locale } = useI18n();
  const navigate = useNavigate();
  const query = useRunDiagnostics(runId, open);
  const data = query.data;
  const [expanded, setExpanded] = useState(() => new Set());

  // 只依赖问题步骤的稳定签名，避免数据对象刷新时反复重置展开状态或触发渲染循环。
  const attentionSignature = (data?.troubleshootingSteps ?? [])
    .filter(step => step.status !== 'LOOKS_OK')
    .map(step => step.stepOrder)
    .join(',');

  useEffect(() => {
    setExpanded(
      new Set(
        attentionSignature
          .split(',')
          .filter(Boolean)
          .map(value => Number(value))
      )
    );
  }, [attentionSignature]);

  const toggle = order => {
    setExpanded(current => {
      const next = new Set(current);
      if (next.has(order)) next.delete(order);
      else next.add(order);
      return next;
    });
  };

  const jump = route => {
    if (!route) return;
    onClose?.();
    navigate(route);
  };

  const healthTone = HEALTH_TONES[data?.healthLevel];
  const health = {
    bg: healthTone?.bg || '#F1F3F4',
    fg: healthTone?.fg || '#5F6368',
    label: healthTone ? t(healthTone.key) : t('diagnostics.diagnosing')
  };

  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      slotProps={{ paper: { sx: { width: '100%', maxWidth: 600, backgroundImage: 'none' } } }}
    >
      <Stack
        direction="row"
        spacing={1}
        sx={{
          px: 2.5,
          py: 1.8,
          alignItems: 'center',
          borderBottom: `1px solid ${googleTokens.outline}`,
          bgcolor: '#FFFFFF'
        }}
      >
        <Box
          sx={{
            width: 34,
            height: 34,
            borderRadius: 2.5,
            bgcolor: '#E8F0FE',
            color: '#1967D2',
            display: 'grid',
            placeItems: 'center'
          }}
        >
          <HealthAndSafetyOutlined sx={{ fontSize: 20 }} />
        </Box>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography sx={{ fontSize: 16, fontWeight: 600 }}>{t('diagnostics.title')}</Typography>
          <Typography variant="caption" color="text.secondary">
            {t('diagnostics.subtitle')}
          </Typography>
        </Box>
        <IconButton onClick={onClose} size="small" aria-label={t('diagnostics.close')}>
          <CloseRounded fontSize="small" />
        </IconButton>
      </Stack>

      <Box sx={{ px: 2.5, py: 2, overflowY: 'auto', flex: 1 }}>
        {query.isLoading && (
          <Stack
            direction="row"
            spacing={1}
            sx={{ py: 5, alignItems: 'center', justifyContent: 'center' }}
          >
            <CircularProgress size={20} />
            <Typography variant="body2" color="text.secondary">
              {t('diagnostics.loading')}
            </Typography>
          </Stack>
        )}
        {query.isError && (
          <Alert severity="error">
            {t('diagnostics.loadFailed', { message: query.error?.message || t('common.unknown') })}
          </Alert>
        )}

        {data && (
          <Stack spacing={2}>
            <Box
              sx={{
                p: 2,
                borderRadius: 3,
                border: `1px solid ${googleTokens.outline}`,
                bgcolor: '#FFFFFF'
              }}
            >
              <Stack
                direction="row"
                spacing={1}
                sx={{ alignItems: 'center', mb: 1, flexWrap: 'wrap' }}
              >
                <Chip
                  size="small"
                  label={health.label}
                  sx={{ bgcolor: health.bg, color: health.fg, fontWeight: 600 }}
                />
                <Chip
                  size="small"
                  label={t('diagnostics.runStatus', {
                    status: statusLabel(data.runStatus, locale)
                  })}
                  sx={{ bgcolor: '#F1F3F4', color: '#5F6368' }}
                />
                <Chip
                  size="small"
                  label={t('diagnostics.taskStatus', {
                    status: statusLabel(data.taskStatus, locale)
                  })}
                  sx={{ bgcolor: '#F1F3F4', color: '#5F6368' }}
                />
              </Stack>
              <Typography sx={{ fontSize: 13, color: googleTokens.textSecondary, mb: 0.4 }}>
                {data.taskTitle || t('common.unnamedTask')}
              </Typography>
              <Typography sx={{ fontSize: 15, fontWeight: 600, lineHeight: 1.6 }}>
                {data.primaryIssue}
              </Typography>
            </Box>

            <Box>
              <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                {t('diagnostics.checklist')}
              </Typography>
              <Stack spacing={1} sx={{ mt: 1 }}>
                {(data.troubleshootingSteps ?? []).map(step => {
                  const tone = STEP_TONES[step.status] || STEP_TONES.LOOKS_OK;
                  const isOpen = expanded.has(step.stepOrder);
                  return (
                    <Box
                      key={step.stepOrder}
                      sx={{
                        borderRadius: 3,
                        border: `1px solid ${isOpen ? tone.border : googleTokens.outline}`,
                        bgcolor: isOpen ? '#FFFFFF' : '#FAFAFA',
                        overflow: 'hidden'
                      }}
                    >
                      <Stack
                        direction="row"
                        spacing={1}
                        role="button"
                        tabIndex={0}
                        onClick={() => toggle(step.stepOrder)}
                        onKeyDown={event => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            toggle(step.stepOrder);
                          }
                        }}
                        sx={{
                          px: 1.6,
                          py: 1.3,
                          alignItems: 'center',
                          cursor: 'pointer',
                          '&:hover': { bgcolor: '#F8F9FA' }
                        }}
                      >
                        <Box
                          sx={{
                            width: 26,
                            height: 26,
                            borderRadius: '50%',
                            bgcolor: tone.bg,
                            color: tone.fg,
                            display: 'grid',
                            placeItems: 'center',
                            flexShrink: 0
                          }}
                        >
                          {tone.icon}
                        </Box>
                        <Typography sx={{ flex: 1, minWidth: 0, fontSize: 13.5, fontWeight: 600 }}>
                          {step.title}
                        </Typography>
                        <Chip
                          size="small"
                          label={t(tone.key)}
                          sx={{ bgcolor: tone.bg, color: tone.fg, height: 21, fontSize: 11 }}
                        />
                        <ExpandMoreRounded
                          sx={{
                            fontSize: 19,
                            color: googleTokens.textSecondary,
                            transform: isOpen ? 'rotate(180deg)' : 'rotate(0deg)',
                            transition: 'transform .15s ease'
                          }}
                        />
                      </Stack>
                      <Collapse in={isOpen}>
                        <Box sx={{ px: 1.7, pb: 1.6, pt: 0.2 }}>
                          <Typography
                            variant="body2"
                            sx={{ color: googleTokens.text, lineHeight: 1.7, mb: 1.2 }}
                          >
                            {step.finding}
                          </Typography>
                          <Button
                            size="small"
                            variant={step.status === 'LOOKS_OK' ? 'text' : 'outlined'}
                            endIcon={<OpenInNewRounded sx={{ fontSize: 15 }} />}
                            onClick={event => {
                              event.stopPropagation();
                              jump(step.targetRoute);
                            }}
                            sx={{ minHeight: 30, fontSize: 12.5 }}
                          >
                            {step.actionLabel || t('common.detail')}
                          </Button>
                        </Box>
                      </Collapse>
                    </Box>
                  );
                })}
              </Stack>
            </Box>

            <Divider />
            <Typography variant="caption" color="text.secondary">
              {t('diagnostics.tip')}
            </Typography>
          </Stack>
        )}
      </Box>
    </Drawer>
  );
}
