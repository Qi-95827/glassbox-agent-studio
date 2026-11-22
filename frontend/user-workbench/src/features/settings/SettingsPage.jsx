import { useState } from 'react';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  Stack,
  Typography
} from '@mui/material';
import ExpandMoreRounded from '@mui/icons-material/ExpandMoreRounded';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthGate';
import { usePageChrome } from '../../layout/pageChrome';
import { evaluationApi } from '../../api/evaluations';
import CostPricePanel from '../workflows/CostPricePanel';
import ModelConnectionPanel from './ModelConnectionPanel';
import ArchitectureLensButton from '../architecture/ArchitectureLensButton';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

/** 设置：账号、模型与 API Key、环境边界、自检与高级价格版本。 */
export default function SettingsPage() {
  const { t } = useI18n();
  const { user, logout, loggingOut } = useAuth();
  const navigate = useNavigate();
  const [selfCheck, setSelfCheck] = useState(null);
  const [checking, setChecking] = useState(false);

  usePageChrome({ trail: [{ label: t('nav.workspace') }, { label: t('nav.settings') }] });

  const runCheck = async () => {
    setChecking(true);
    try {
      setSelfCheck(await evaluationApi.selfCheck());
    } catch (error) {
      setSelfCheck({
        status: 'ERROR',
        checks: [{ name: t('monitoring.requestFailed'), detail: error.message }]
      });
    } finally {
      setChecking(false);
    }
  };

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Box sx={{ maxWidth: 840, mx: 'auto', px: { xs: 2, sm: 3 }, py: { xs: 3, md: 4 } }}>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={1.5}
          sx={{ mb: 3, justifyContent: 'space-between', alignItems: { sm: 'flex-start' } }}
        >
          <Box>
            <Typography component="h1" sx={{ fontSize: 22, fontWeight: 500, mb: 0.5 }}>
              {t('settings.title')}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {t('settings.description')}
            </Typography>
          </Box>
          <ArchitectureLensButton topic="model-byok" />
        </Stack>

        <Stack spacing={2}>
          <Box
            sx={{
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`,
              borderRadius: 4,
              p: 2.5
            }}
          >
            <Typography sx={{ fontSize: 15, fontWeight: 500, mb: 1 }}>
              {t('settings.account')}
            </Typography>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <Typography variant="body2">{user?.displayName}</Typography>
              <Typography variant="caption" color="text.secondary">
                {user?.username}
              </Typography>
              <Chip
                size="small"
                label={user?.role === 'ADMIN' ? t('topbar.roleAdmin') : t('topbar.roleUser')}
              />
              {user?.legacyClaimed && <Chip size="small" label={t('settings.legacyClaimed')} />}
            </Stack>
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block', mt: 0.75 }}
            >
              {t('settings.identityNote')}
            </Typography>
            <Button
              variant="outlined"
              sx={{ mt: 1.5 }}
              disabled={loggingOut}
              onClick={() => {
                logout();
                navigate('/');
              }}
            >
              {t('topbar.logout')}
            </Button>
          </Box>

          <ModelConnectionPanel />

          <Box
            sx={{
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`,
              borderRadius: 4,
              p: 2.5
            }}
          >
            <Typography sx={{ fontSize: 15, fontWeight: 500, mb: 1 }}>
              {t('settings.boundaries')}
            </Typography>
            <Stack spacing={0.6}>
              <Typography variant="body2">{t('settings.boundaryServer')}</Typography>
              <Typography variant="body2">{t('settings.boundaryModel')}</Typography>
              <Typography variant="body2">{t('settings.boundaryWorkspace')}</Typography>
              <Typography variant="body2">{t('settings.boundaryEvaluation')}</Typography>
            </Stack>
            <Alert severity="info" sx={{ mt: 1.5 }}>
              {t('settings.boundaryNote')}
            </Alert>
          </Box>

          <Box
            sx={{
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`,
              borderRadius: 4,
              p: 2.5
            }}
          >
            <Stack
              direction="row"

              sx={{ mb: 1, justifyContent: 'space-between', alignItems: 'center' }}
            >
              <Typography sx={{ fontSize: 15, fontWeight: 500 }}>
                {t('settings.selfCheck')}
              </Typography>
              <Button variant="outlined" loading={checking} onClick={runCheck}>
                {t('settings.runSelfCheck')}
              </Button>
            </Stack>
            {selfCheck ? (
              <Stack spacing={0.6}>
                <Typography variant="body2">
                  {t('settings.overall', { status: selfCheck.status })}
                </Typography>
                {(selfCheck.checks ?? []).map(check => (
                  <Typography key={check.name} variant="body2" color="text.secondary">
                    {check.status} · {check.name}
                    {check.detail ? `：${check.detail}` : ''}
                  </Typography>
                ))}
              </Stack>
            ) : (
              <Typography variant="body2" color="text.secondary">
                {t('settings.selfCheckHint')}
              </Typography>
            )}
          </Box>

          <Box
            sx={{
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`,
              borderRadius: 4,
              p: 2.5
            }}
          >
            <Accordion
              disableGutters
              sx={{
                border: 'none',
                bgcolor: 'transparent',
                '&:before': { display: 'none' }
              }}
            >
              <AccordionSummary
                expandIcon={<ExpandMoreRounded fontSize="small" />}
                sx={{ px: 0, minHeight: 40, '& .MuiAccordionSummary-content': { my: 0.5 } }}
              >
                <Box>
                  <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
                    {t('settings.advancedPricing')}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t('settings.advancedPricingHint')}
                  </Typography>
                </Box>
              </AccordionSummary>
              <AccordionDetails sx={{ px: 0, pt: 0 }}>
                <CostPricePanel />
              </AccordionDetails>
            </Accordion>
            <Divider sx={{ my: 1.5 }} />
            <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.5 }}>
              {t('settings.about')}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {t('settings.aboutDescription')}
            </Typography>
          </Box>
        </Stack>
      </Box>
    </Box>
  );
}
