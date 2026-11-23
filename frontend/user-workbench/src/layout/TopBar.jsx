import { useState } from 'react';
import {
  Avatar,
  Badge,
  Box,
  ButtonBase,
  Divider,
  IconButton,
  Menu,
  MenuItem,
  Stack,
  Tooltip,
  Typography
} from '@mui/material';
import MenuRounded from '@mui/icons-material/MenuRounded';
import HelpOutlineRounded from '@mui/icons-material/HelpOutlineRounded';
import NotificationsNoneRounded from '@mui/icons-material/NotificationsNoneRounded';
import LogoutRounded from '@mui/icons-material/LogoutRounded';
import SettingsOutlined from '@mui/icons-material/SettingsOutlined';
import FactCheckOutlined from '@mui/icons-material/FactCheckOutlined';
import TranslateRounded from '@mui/icons-material/TranslateRounded';
import { useLocation, useNavigate } from 'react-router-dom';
import StatusChip from '../components/StatusChip';
import { useAuth } from '../features/auth/AuthGate';
import { useChrome } from './pageChrome';
import { googleTokens } from '../theme/googleTheme';
import { useI18n } from '../i18n/I18nProvider';

const sectionKeys = {
  '/': 'nav.home',
  '/runs': 'nav.runs',
  '/agents': 'nav.agents',
  '/workspaces': 'nav.workspaces',
  '/evaluations': 'nav.evaluations',
  '/monitoring': 'nav.monitoring',
  '/settings': 'nav.settings',
  '/docs': 'nav.docs'
};

function fallbackTrail(pathname, t) {
  if (pathname.startsWith('/runs/')) {
    return [
      { label: t('nav.workspace') },
      { label: t('nav.runs') },
      {
        label: t('common.runId', { id: pathname.split('/')[2]?.slice(0, 8) ?? '' }),
        mono: true
      }
    ];
  }
  const base = Object.keys(sectionKeys).find(
    key => key !== '/' && (pathname === key || pathname.startsWith(`${key}/`))
  );
  return [{ label: t('nav.workspace') }, { label: t(sectionKeys[base || '/']) }];
}

function Breadcrumb({ trail }) {
  return (
    <Stack
      direction="row"
      sx={{
        minWidth: 0,
        overflow: 'hidden',
        color: googleTokens.textSecondary,
        alignItems: 'center'
      }}
    >
      {trail.map((item, index) => (
        <Stack
          key={`${item.label}:${index}`}
          direction="row"
          sx={{ minWidth: 0, alignItems: 'center' }}
        >
          {index > 0 && (
            <Box component="span" sx={{ mx: 1, color: '#BDC1C6', fontSize: 12 }}>
              /
            </Box>
          )}
          <Typography
            noWrap
            sx={{
              fontSize: 12.5,
              fontWeight: index === trail.length - 1 ? 500 : 400,
              color: index === trail.length - 1 ? '#1F1F1F' : googleTokens.textSecondary,
              fontFamily: item.mono ? googleTokens.mono : 'inherit',
              letterSpacing: item.mono ? '-0.02em' : 0
            }}
          >
            {item.label}
          </Typography>
        </Stack>
      ))}
    </Stack>
  );
}

export default function TopBar({ onOpenNav }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, loggingOut } = useAuth();
  const { page } = useChrome();
  const { t, locale, toggleLocale, isEnglish } = useI18n();
  const [userAnchor, setUserAnchor] = useState(null);
  const [notifyAnchor, setNotifyAnchor] = useState(null);

  const trail = page?.trail?.length ? page.trail : fallbackTrail(location.pathname, t);
  const initial = (user?.displayName || user?.username || 'A').slice(0, 1).toUpperCase();

  return (
    <Box
      component="header"
      sx={{
        height: 56,
        flexShrink: 0,
        display: 'flex',
        alignItems: 'center',
        gap: 1.5,
        px: { xs: 1.5, sm: 2.5 },
        bgcolor: '#FFFFFF',
        borderBottom: `1px solid ${googleTokens.outline}`
      }}
    >
      <IconButton
        aria-label={t('topbar.openNav')}
        onClick={onOpenNav}
        sx={{ display: { xs: 'inline-flex', md: 'none' } }}
        size="small"
      >
        <MenuRounded fontSize="small" />
      </IconButton>
      <Box sx={{ flex: 1, minWidth: 0, pl: { xs: 0, md: 1 } }}>
        <Breadcrumb trail={trail} />
      </Box>

      {page?.status && <StatusChip value={page.status} />}

      {page?.model?.label && (
        <Tooltip title={page.model.readOnly ? t('topbar.modelReadonly') : t('topbar.modelHint')}>
          <Box
            sx={{
              height: 32,
              px: 1.5,
              borderRadius: 9999,
              border: `1px solid ${googleTokens.outlineStrong}`,
              display: { xs: 'none', sm: 'inline-flex' },
              alignItems: 'center',
              color: '#3C4043',
              fontSize: 12.5,
              whiteSpace: 'nowrap',
              maxWidth: 190,
              overflow: 'hidden',
              textOverflow: 'ellipsis'
            }}
          >
            {page.model.label}
          </Box>
        </Tooltip>
      )}

      <Tooltip title={t('topbar.help')}>
        <IconButton size="small" onClick={() => navigate('/docs')} aria-label={t('topbar.help')}>
          <HelpOutlineRounded fontSize="small" />
        </IconButton>
      </Tooltip>
      <Tooltip title={t('topbar.notifications')}>
        <IconButton
          size="small"
          aria-label={t('topbar.notifications')}
          onClick={event => setNotifyAnchor(event.currentTarget)}
        >
          <Badge variant="dot" color="error" overlap="circular">
            <NotificationsNoneRounded fontSize="small" />
          </Badge>
        </IconButton>
      </Tooltip>
      <Menu
        anchorEl={notifyAnchor}
        open={Boolean(notifyAnchor)}
        onClose={() => setNotifyAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      >
        <Box sx={{ px: 2, py: 1.2, width: 260 }}>
          <Typography sx={{ fontSize: 13, fontWeight: 500 }}>
            {t('topbar.notificationsTitle')}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {t('topbar.notificationsEmpty')}
          </Typography>
        </Box>
        <Divider />
        <MenuItem
          onClick={() => {
            setNotifyAnchor(null);
            navigate('/evaluations');
          }}
        >
          <FactCheckOutlined fontSize="small" sx={{ mr: 1 }} />
          {t('topbar.openMonitoring')}
        </MenuItem>
      </Menu>

      <Tooltip
        title={
          isEnglish ? t('topbar.languageSwitchToChinese') : t('topbar.languageSwitchToEnglish')
        }
      >
        <ButtonBase
          onClick={toggleLocale}
          aria-label={t('topbar.language')}
          lang={locale}
          sx={{
            height: 30,
            px: 1,
            borderRadius: 9999,
            border: `1px solid ${googleTokens.outlineStrong}`,
            color: '#3C4043',
            fontSize: 12,
            fontWeight: 500,
            gap: 0.4
          }}
        >
          <TranslateRounded sx={{ fontSize: 16 }} />
          {isEnglish ? '中文' : 'EN'}
        </ButtonBase>
      </Tooltip>

      <ButtonBase
        onClick={event => setUserAnchor(event.currentTarget)}
        aria-label={t('topbar.accountMenu')}
        sx={{ borderRadius: '50%' }}
      >
        <Avatar
          sx={{
            width: 30,
            height: 30,
            bgcolor: '#E8F0FE',
            color: '#1967D2',
            fontSize: 13,
            fontWeight: 600,
            border: '1px solid #D2E3FC'
          }}
        >
          {initial}
        </Avatar>
      </ButtonBase>
      <Menu
        anchorEl={userAnchor}
        open={Boolean(userAnchor)}
        onClose={() => setUserAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      >
        <Box sx={{ px: 2, py: 1.2, minWidth: 220 }}>
          <Typography sx={{ fontSize: 14, fontWeight: 500 }}>{user?.displayName}</Typography>
          <Typography variant="caption" color="text.secondary">
            {user?.username} ·{' '}
            {user?.role === 'ADMIN' ? t('topbar.roleAdmin') : t('topbar.roleUser')}
          </Typography>
        </Box>
        <Divider />
        <MenuItem
          onClick={() => {
            setUserAnchor(null);
            navigate('/settings');
          }}
        >
          <SettingsOutlined fontSize="small" sx={{ mr: 1 }} />
          {t('topbar.settings')}
        </MenuItem>
        <MenuItem
          disabled={loggingOut}
          onClick={() => {
            setUserAnchor(null);
            logout();
          }}
        >
          <LogoutRounded fontSize="small" sx={{ mr: 1 }} />
          {t('topbar.logout')}
        </MenuItem>
      </Menu>
    </Box>
  );
}
