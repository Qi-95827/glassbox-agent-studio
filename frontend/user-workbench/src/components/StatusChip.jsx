import { Box, keyframes } from '@mui/material';
import CheckRounded from '@mui/icons-material/CheckRounded';
import CloseRounded from '@mui/icons-material/CloseRounded';
import PriorityHighRounded from '@mui/icons-material/PriorityHighRounded';
import { STATUS_TONES, statusLabel, statusTone } from '../constants/labels';
import { useI18n } from '../i18n/I18nProvider';

const pulse = keyframes`
  0% { box-shadow: 0 0 0 0 rgba(25, 103, 210, 0.45); }
  70% { box-shadow: 0 0 0 5px rgba(25, 103, 210, 0); }
  100% { box-shadow: 0 0 0 0 rgba(25, 103, 210, 0); }
`;

/**
 * 语义状态胶囊：浅底深字 + 简洁图标；运行中额外显示呼吸点。
 * 所有状态都来自后端枚举，未知值原样显示。
 */
export default function StatusChip({ value, label, sx }) {
  const { locale } = useI18n();
  const tone = STATUS_TONES[statusTone(value)];
  const text = label || statusLabel(value, locale);
  const active = statusTone(value) === 'active';
  const success = statusTone(value) === 'success';
  const error = statusTone(value) === 'error';

  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: '5px',
        height: 22,
        px: '8px',
        borderRadius: 9999,
        bgcolor: tone.background,
        color: tone.color,
        fontSize: 11.5,
        fontWeight: 500,
        lineHeight: 1,
        whiteSpace: 'nowrap',
        ...sx
      }}
    >
      {active && (
        <Box
          component="span"
          sx={{
            width: 6,
            height: 6,
            borderRadius: '50%',
            bgcolor: 'currentColor',
            animation: `${pulse} 1.9s ease-out infinite`
          }}
        />
      )}
      {success && <CheckRounded sx={{ fontSize: 13 }} />}
      {error && <CloseRounded sx={{ fontSize: 13 }} />}
      {statusTone(value) === 'warning' && <PriorityHighRounded sx={{ fontSize: 13 }} />}
      {!active && !success && !error && statusTone(value) !== 'warning' && (
        <Box
          component="span"
          sx={{ width: 5, height: 5, borderRadius: '50%', bgcolor: 'currentColor', opacity: 0.55 }}
        />
      )}
      {text}
    </Box>
  );
}
