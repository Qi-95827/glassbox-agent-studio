import { useState } from 'react';
import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  IconButton,
  Stack,
  Tooltip,
  Typography
} from '@mui/material';
import SchemaOutlined from '@mui/icons-material/SchemaOutlined';
import CloseRounded from '@mui/icons-material/CloseRounded';
import ArrowForwardRounded from '@mui/icons-material/ArrowForwardRounded';
import { useInRouterContext, useNavigate } from 'react-router-dom';
import ArchitectureCanvas from './ArchitectureCanvas';
import { getArchitectureTopic, pickText } from './architectureCatalog';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

function SafeAtlasLink({ topicId, onClose }) {
  const { t } = useI18n();
  const inRouter = useInRouterContext();
  if (!inRouter) return null;
  return (
    <AtlasNavigateButton
      topicId={topicId}
      onClose={onClose}
      label={t('architecture.openAtlasAll')}
    />
  );
}

function AtlasNavigateButton({ topicId, onClose, label }) {
  const navigate = useNavigate();
  return (
    <Button
      size="small"
      endIcon={<ArrowForwardRounded sx={{ fontSize: 16 }} />}
      onClick={() => {
        onClose();
        navigate(`/architecture?topic=${topicId}`);
      }}
      sx={{ textTransform: 'none', fontWeight: 500 }}
    >
      {label}
    </Button>
  );
}

/**
 * 分散式就地架构入口按钮：放在具体功能面板角落，“用哪看哪”。
 */
export default function ArchitectureLensButton({ topic, label, size = 'small' }) {
  const { t, locale } = useI18n();
  const [open, setOpen] = useState(false);
  const meta = getArchitectureTopic(topic);
  const buttonLabel = label || t('architecture.lensButton');

  return (
    <>
      <Tooltip title={t('architecture.lensTooltip', { title: pickText(meta.title, locale) })}>
        <Button
          size={size}
          variant="outlined"
          startIcon={<SchemaOutlined sx={{ fontSize: 16 }} />}
          onClick={() => setOpen(true)}
          sx={{
            borderRadius: 9999,
            px: 1.5,
            py: 0.4,
            minHeight: 30,
            fontSize: 12.5,
            fontWeight: 500,
            textTransform: 'none',
            color: '#1967D2',
            borderColor: '#D2E3FC',
            bgcolor: '#F8FAFF',
            '&:hover': {
              bgcolor: '#E8F0FE',
              borderColor: '#AECBFA'
            }
          }}
        >
          {buttonLabel}
        </Button>
      </Tooltip>

      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        maxWidth="lg"
        fullWidth
        slotProps={{
          paper: {
            sx: {
              borderRadius: 4,
              bgcolor: '#FFFFFF',
              backgroundImage: 'none',
              maxHeight: '90vh'
            }
          }
        }}
      >
        <Stack
          direction="row"
          spacing={1}
          sx={{
            px: 3,
            py: 1.75,
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: `1px solid ${googleTokens.outline}`
          }}
        >
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Box
              sx={{
                width: 28,
                height: 28,
                borderRadius: 2,
                bgcolor: '#E8F0FE',
                color: '#1967D2',
                display: 'grid',
                placeItems: 'center'
              }}
            >
              <SchemaOutlined sx={{ fontSize: 17 }} />
            </Box>
            <Typography sx={{ fontSize: 14.5, fontWeight: 600 }}>
              {t('architecture.modalHeading')}
            </Typography>
          </Stack>
          <IconButton size="small" onClick={() => setOpen(false)} aria-label={t('common.close')}>
            <CloseRounded fontSize="small" />
          </IconButton>
        </Stack>

        <DialogContent sx={{ px: { xs: 2, sm: 3 }, py: 2.5, bgcolor: googleTokens.canvas }}>
          <ArchitectureCanvas topicId={topic} compact />
        </DialogContent>

        <DialogActions
          sx={{
            px: 3,
            py: 1.5,
            justifyContent: 'space-between',
            borderTop: `1px solid ${googleTokens.outline}`,
            bgcolor: '#FFFFFF'
          }}
        >
          <SafeAtlasLink topicId={topic} onClose={() => setOpen(false)} />
          <Button variant="outlined" size="small" onClick={() => setOpen(false)}>
            {t('common.close')}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
